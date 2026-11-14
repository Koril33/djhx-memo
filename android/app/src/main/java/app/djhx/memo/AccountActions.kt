package app.djhx.memo

import org.json.JSONObject

/** UI account actions retain the editor barrier; the core owns credentials and account isolation. */
internal class AccountActions(
    private val gateway: CoreBridge,
    private val flush: suspend () -> Boolean,
    private val refresh: suspend () -> Unit,
    private val clearSelection: () -> Unit,
    private val message: (UiText) -> Unit,
    private val state: () -> JSONObject,
    private val profileId: () -> String
) {
    suspend fun authenticate(
        register: Boolean,
        server: String,
        username: String,
        password: String,
        invite: String,
        device: String,
        onSuccess: () -> Unit
    ) {
        if (!flush()) return
        val result = gateway.obj(
            if (register) "register" else "login",
            "server_url" to server,
            "username" to username,
            "password" to password,
            "invite_code" to invite,
            "device_name" to device
        )
        clearSelection()
        refresh()
        val target = result.optJSONObject("profile")?.optString("id").orEmpty()
        val current = state()
        if (target.isEmpty() || current.optJSONObject("profile")?.optString("id") != target ||
            current.optJSONObject("sync")?.optString("state") == "auth_required"
        ) {
            return
        }
        onSuccess()
        message(
            UiText(if (result.optInt("import_count") > 0) R.string.login_import_available else R.string.login_success)
        )
    }

    suspend fun importLocal(source: String) {
        if (!flush()) return
        val result = gateway.obj("import", "profile_id" to profileId(), "source_profile_id" to source)
        message(UiText(R.plurals.import_success, listOf(result.optInt("imported")), result.optInt("imported")))
        refresh()
    }

    suspend fun logout() {
        if (!flush()) return
        val result = gateway.obj("logout")
        clearSelection()
        refresh()
        message(
            UiText(if (result.optBoolean("remote_revoked")) R.string.logout_success else R.string.logout_remote_pending)
        )
    }
}
