package app.djhx.memo

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Only the verified-update cache directory is exposed, with an exact URI read grant. */
class UpdateFileProvider : FileProvider()

internal data class StagedUpdate(val attempt: String, val intent: Intent)

/** A compatibility handoff uses the same verified candidate and never opens public storage. */
internal class UpdateCompatibility(private val context: Context) {
    private val journal = context.getSharedPreferences("updates-install", Context.MODE_PRIVATE)
    val target: Long get() = journal.getLong("external_code", 0)
    val attempt: String? get() = journal.getString("external_attempt", null)
    val pending: Boolean get() = target > 0 && attempt != null
    val callbacks: Set<String> get() = journal.getStringSet("external_callbacks", emptySet()).orEmpty().toSet()
    val needsCleanup: Boolean get() = journal.contains("cleanup_attempt")

    @Suppress("DEPRECATION") // Integer flags support Android 10.
    fun installedTarget(): Boolean = pending && context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode >= target

    /** Rechecks copied bytes before granting another process access to this one APK. */
    fun stage(source: File, release: JSONObject): StagedUpdate {
        val id = UUID.randomUUID().toString()
        val root = context.cacheDir.canonicalFile
        val directory = root.resolve("verified-update").apply { mkdirs() }.canonicalFile
        val partial = directory.resolve("$id.partial")
        val apk = directory.resolve("$id.apk")
        if (directory.parentFile != root || Files.isSymbolicLink(partial.toPath()) || Files.isSymbolicLink(apk.toPath())
        ) {
            updateFailure("UPDATE_STORAGE")
        }
        try {
            copyChecked(source, partial, release)
            Files.move(
                partial.toPath(),
                apk.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally { partial.delete() }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", apk)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .apply { clipData = ClipData.newRawUri("DJHX Memo update", uri) }
            return StagedUpdate(id, intent)
        } catch (error: IllegalArgumentException) {
            // No grant was launched; preserve the primary error even if cache cleanup fails.
            runCatching { apk.delete() }
            throw error
        } catch (error: SecurityException) {
            runCatching { apk.delete() }
            throw error
        }
    }

    private fun copyChecked(source: File, partial: File, release: JSONObject) {
        val digest = MessageDigest.getInstance("SHA-256")
        val size = release.getLong("size")
        var total = 0L
        FileOutputStream(partial).use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var count = input.read(buffer)
                while (count >= 0) {
                    total += count
                    requireSize(total <= size)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    count = input.read(buffer)
                }
            }
            output.fd.sync()
        }
        requireSize(total == size)
        if (digest.digest().joinToString("") { "%02x".format(it) } != release.getString("sha256")) {
            updateFailure(
                "UPDATE_PACKAGE"
            )
        }
    }

    private fun requireSize(valid: Boolean) { if (!valid) updateFailure("UPDATE_PACKAGE") }

    /** Must be durable before launching the system UI or accepting its result. */
    fun begin(staged: StagedUpdate, release: JSONObject) {
        if (pending || needsCleanup) updateFailure("UPDATE_BUSY")
        if (!journal.edit().putLong("external_code", release.getJSONObject("android").getLong("version_code"))
                .putString("external_attempt", staged.attempt)
                .putStringSet("external_callbacks", callbacks + staged.attempt)
                .putString(
                    "external_uri",
                    checkNotNull(staged.intent.data).toString()
                ).commit()
        ) {
            updateFailure("UPDATE_STORAGE")
        }
    }

    fun acknowledge(id: String) { journal.edit().putStringSet("external_callbacks", callbacks - id).commit() }

    fun clear(expectedAttempt: String?): Boolean {
        if (expectedAttempt != attempt) return true
        val uri = journal.getString("external_uri", null)
        val edit = journal.edit().remove("external_code").remove("external_uri").remove("external_attempt")
            .putStringSet("external_callbacks", callbacks - expectedAttempt)
        if (uri != null && expectedAttempt != null) {
            edit.putString("cleanup_uri", uri).putString("cleanup_attempt", expectedAttempt)
        }
        if (!edit.commit()) return false
        return retryCleanup()
    }

    /** A durable cleanup ticket allows retry after I/O or OEM permission-service failures. */
    fun retryCleanup(): Boolean {
        val id = journal.getString("cleanup_attempt", null) ?: return true
        val uri = journal.getString("cleanup_uri", null) ?: return false
        if (!discard(StagedUpdate(id, Intent().setData(Uri.parse(uri))))) return false
        return journal.edit().remove("cleanup_attempt").remove("cleanup_uri").commit()
    }

    fun deferCleanup(staged: StagedUpdate) {
        val previous = journal.getString("cleanup_attempt", null)
        if (previous == null || previous == staged.attempt) {
            journal.edit().putString("cleanup_attempt", staged.attempt)
                .putString("cleanup_uri", staged.intent.data?.toString()).commit()
        }
    }

    /** Revokes only this attempt's file, never a newer attempt or another private cache path. */
    @Suppress("TooGenericExceptionCaught") // Cleanup reports failure; it must never strand the save barrier.
    fun discard(staged: StagedUpdate): Boolean = try {
        val uri = staged.intent.data
        if (uri == null || uri.authority != "${context.packageName}.update-files" || uri.lastPathSegment != "${staged.attempt}.apk") {
            false
        } else {
            val root = context.cacheDir.canonicalFile
            val directory = root.resolve("verified-update").canonicalFile
            val file = directory.resolve("${staged.attempt}.apk")
            if (directory.parentFile != root || Files.isSymbolicLink(file.toPath())) {
                false
            } else {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                !file.exists() || file.delete()
            }
        }
    } catch (_: Exception) { false }
}

/** System reasons are stable categories; raw status messages may contain private paths. */
internal fun installFailureCode(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "UPDATE_INSTALL_CANCELED"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "UPDATE_INSTALL_BLOCKED"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "UPDATE_INSTALL_CONFLICT"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "UPDATE_INSTALL_INCOMPATIBLE"
    PackageInstaller.STATUS_FAILURE_INVALID -> "UPDATE_INSTALL_INVALID"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "UPDATE_INSTALL_STORAGE"
    PackageInstaller.STATUS_FAILURE_TIMEOUT -> "UPDATE_INSTALL_TIMEOUT"
    else -> "UPDATE_INSTALL"
}

internal val persistentInstallErrors = setOf(
    "UPDATE_INSTALL", "UPDATE_INSTALL_CANCELED", "UPDATE_INSTALL_BLOCKED", "UPDATE_INSTALL_CONFLICT",
    "UPDATE_INSTALL_INCOMPATIBLE", "UPDATE_INSTALL_INVALID", "UPDATE_INSTALL_STORAGE", "UPDATE_INSTALL_TIMEOUT",
    "UPDATE_INSTALL_BACKGROUND", "UPDATE_INSTALL_UNAVAILABLE", "UPDATE_INSTALL_UNKNOWN", "UPDATE_INSTALL_WAIT_CANCELED",
    "UPDATE_INSTALL_RECOVERY"
)
