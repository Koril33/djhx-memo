package app.djhx.memo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
class AppUpdates internal constructor(
    private val app: MemoApplication,
    private val compatibility: UpdateCompatibility,
    private val recovery: InstallRecovery? = null
) {
    constructor(app: MemoApplication) : this(app, UpdateCompatibility(app))
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
    private var confirmationIntent: Intent? = null
    private var activity = WeakReference<MainActivity>(null)
    var status by mutableStateOf(JSONObject())
        private set
    var installing by mutableStateOf(journal.getInt("session", -1) >= 0 || compatibility.pending)
        private set
    var installError by mutableStateOf(
        journal.getString("last_failure", null)?.takeIf { it in persistentInstallErrors }
    )
        private set
    var needsConfirmation by mutableStateOf(false)
        private set
    var preferencesPending by mutableStateOf(false)
        private set
    private var pendingCommands by mutableIntStateOf(0)
    val operationPending: Boolean get() = pendingCommands > 0
    private var automaticReady = false
    private var started = false
    private var submissionActive = false
    private var finishing = false
    var recoveryRequired by mutableStateOf(compatibility.needsCleanup)
        private set
    internal val systemInstallerCallbacks: Set<String> get() = compatibility.callbacks
    val waitingForSystemInstaller: Boolean get() = installing && compatibility.pending

    /** A journaled session remains cancelable even if its confirmation callback was lost. */
    val canCancelInstallation: Boolean get() = !submissionActive && !finishing &&
        (recoveryRequired || installing && (journal.getInt("session", -1) >= 0 || compatibility.pending))

    /** Cancels unfinished background downloads and checks when the foreground deadline is due. */
    fun foreground(screen: MainActivity?, active: Boolean) {
        activity = WeakReference(if (active) screen else null)
        if (!active) service.cancel()
        if (active) reconcileInstallation()
        if (started) {
            if (active && automaticReady && !installing) action("automatic")
            return
        }
        started = true
        scope.launch {
            refresh()
            reconcileInstallation()
            delay(30000 + (0..30000L).random())
            automaticReady = true
            while (true) {
                if (activity.get() != null && !installing) perform("automatic")
                delay(3600000)
            }
        }
    }

    /** Recovers a process death between journaling a session and committing it to Android. */
    internal fun reconcileInstallation() {
        if (!installing || submissionActive || finishing) return
        if (compatibility.pending) {
            if (compatibility.installedTarget()) finish(true)
            return
        }
        val id = journal.getInt("session", -1)
        val session = app.packageManager.packageInstaller.getSessionInfo(id)
        if (session == null || !session.isCommitted) {
            if (session != null) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
            finish(false)
        }
    }

    /** Copies the non-sensitive native snapshot without running network work on the main thread. */
    suspend fun refresh() { status = withContext(Dispatchers.IO) { JSONObject(service.snapshot()) } }

    /** Sends a fixed UI intent; paths, signing keys and URLs never come from this interface. */
    fun action(name: String) { scope.launch { perform(name) } }

    private suspend fun perform(name: String) {
        if (installing || recoveryRequired) return
        if (name != "cancel" && (operationPending || preferencesPending)) return
        if (name == "check" || name == "download") showInstallError(null)
        pendingCommands++
        try {
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
        } finally { pendingCommands-- }
    }

    /** Serializes preference edits so rapid controls cannot overwrite each other's fields. */
    fun preferences(autoCheck: Boolean, policy: String) {
        if (preferencesPending || installing || operationPending) return
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
    fun install(model: MemoModel) = startInstall(model, false)

    /** Explicit compatibility route; it retains verification, save and source-permission gates. */
    fun installWithSystemInstaller(model: MemoModel) = startInstall(model, true)

    private fun startInstall(model: MemoModel, systemInstaller: Boolean) {
        if (installing || recoveryRequired) return
        if (operationPending || preferencesPending) return
        val screen = activity.get() ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            try {
                screen.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                )
            } catch (_: ActivityNotFoundException) { showInstallError("UPDATE_INSTALL_UNAVAILABLE") }
            return
        }
        scope.launch {
            installing = true
            submissionActive = true
            showInstallError(null)
            var staged: StagedUpdate? = null
            try {
                if (recoveryRequired) updateFailure("UPDATE_INSTALL_RECOVERY")
                val prepared = withContext(Dispatchers.IO) { JSONObject(service.prepareInstall()) }
                if (!prepared.optBoolean("ok")) updateFailure(prepared.optString("error", "UPDATE_INSTALL"))
                val data = prepared.getJSONObject("data")
                val file = File(data.getString("path"))
                val release = data.getJSONObject("release")
                withContext(Dispatchers.IO) { UpdateInstaller(app).verify(file, release) }
                staged = if (systemInstaller) withContext(Dispatchers.IO) { compatibility.stage(file, release) } else null
                if (!model.prepareUpdate()) updateFailure("UPDATE_SAVE")
                val external = staged
                if (external != null) {
                    withContext(Dispatchers.IO) { compatibility.begin(external, release) }
                    // Resolve after the journal write: rotation/backgrounding may replace the Activity.
                    val active = activity.get() ?: updateFailure("UPDATE_INSTALL_BACKGROUND")
                    active.launchUpdateInstaller(external)
                } else {
                    withContext(Dispatchers.IO) { UpdateInstaller(app).submit(file) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                finish(false)
                throw error
            } catch (error: Exception) {
                showInstallError(
                    when (error) {
                        is UpdateFailure -> error.code
                        is SecurityException -> "UPDATE_INSTALL_BLOCKED"
                        is ActivityNotFoundException -> "UPDATE_INSTALL_UNAVAILABLE"
                        else -> "UPDATE_INSTALL"
                    }
                )
                finish(false)
            } finally {
                submissionActive = false
                staged?.takeIf { compatibility.attempt != it.attempt }?.let {
                    if (!compatibility.discard(it)) {
                        recoveryRequired = true
                        try {
                            compatibility.deferCleanup(
                                it
                            )
                        } catch (_: Exception) { /* Keep the visible recovery state. */ }
                        showInstallError("UPDATE_INSTALL_RECOVERY")
                    }
                }
            }
            refresh()
        }
    }

    /** Handles only the installation session recorded by this application. */
    @Suppress("DEPRECATION") // API 29 has no typed Parcelable overload.
    fun receive(intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != journal.getInt("session", -1)) return
        installing = true
        val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation == null) {
                showInstallError("UPDATE_INSTALL")
                finish(false)
                return
            }
            confirmationIntent = Intent(confirmation)
            journal.edit().remove("continuation").putBoolean("confirmation_pending", true).commit()
            needsConfirmation = true
            continueInstallation()
        } else {
            if (result != PackageInstaller.STATUS_SUCCESS) showInstallError(installFailureCode(result))
            finish(result == PackageInstaller.STATUS_SUCCESS)
        }
    }

    /** Reopens a system-supplied confirmation only when the Activity is available. */
    fun continueInstallation() {
        val screen = activity.get() ?: return
        try {
            val pending = confirmationIntent?.let(::Intent) ?: return
            screen.startActivity(pending)
        } catch (_: Exception) {
            showInstallError("UPDATE_INSTALL")
        }
    }

    /** Ends local waiting; only an owned PackageInstaller session can actually be abandoned. */
    fun cancelInstallation() {
        if (!canCancelInstallation) return
        val id = journal.getInt("session", -1)
        if (id >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
        showInstallError(if (compatibility.pending) "UPDATE_INSTALL_WAIT_CANCELED" else "UPDATE_INSTALL_CANCELED")
        finish(false)
    }

    /** Activity results alone are not success: the OS-installed package must reach the target. */
    fun systemInstallerReturned(attempt: String) {
        if (!compatibility.pending || compatibility.attempt != attempt || finishing) return
        try {
            compatibility.acknowledge(attempt)
            if (compatibility.installedTarget()) finish(true, attempt) else showInstallError("UPDATE_INSTALL_UNKNOWN")
        } catch (_: Exception) { showInstallError("UPDATE_INSTALL_UNKNOWN") }
    }

    private fun showInstallError(code: String?) {
        installError = code
        try {
            val edit = journal.edit()
            if (code in persistentInstallErrors) edit.putString("last_failure", code) else edit.remove("last_failure")
            if (!edit.commit()) {
                recoveryRequired = true
                installError = "UPDATE_INSTALL_RECOVERY"
            }
        } catch (_: Exception) {
            recoveryRequired = true
            installError = "UPDATE_INSTALL_RECOVERY"
        }
    }

    private fun finish(success: Boolean, expectedAttempt: String? = compatibility.attempt) {
        if (finishing || expectedAttempt != compatibility.attempt) return
        finishing = true
        if (success) showInstallError(null)
        confirmationIntent = null
        try {
            recoveryRequired = !compatibility.clear(expectedAttempt)
            if (!journal.edit().remove("session").remove("continuation").remove("confirmation_pending").commit()) {
                recoveryRequired = true
            }
        } catch (_: Exception) { recoveryRequired = true }
        if (recoveryRequired) showInstallError("UPDATE_INSTALL_RECOVERY")
        needsConfirmation = false
        scope.launch {
            // Failure reporting is best effort and must never prevent releasing the core gate.
            if (!success) {
                try {
                    if (recovery != null) {
                        recovery.markFailed()
                    } else {
                        withContext(
                            Dispatchers.IO
                        ) { service.installationFailed() }
                    }
                } catch (_: Exception) {
                    recoveryRequired = true
                    showInstallError("UPDATE_INSTALL_RECOVERY")
                }
            }
            try {
                if (recovery != null) recovery.resume() else app.gateway.resumeAfterUpdate()
                // onStart may have been blocked while the restored installation journal locked UI.
                var active: Boolean
                do {
                    active = activity.get() != null
                    app.gateway.call("foreground", "active" to active)
                } while (active != (activity.get() != null))
            } catch (_: CoreException) {
                recoveryRequired = true
                showInstallError("UPDATE_SAVE")
            } catch (_: CoreFailure) {
                recoveryRequired = true
                showInstallError("UPDATE_SAVE")
            } catch (_: Exception) {
                recoveryRequired = true
                showInstallError("UPDATE_SAVE")
            } finally {
                // A new attempt must not race a delayed release from this installation.
                installing = false
                finishing = false
                refresh()
            }
        }
    }
}

/** Separates the recoverable native bookkeeping and core-resume boundaries. */
internal interface InstallRecovery {
    suspend fun markFailed()
    suspend fun resume()
}
