package app.djhx.memo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.djhx_memo_core.CoreException
import uniffi.djhx_memo_core.UpdateService
import java.io.File
import java.lang.ref.WeakReference

/** Per-device updates; Rust owns catalog trust and bounded downloads. */
@Suppress("TooGenericExceptionCaught") // UI boundary only exposes fixed error codes.
class AppUpdates(private val app: MemoApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val service by lazy {
        UpdateService(
            app.filesDir.resolve("updates").absolutePath,
            "android-universal-apk",
            BuildConfig.VERSION_NAME.substringBefore('-'),
            BuildConfig.VERSION_CODE.toULong(),
            app.packageName
        )
    }
    private val journal = app.getSharedPreferences("updates-install", Context.MODE_PRIVATE)
    private var activity = WeakReference<MainActivity>(null)
    var status by mutableStateOf(JSONObject())
        private set
    var installing by mutableStateOf(journal.getInt("session", -1) >= 0)
        private set
    var installError by mutableStateOf<String?>(null)
        private set
    var needsConfirmation by mutableStateOf(journal.contains("continuation"))
        private set
    var preferencesPending by mutableStateOf(false)
        private set
    private var automaticReady = false
    private var started = false

    /** Cancels unfinished background downloads and checks when the foreground deadline is due. */
    fun foreground(screen: MainActivity?, active: Boolean) {
        activity = WeakReference(if (active) screen else null)
        if (!active) service.cancel()
        if (started) {
            if (active && automaticReady && !installing) action("automatic")
            return
        }
        started = true
        scope.launch {
            refresh()
            if (installing && app.packageManager.packageInstaller.getSessionInfo(journal.getInt("session", -1)) == null) {
                finish(
                    false
                )
            }
            delay(30000 + (0..30000L).random())
            automaticReady = true
            while (true) {
                if (activity.get() != null && !installing) perform("automatic")
                delay(3600000)
            }
        }
    }

    /** Copies the non-sensitive native snapshot without running network work on the main thread. */
    suspend fun refresh() { status = withContext(Dispatchers.IO) { JSONObject(service.snapshot()) } }

    /** Sends a fixed UI intent; paths, signing keys and URLs never come from this interface. */
    fun action(name: String) { scope.launch { perform(name) } }

    private suspend fun perform(name: String) {
        if (installing) return
        status = withContext(Dispatchers.IO) {
            JSONObject(
                when (name) {
                    "check" -> service.check(true)
                    "automatic" -> {
                        service.check(false)
                        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        service.download(false, !cm.isActiveNetworkMetered)
                    }
                    "download" -> service.download(true, false)
                    "defer" -> service.defer()
                    "cancel" -> {
                        service.cancel()
                        service.snapshot()
                    }
                    else -> service.snapshot()
                }
            )
        }
    }

    /** Serializes preference edits so rapid controls cannot overwrite each other's fields. */
    fun preferences(autoCheck: Boolean, policy: String) {
        if (preferencesPending || installing) return
        preferencesPending = true
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { JSONObject(service.preferences(autoCheck, policy)) }
            } finally {
                preferencesPending = false
            }
        }
    }

    /** Revalidates the candidate, asks for source permission, and saves before a native handoff. */
    fun install(model: MemoModel) {
        if (installing) return
        val screen = activity.get() ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            screen.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            )
            return
        }
        scope.launch {
            installing = true
            installError = null
            try {
                val prepared = withContext(Dispatchers.IO) { JSONObject(service.prepareInstall()) }
                if (!prepared.optBoolean("ok")) updateFailure(prepared.optString("error", "UPDATE_INSTALL"))
                val data = prepared.getJSONObject("data")
                val file = File(data.getString("path"))
                withContext(Dispatchers.IO) { UpdateInstaller(app).verify(file, data.getJSONObject("release")) }
                if (!model.prepareUpdate()) updateFailure("UPDATE_SAVE")
                withContext(Dispatchers.IO) { UpdateInstaller(app).submit(file) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                finish(false)
                throw error
            } catch (error: Exception) {
                installError = (error as? UpdateFailure)?.code ?: "UPDATE_INSTALL"
                finish(false)
            }
            refresh()
        }
    }

    /** Handles only the installation session recorded by this application. */
    @Suppress("DEPRECATION") // API 29 has no typed Parcelable overload.
    fun receive(intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != journal.getInt("session", -1)) return
        val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation == null) {
                installError = "UPDATE_INSTALL"
                finish(false)
                return
            }
            journal.edit().putString("continuation", confirmation.toUri(Intent.URI_INTENT_SCHEME)).commit()
            needsConfirmation = true
            continueInstallation()
        } else {
            finish(result == PackageInstaller.STATUS_SUCCESS)
        }
    }

    /** Reopens a system-supplied confirmation only when the Activity is available. */
    fun continueInstallation() {
        val screen = activity.get() ?: return
        val saved = journal.getString("continuation", null) ?: return
        try {
            screen.startActivity(Intent.parseUri(saved, Intent.URI_INTENT_SCHEME))
        } catch (_: Exception) {
            installError = "UPDATE_INSTALL"
        }
    }

    /** Abandons the pending system session and releases the core installation barrier. */
    fun cancelInstallation() {
        if (!needsConfirmation) return
        val id = journal.getInt("session", -1)
        if (id >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
        finish(false)
    }

    private fun finish(success: Boolean) {
        journal.edit().remove("session").remove("continuation").commit()
        needsConfirmation = false
        scope.launch {
            try {
                if (!success) withContext(Dispatchers.IO) { service.installationFailed() }
                app.gateway.resumeAfterUpdate()
            } catch (_: CoreException) {
                installError = "UPDATE_SAVE"
            } finally {
                // A new attempt must not race a delayed release from this installation.
                installing = false
                refresh()
            }
        }
    }
}
