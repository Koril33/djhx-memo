package app.djhx.memo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.djhx_memo_core.CredentialStore
import uniffi.djhx_memo_core.MemoApp
import java.util.UUID

/** 显式传入隔离测试服务器与单次邀请码才运行，不接触用户的真实账号。 */
@RunWith(AndroidJUnit4::class)
class ServerIntegrationTest {
    private class Credentials : CredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun load(profileId: String): String? = values[profileId]
        override fun save(profileId: String, token: String): Boolean {
            values[profileId] = token
            return true
        }
        override fun remove(profileId: String) { values.remove(profileId) }
    }

    private fun call(app: MemoApp, action: String, vararg fields: Pair<String, Any>): Any {
        val request = JSONObject().put("action", action)
        fields.forEach { (key, value) -> request.put(key, value) }
        val response = JSONObject(app.invoke(request.toString()))
        assertTrue(
            "bridge action failed: $action (${response.optJSONObject("error")?.optString("code")})",
            response.optBoolean("ok")
        )
        return response.get("data")
    }

    @Test fun invitationLoginExplicitImportTwoDeviceSyncAndRevocation() {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("serverUrl").orEmpty()
        val invite = args.getString("inviteCode").orEmpty()
        assumeTrue("requires isolated integration server", server.isNotBlank() && invite.isNotBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.filesDir.resolve("server-test-${UUID.randomUUID()}")
        val first = MemoApp(root.resolve("first").absolutePath, Credentials(), true)
        val second = MemoApp(root.resolve("second").absolutePath, Credentials(), true)
        try {
            call(first, "foreground", "active" to false)
            call(second, "foreground", "active" to false)
            call(
                first,
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to "# 安卓联调\n中文😀 离线草稿"
            )
            val username = "android_${UUID.randomUUID().toString().take(12)}"
            val password = UUID.randomUUID().toString() + "!Aa1"
            val registered = call(
                first,
                "register",
                "server_url" to server,
                "username" to username,
                "password" to password,
                "invite_code" to invite,
                "device_name" to "Android integration first"
            ) as JSONObject
            val profile = registered.getJSONObject("profile").getString("id")
            assertEquals(1, registered.getInt("import_count"))
            assertEquals(0, (call(first, "list", "profile_id" to profile) as JSONArray).length())
            assertEquals(
                1,
                (
                    call(
                        first,
                        "import",
                        "profile_id" to profile,
                        "source_profile_id" to "local"
                    ) as JSONObject
                    ).getInt("imported")
            )
            assertEquals(
                0,
                (
                    call(
                        first,
                        "import",
                        "profile_id" to profile,
                        "source_profile_id" to "local"
                    ) as JSONObject
                    ).getInt("imported")
            )
            val beforeSelfPull = (call(first, "list", "profile_id" to profile) as JSONArray).getJSONObject(0)
            val generationBeforeSelfPull = beforeSelfPull.getString("generation")
            val synced = call(first, "sync", "wait" to true) as JSONObject
            assertEquals("synced", synced.getString("state"))
            val afterSelfPull = call(
                first,
                "get",
                "profile_id" to profile,
                "id" to beforeSelfPull.getString("id")
            ) as JSONObject
            assertEquals(generationBeforeSelfPull, afterSelfPull.getString("generation"))
            call(
                first,
                "save",
                "profile_id" to profile,
                "id" to beforeSelfPull.getString("id"),
                "expected_generation" to generationBeforeSelfPull,
                "content" to beforeSelfPull.getString("content") + "\n自同步确认后的原代次续写"
            )
            call(first, "sync", "wait" to true)
            val loggedIn = call(
                second,
                "login",
                "server_url" to server,
                "username" to username,
                "password" to password,
                "device_name" to "Android integration second"
            ) as JSONObject
            val secondProfile = loggedIn.getJSONObject("profile").getString("id")
            val secondDevice = loggedIn.getJSONObject("profile").getString("device_id")
            call(second, "sync", "wait" to true)
            assertEquals(
                1,
                (call(second, "list", "profile_id" to secondProfile, "query" to "中文") as JSONArray).length()
            )
            val devices = call(first, "devices") as JSONArray
            assertEquals(2, devices.length())
            call(first, "revoke_device", "id" to secondDevice)
            val revokedSync = call(second, "sync", "wait" to true) as JSONObject
            assertEquals("auth_required", revokedSync.getString("state"))
            assertEquals(1, (call(second, "list", "profile_id" to secondProfile) as JSONArray).length())
            call(second, "logout")
            assertEquals("local", (call(second, "state") as JSONObject).getJSONObject("profile").getString("id"))
            call(first, "logout")
            assertEquals(1, (call(first, "list", "profile_id" to "local") as JSONArray).length())
        } finally {
            first.destroy()
            second.destroy()
            root.deleteRecursively()
        }
    }
}
