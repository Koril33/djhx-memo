package app.djhx.memo

/** 仅按稳定错误码选择固定翻译，绝不显示服务器原始错误或用户凭据。 */
internal fun errorResource(code: String?): Int = when (code) {
    "AUTH_INVALID_CREDENTIALS" -> R.string.error_auth_invalid
    "AUTH_REQUIRED" -> R.string.error_auth_required
    "INVITE_INVALID" -> R.string.error_invite
    "USERNAME_TAKEN" -> R.string.error_username_taken
    "RATE_LIMITED" -> R.string.error_rate_limited
    "DEVICE_ID_UNAVAILABLE" -> R.string.error_device_id
    "SYNC_EPOCH_MISMATCH", "RECOVERY_REQUIRED" -> R.string.error_recovery
    "CURSOR_INVALID" -> R.string.error_cursor
    "PROTOCOL_UNSUPPORTED" -> R.string.error_protocol
    "INVALID_REQUEST" -> R.string.error_request
    "NETWORK_UNAVAILABLE" -> R.string.error_network
    "LOCAL_STORAGE_FAILED" -> R.string.error_storage
    "LOCAL_SCHEMA_TOO_NEW" -> R.string.error_schema
    "NOT_FOUND" -> R.string.error_not_found
    "LOCAL_GENERATION_CONFLICT" -> R.string.error_generation
    "SESSION_CHANGED", "PROFILE_CHANGED" -> R.string.error_session
    "NOTE_TOO_LARGE" -> R.string.error_note_large
    "SERVER_BUSY" -> R.string.error_server
    "EXPORT_FAILED", "EXPORT_WRITE_FAILED" -> R.string.error_export
    else -> R.string.operation_failed
}
