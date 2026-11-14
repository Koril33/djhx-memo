package app.djhx.memo

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit receipts, not a space change or busy completion, dismiss authentication. */
@RunWith(AndroidJUnit4::class)
class AccountActionsTest {
    private class Fixture : CoreBridge {
        val events = mutableListOf<String>()
        var canSave = true
        var failure = false
        var current = snapshot("account-a", "synced")
        var returned = "account-a"
        var accepted = false
        var notice: UiText? = null
        val actions = AccountActions(
            this,
            {
                events += "save"
                canSave
            },
            { events += "refresh" },
            { events += "clear" },
            { notice = it },
            { current },
            { current.getJSONObject("profile").getString("id") }
        )

        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any {
            events += action
            if (failure) throw CoreFailure("AUTH_REQUIRED", "fixture rejection")
            return JSONObject().put("profile", JSONObject().put("id", returned)).put("import_count", 0)
        }

        suspend fun login() = actions.authenticate(false, "https://fixture.invalid", "fixture", "", "", "test") {
            accepted = true
        }
    }

    @Test fun successfulReceiptClosesOnlyAfterSaveAndRefresh() = runBlocking {
        val fixture = Fixture()
        fixture.login()
        assertEquals(listOf("save", "login", "clear", "refresh"), fixture.events)
        assertTrue(fixture.accepted)
        assertEquals(R.string.login_success, fixture.notice?.resource)
    }

    @Test fun failedSaveNeverSubmitsOrCloses() = runBlocking {
        val fixture = Fixture().apply { canSave = false }
        fixture.login()
        assertEquals(listOf("save"), fixture.events)
        assertFalse(fixture.accepted)
        assertNull(fixture.notice)
    }

    @Test fun rejectedLoginKeepsTheFormAndDoesNotReportSuccess() = runBlocking {
        val fixture = Fixture().apply { failure = true }
        try {
            fixture.login()
            fail("login must fail")
        } catch (_: CoreFailure) {
            assertFalse(fixture.accepted)
            assertNull(fixture.notice)
            assertEquals(listOf("save", "login"), fixture.events)
        }
    }

    @Test fun differentActiveProfileCannotAcceptTheReceipt() = runBlocking {
        val fixture = Fixture().apply { current = snapshot("account-b", "synced") }
        fixture.login()
        assertFalse(fixture.accepted)
        assertNull(fixture.notice)
    }

    @Test fun expiredSessionCannotAcceptTheReceipt() = runBlocking {
        val fixture = Fixture().apply { current = snapshot("account-a", "auth_required") }
        fixture.login()
        assertFalse(fixture.accepted)
        assertNull(fixture.notice)
    }

    companion object {
        private fun snapshot(id: String, sync: String) = JSONObject()
            .put("profile", JSONObject().put("id", id)).put("sync", JSONObject().put("state", sync))
    }
}
