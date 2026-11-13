package app.djhx.memo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Prechecks identity; Android verifies the native APK signature when committing the session. */
internal class UpdateInstaller(private val context: Context) {
    /** Rejects changed signer/package/ABI/SDK or non-increasing versions before creating a session. */
    @Suppress("DEPRECATION") // Flags overload supports the API 29 baseline.
    fun verify(file: File, release: JSONObject) {
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: updateFailure("UPDATE_PACKAGE")
        val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val android = release.getJSONObject("android")
        val signer = apk.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
            ?: updateFailure("UPDATE_SIGNATURE")
        val installedSigner = current.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
            ?: updateFailure("UPDATE_SIGNATURE")
        if (!signer.contentEquals(installedSigner) || sha256(signer) != android.getString("certificate_sha256")) {
            updateFailure("UPDATE_SIGNATURE")
        }
        val minSdk = apk.applicationInfo?.minSdkVersion ?: updateFailure("UPDATE_PACKAGE")
        val packageMatches = apk.packageName == context.packageName && apk.packageName == android.getString("application_id")
        val versionMatches = apk.longVersionCode == android.getLong("version_code") && apk.longVersionCode > current.longVersionCode
        val systemMatches = minSdk == android.getInt("min_sdk") && minSdk <= Build.VERSION.SDK_INT
        val identityMatches = packageMatches && versionMatches
        if (!identityMatches || !systemMatches || apk.versionName != release.getString("version")) {
            updateFailure("UPDATE_INCOMPATIBLE")
        }
        val abis = android.getJSONArray("abis").let { a -> (0 until a.length()).map { a.getString(it) } }
        ZipFile(file).use { zip ->
            if (Build.SUPPORTED_ABIS.none { it in abis } || abis.any { zip.getEntry("lib/$it/libdjhx_memo_core.so") == null }) {
                updateFailure("UPDATE_INCOMPATIBLE")
            }
        }
    }

    private fun writePackage(session: PackageInstaller.Session, file: File) {
        session.openWrite("base.apk", 0, file.length()).use { output ->
            file.inputStream().use { it.copyTo(output) }
            session.fsync(output)
        }
    }

    /** Streams a verified APK into the system and journals its callback identity before committing. */
    fun submit(file: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        val callback = Intent(
            context,
            UpdateInstallReceiver::class.java
        ).setAction("${context.packageName}.UPDATE_STATUS")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, id, callback, flags)
        var submitted = false
        try {
            installer.openSession(id).use { session ->
                writePackage(session, file)
                val saved = context.getSharedPreferences("updates-install", Context.MODE_PRIVATE).edit()
                    .putInt("session", id).remove("continuation").commit()
                if (!saved) updateFailure("UPDATE_STORAGE")
                session.commit(pending.intentSender)
                submitted = true
            }
        } finally {
            if (!submitted) installer.abandonSession(id)
        }
        return id
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

/** Not exported; receives only the explicit system installation PendingIntent. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as MemoApplication).updates.receive(intent)
    }
}

/** Fixed codes keep transport/installer details out of UI messages and logs. */
internal class UpdateFailure(val code: String) : Exception(code)

internal fun updateFailure(code: String): Nothing = throw UpdateFailure(code)
