package app.djhx.memo

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Saved-account presentation and reauthentication intent are exercised in real Compose. */
@RunWith(AndroidJUnit4::class)
class AccountSettingsTest {
    private class Accounts : CoreBridge {
        var active = "account-a"
        private fun profile(id: String) = JSONObject().put("id", id).put("kind", "account")
            .put("label", id).put("username", id).put("server_url", "https://fixture.invalid").put("read_only", false)
        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any = when (action) {
            "state" -> JSONObject().put("profile", profile(active))
                .put("profiles", JSONArray().put(profile("account-a")).put(profile("account-b")))
                .put("event_seq", active).put("redirects", JSONObject())
                .put("credential_persistent", true)
                .put("sync", JSONObject().put("state", if (active == "account-a") "auth_required" else "synced"))
            "list" -> JSONArray()
            "switch" -> {
                active = fields.toMap()["profile_id"].toString()
                JSONObject()
            }
            else -> error("Unexpected account UI fixture action")
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.text?.toString() == label || current.contentDescription?.toString() == label) return current
            for (index in 0 until current.childCount) current.getChild(
                index
            )?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        assertTrue("Account UI fixture did not settle", condition())
    }

    private fun click(label: String) {
        waitFor { node(label) != null }
        var target = checkNotNull(node(label))
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun savedAccountsKeepLoginCollapsedAndChangingSpaceAbandonsReauthentication() {
        val app = ApplicationProvider.getApplicationContext<MemoApplication>()
        val store = ViewModelStore()
        lateinit var model: MemoModel
        val original = PresentationStore(app).preferences
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    model = MemoModel(app, Accounts())
                    store.put("account-ui", model)
                    activity.presentation.update(DisplayPreferences("en", "light", "Asia/Shanghai"))
                    activity.setContent {
                        MemoPresentation(activity.presentation) {
                            AccountSettings(model, "spaces", onClose = {})
                        }
                    }
                }
                waitFor { node("Add account") != null && node("Sign in again") != null }
                assertNull("Saved accounts must not expose a duplicate login form", node("Server address"))
                click("Sign in again")
                waitFor { node("Server address") != null }
                scenario.onActivity { model.switchProfile("account-b") }
                waitFor { !model.busy && model.state.getJSONObject("profile").getString("id") == "account-b" }
                waitFor { node("Server address") == null && node("Sign in again") == null }
                assertNotNull(node("Add account"))
                click("Add account")
                waitFor { node("Server address") != null }
                click("Close")
                waitFor { node("Server address") == null }
                assertEquals("account-b", model.state.getJSONObject("profile").getString("id"))
            }
        } finally {
            instrumentation.runOnMainSync {
                store.clear()
                PresentationStore(app).update(original)
            }
        }
    }
}
