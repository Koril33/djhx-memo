package app.djhx.memo

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

internal data class MarkdownImportRequest(
    val profile: String,
    val id: String,
    val filename: String? = null,
    val bytes: ByteArray? = null
)

/** Owns picker identity and an uncertain persistence receipt across Activity recreation. */
internal class MarkdownImportSession(private val gateway: CoreBridge) {
    var pending: MarkdownImportRequest? = null
        private set
    fun begin(profile: String): String {
        val request = MarkdownImportRequest(profile, UUID.randomUUID().toString())
        pending = request
        return request.id
    }
    fun cancel(token: String? = null) {
        if (token == null || pending?.id == token) pending = null
    }
    fun accept(token: String, profile: String, filename: String?, bytes: ByteArray?): MarkdownImportRequest? {
        val request = pending?.takeIf { it.id == token } ?: return null
        if (request.profile != profile) {
            cancel(token)
            throw CoreFailure("PROFILE_CHANGED", "")
        }
        if (request.bytes == null && bytes != null) pending = request.copy(filename = filename, bytes = bytes)
        return pending?.takeIf { it.bytes != null }
    }
    suspend fun commit(request: MarkdownImportRequest): JSONObject {
        try {
            val note = gateway.obj(
                "import_markdown",
                "profile_id" to request.profile,
                "request_id" to request.id,
                "filename" to request.filename,
                "bytes_base64" to Base64.encodeToString(request.bytes, Base64.NO_WRAP)
            )
            cancel(request.id)
            return note
        } catch (error: CoreFailure) {
            if (error.code in setOf(
                    "IMPORT_INVALID_FILE", "IMPORT_INVALID_ENCODING", "IMPORT_EMPTY",
                    "NOTE_TOO_LARGE", "PROFILE_CHANGED", "RECOVERY_REQUIRED"
                )
            ) {
                cancel(request.id)
            }
            throw error
        }
    }
}
