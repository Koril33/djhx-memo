package app.djhx.memo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.djhx_memo_core.CoreException
import uniffi.djhx_memo_core.MemoApp

/** 平台后台调用契约；测试可以验证 UI 的并发协调而不重写同步规则。 */
interface CoreBridge {
    suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any
    suspend fun obj(action: String, vararg fields: Pair<String, Any?>): JSONObject = call(action, *fields) as JSONObject
    suspend fun array(action: String, vararg fields: Pair<String, Any?>): JSONArray = call(action, *fields) as JSONArray
}

/** 单进程 core 入口。所有数据库与网络业务都在 Rust 中执行，调用移到 IO 线程。 */
class CoreGateway(context: Context) : CoreBridge {
    /** Native installation barrier after the UI has saved its latest buffer. */
    suspend fun pauseForUpdate() = withContext(Dispatchers.IO) {
        try { core.pauseForUpdate() } catch (_: CoreException) { updateFailure("UPDATE_SAVE") }
    }

    /** Resumes notes and background work after a canceled/failed update. */
    suspend fun resumeAfterUpdate() = withContext(Dispatchers.IO) { core.resumeAfterUpdate() }
    private val appContext = context.applicationContext
    private val core by lazy {
        MemoApp(
            appContext.filesDir.resolve("memo").absolutePath,
            KeystoreCredentials(appContext),
            BuildConfig.DEBUG,
        ).also { app ->
            // 后台首次初始化不能默认持有 SSE；Activity.onStart 再显式激活前台。
            app.invoke("{\"action\":\"foreground\",\"active\":false}")
        }
    }

    override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any = withContext(Dispatchers.IO) {
        val request = JSONObject().put("action", action)
        fields.forEach { (key, value) -> request.put(key, value ?: JSONObject.NULL) }
        val result = JSONObject(core.invoke(request.toString()))
        if (!result.optBoolean("ok")) {
            val error = result.optJSONObject("error")
            throw CoreFailure(
                error?.optString("code") ?: "INTERNAL",
                error?.optString("message") ?: "操作失败",
                error?.optBoolean("retryable") == true
            )
        }
        result.opt("data") ?: JSONObject.NULL
    }
}

/** core 保证消息经过脱敏；平台错误仅使用固定提示，禁止记录正文或凭据。 */
class CoreFailure(val code: String, override val message: String, val retryable: Boolean = false) : Exception(message)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.nullableString(key: String): String? = if (isNull(
        key
    )
) {
    null
} else {
    optString(key).ifEmpty { null }
}
