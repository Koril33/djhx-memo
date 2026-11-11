package app.djhx.memo

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.djhx_memo_core.CredentialStore
import uniffi.djhx_memo_core.MemoApp
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PermanentDeletionTest {
    @Test fun accountPermanentDeletionConvergesThroughRealHTTP() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("serverUrl").orEmpty()
        org.junit.Assume.assumeTrue(url.isNotEmpty())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("purge-http-${UUID.randomUUID()}")
        val credentials = object : CredentialStore {
            override fun load(profileId: String): String? = null
            override fun save(profileId: String, token: String) = false
            override fun remove(profileId: String) = Unit
        }
        val a = MemoApp(root.resolve("a").absolutePath, credentials, true)
        val b = MemoApp(root.resolve("b").absolutePath, credentials, true)
        fun call(app: MemoApp, action: String, vararg fields: Pair<String, Any>): JSONObject {
            val request = JSONObject().put("action", action)
            fields.forEach { request.put(it.first, it.second) }
            val response = JSONObject(app.invoke(request.toString()))
            assertTrue("native HTTP operation failed", response.optBoolean("ok"))
            return response
        }
        fun sync(app: MemoApp) {
            val result = call(app, "sync", "wait" to true).getJSONObject("data")
            assertTrue("sync failed", result.isNull("error"))
        }
        try {
            call(a, "foreground", "active" to false)
            call(b, "foreground", "active" to false)
            val username = "purge_${UUID.randomUUID().toString().take(12)}"
            call(
                a,
                "register",
                "server_url" to url,
                "username" to username,
                "password" to "isolated test password",
                "device_name" to "A",
                "invite_code" to args.getString("inviteCode").orEmpty()
            )
            call(
                b,
                "login",
                "server_url" to url,
                "username" to username,
                "password" to "isolated test password",
                "device_name" to "B"
            )
            val note = call(
                a,
                "create",
                "content" to "Android cloud purge fixture",
                "request_id" to UUID.randomUUID().toString()
            ).getJSONObject("data")
            val id = note.getString("id")
            sync(a)
            sync(b)
            val current = call(a, "get", "id" to id).getJSONObject("data")
            val deleted = call(
                a,
                "set_deleted",
                "id" to id,
                "expected_generation" to current.getString("generation"),
                "deleted" to true
            ).getJSONObject("data")
            sync(a)
            sync(b)
            val known = call(a, "get", "id" to id).getJSONObject("data")
            assertEquals(deleted.getString("content"), known.getString("content"))
            call(a, "purge", "id" to id, "expected_generation" to known.getString("generation"))
            sync(a)
            val stale = call(b, "get", "id" to id).getJSONObject("data")
            call(
                b,
                "set_deleted",
                "id" to id,
                "expected_generation" to stale.getString("generation"),
                "deleted" to false
            )
            sync(b)
            val final = call(b, "get", "id" to id).getJSONObject("data")
            assertEquals("purged", final.getString("purge_state"))
            assertEquals("", final.getString("content"))
            assertEquals(0, call(b, "list", "deleted" to true).getJSONArray("data").length())
            assertEquals(0, call(b, "list").getJSONArray("data").length())
        } finally {
            a.destroy()
            b.destroy()
            root.deleteRecursively()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("permanent deletion did not settle", condition())
    }

    @Test fun nativeBindingPermanentlyDeletesOnlyOneTrashNoteAndPersistsMarker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("purge-test-${UUID.randomUUID()}")
        val credentials = object : CredentialStore {
            override fun load(profileId: String): String? = null
            override fun save(profileId: String, token: String) = false
            override fun remove(profileId: String) = Unit
        }
        fun request(core: MemoApp, action: String, vararg fields: Pair<String, Any>): JSONObject {
            val input = JSONObject().put("action", action)
            fields.forEach { input.put(it.first, it.second) }
            return JSONObject(core.invoke(input.toString()))
        }
        val core = MemoApp(root.absolutePath, credentials, true)
        val note = request(
            core,
            "create",
            "request_id" to UUID.randomUUID().toString(),
            "content" to "isolated purge fixture"
        ).getJSONObject("data")
        val kept = request(
            core,
            "create",
            "request_id" to UUID.randomUUID().toString(),
            "content" to "kept fixture"
        ).getJSONObject("data")
        val deleted = request(
            core,
            "set_deleted",
            "id" to note.getString("id"),
            "expected_generation" to note.getString("generation"),
            "deleted" to true
        ).getJSONObject("data")
        val purged = request(
            core,
            "purge",
            "id" to note.getString("id"),
            "expected_generation" to deleted.getString("generation")
        ).getJSONObject("data")
        assertEquals("purged", purged.getString("purge_state"))
        assertEquals("", purged.getString("content"))
        assertEquals(0, request(core, "list", "deleted" to true).getJSONArray("data").length())
        assertEquals(kept.getString("id"), request(core, "list").getJSONArray("data").getJSONObject(0).getString("id"))
        core.destroy()
        val reopened = MemoApp(root.absolutePath, credentials, true)
        assertEquals(
            "purged",
            request(reopened, "get", "id" to note.getString("id")).getJSONObject("data").getString("purge_state")
        )
        val restore = request(
            reopened,
            "set_deleted",
            "id" to note.getString("id"),
            "expected_generation" to purged.getString("generation"),
            "deleted" to false
        )
        assertEquals("NOTE_PURGED", restore.getJSONObject("error").getString("code"))
        reopened.destroy()
        root.deleteRecursively()
    }

    private fun clickLabel(label: String, dialogTitle: String? = null) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: AccessibilityNodeInfo?, wanted: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == wanted || node.contentDescription?.toString() == wanted) return node
            for (index in 0 until node.childCount) find(node.getChild(index), wanted)?.let { return it }
            return null
        }
        var found: AccessibilityNodeInfo? = null
        waitFor {
            val root = automation.rootInActiveWindow
            found = find(root, label)
            val dialogReady = dialogTitle == null || find(root, dialogTitle) != null
            found != null && dialogReady
        }
        var target = found!!
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("control not clickable", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun trashMenuRequiresConfirmationAndDeletesTheSelectedFixture() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: MemoModel
            scenario.onActivity { model = ViewModelProvider(it)[MemoModel::class.java] }
            waitFor { model.state.has("profile") }
            scenario.onActivity { model.switchProfile("local") }
            waitFor { !model.busy }
            scenario.onActivity { model.newNote() }
            waitFor { model.editorOpen && !model.busy }
            scenario.onActivity { model.edit("purge UI fixture ${UUID.randomUUID()}") }
            waitFor { !model.pending && model.selected != null }
            val id = model.selected!!.getString("id")
            scenario.onActivity { model.setDeleted(true) }
            waitFor { !model.editorOpen && !model.busy }
            scenario.onActivity { model.showTrash(true) }
            waitFor { model.trash && !model.busy && model.notes.any { it.optString("id") == id } }
            scenario.onActivity { model.open(model.notes.first { it.optString("id") == id }) }
            waitFor { model.editorOpen && !model.busy }
            clickLabel(context.getString(R.string.more_actions))
            clickLabel(context.getString(R.string.purge_note))
            clickLabel(context.getString(R.string.cancel))
            assertEquals(id, model.selected!!.getString("id"))
            clickLabel(context.getString(R.string.more_actions))
            clickLabel(context.getString(R.string.purge_note))
            clickLabel(context.getString(R.string.purge_note), context.getString(R.string.purge_confirm))
            waitFor { !model.editorOpen && !model.busy }
            assertTrue(model.notes.none { it.optString("id") == id })
            val current = (context.applicationContext as MemoApplication).gateway.obj("get", "id" to id)
            assertEquals("purged", current.getString("purge_state"))
        }
    }
}
