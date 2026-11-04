package app.djhx.memo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.djhx_memo_core.CredentialStore
import uniffi.djhx_memo_core.MemoApp
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoreBindingTest {
    private class MemoryCredentials : CredentialStore {
        private val tokens = mutableMapOf<String, String>()
        override fun load(profileId: String): String? = tokens[profileId]
        override fun save(profileId: String, token: String): Boolean {
            tokens[profileId] = token
            return true
        }
        override fun remove(profileId: String) { tokens.remove(profileId) }
    }

    private fun request(app: MemoApp, action: String, vararg fields: Pair<String, Any>): Any {
        val input = JSONObject().put("action", action).put("profile_id", "local")
        fields.forEach { input.put(it.first, it.second) }
        val result = JSONObject(app.invoke(input.toString()))
        assertTrue("core action failed: $action", result.getBoolean("ok"))
        return result.get("data")
    }

    @Test fun nativeSqliteCrudRestartTrashSearchExportAndTaskToggle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.filesDir.resolve("binding-test-${UUID.randomUUID()}")
        try {
            val app = MemoApp(root.absolutePath, MemoryCredentials(), true)
            val text = "# 中文😀\n\n- [ ] 待办  \n\n```\n- [ ] 代码\n```\n"
            val note = request(
                app,
                "create",
                "request_id" to UUID.randomUUID().toString(),
                "content" to text
            ) as JSONObject
            val id = note.getString("id")
            val toggled = request(
                app,
                "toggle_task",
                "id" to id,
                "expected_generation" to note.getString("generation"),
                "line" to 2
            ) as JSONObject
            assertEquals(text.replace("- [ ] 待办", "- [x] 待办"), toggled.getString("content"))
            val results = request(app, "list", "query" to "中文") as org.json.JSONArray
            assertEquals(1, results.length())
            app.destroy()
            val reopened = MemoApp(root.absolutePath, MemoryCredentials(), true)
            val persisted = request(reopened, "get", "id" to id) as JSONObject
            assertEquals(toggled.getString("content"), persisted.getString("content"))
            val deleted = request(
                reopened,
                "set_deleted",
                "id" to id,
                "expected_generation" to persisted.getString("generation"),
                "deleted" to true
            ) as JSONObject
            assertEquals(0, (request(reopened, "list") as org.json.JSONArray).length())
            assertEquals(1, (request(reopened, "list", "deleted" to true) as org.json.JSONArray).length())
            val restored = request(
                reopened,
                "set_deleted",
                "id" to id,
                "expected_generation" to deleted.getString("generation"),
                "deleted" to false
            ) as JSONObject
            assertFalse(restored.getBoolean("deleted"))
            val exported = request(reopened, "export") as JSONObject
            val bytes = android.util.Base64.decode(exported.getString("bytes_base64"), android.util.Base64.DEFAULT)
            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                val files = mutableListOf<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    files += entry.name
                }
                assertTrue(files.contains("$id.md"))
                assertTrue(files.contains("manifest.json"))
            }
            reopened.destroy()
        } finally { root.deleteRecursively() }
    }

    @Test fun keystoreEncryptsCredentialsAcrossAdapterRestartAndRemovesThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = "test-${UUID.randomUUID()}"
        val credentials = KeystoreCredentials(context)
        try {
            assertTrue(credentials.save(profile, "instrumentation-fixture-token"))
            val raw = context.getSharedPreferences(
                "credential_ciphertext",
                Context.MODE_PRIVATE
            ).getString(profile, "")!!
            assertFalse(raw.contains("instrumentation-fixture-token"))
            assertEquals("instrumentation-fixture-token", KeystoreCredentials(context).load(profile))
        } finally { credentials.remove(profile) }
        assertNull(KeystoreCredentials(context).load(profile))
    }

    @Test fun platformCredentialFailuresDoNotCrossCallbacksOrPreventKeyRevocation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = "failure-${UUID.randomUUID()}"
        val normal = KeystoreCredentials(context)
        val prefs = context.getSharedPreferences("credential_ciphertext", Context.MODE_PRIVATE)
        val brokenContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                object : android.content.SharedPreferences by prefs {
                    override fun getString(
                        key: String?,
                        defValue: String?
                    ): String? = error("fixture unavailable")
                    override fun edit(): android.content.SharedPreferences.Editor = throw SecurityException(
                        "fixture unavailable"
                    )
                }
        }
        val broken = KeystoreCredentials(brokenContext)
        try {
            assertTrue(normal.save(profile, "fixture-value"))
            assertNull(broken.load(profile))
            assertFalse(broken.save(profile, "fixture-value"))
            broken.remove(profile)
            assertTrue(prefs.contains(profile))
            assertNull(normal.load(profile))
        } finally { normal.remove(profile) }
    }
}
