package app.djhx.memo

/**
 * 从账号 `server_url` 提取可展示的来源主机：丢弃 userinfo/query/fragment，
 * 只保留安全主机、必要端口与区分实例所需的基础路径。无法解析时返回 null，
 * 由调用方使用通用账号降级文案，不使用凭据或文件路径填充。
 */
internal fun serverHostDisplay(serverUrl: String?): String? {
    if (serverUrl.isNullOrBlank()) return null
    return try {
        val uri = java.net.URI(serverUrl)
        val host = uri.host ?: return null
        val port = uri.port
        val path = uri.path.orEmpty().trim('/')
        buildString {
            append(host)
            if (port >= 0) append(':').append(port)
            if (path.isNotEmpty()) append('/').append(path)
        }
    } catch (_: Exception) {
        null
    }
}

/** 账号来源标识：优先用户名，缺失时回退到 profile.label。 */
internal fun accountSourceIdentity(username: String?, label: String?): String? =
    username?.takeIf { it.isNotBlank() } ?: label?.takeIf { it.isNotBlank() }
