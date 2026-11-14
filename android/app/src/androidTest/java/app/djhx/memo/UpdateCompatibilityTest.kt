package app.djhx.memo

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Platform handoff fixtures never use a production account, signing key or note database reset. */
@RunWith(AndroidJUnit4::class)
class UpdateCompatibilityTest {
    private val app = ApplicationProvider.getApplicationContext<MemoApplication>()
    private val journal = app.getSharedPreferences("updates-install", Context.MODE_PRIVATE)
    private val compatibility = UpdateCompatibility(app)
    private val staged = mutableListOf<StagedUpdate>()
    private val sources = mutableListOf<File>()
    private val sessions = mutableListOf<Int>()
    private var controller: AppUpdates? = null
    private var before: Map<String, *> = emptyMap<String, Any>()

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue("Installer fixture did not settle", condition())
    }

    @Before fun saveMetadata() {
        before = journal.all.toMap()
        // Only this isolated test app's updater metadata is reset; it is restored below.
        assertTrue(journal.edit().clear().commit())
    }

    @After fun restoreMetadata() = runBlocking {
        main { controller?.cancelInstallation() }
        waitFor { controller?.installing != true }
        app.gateway.resumeAfterUpdate()
        staged.forEach { compatibility.discard(it) }
        sources.forEach { it.delete() }
        sessions.forEach { runCatching { app.packageManager.packageInstaller.abandonSession(it) } }
        val edit = journal.edit().clear()
        before.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(edit.commit())
    }

    private fun source(): File = app.cacheDir.resolve("compat-source-${UUID.randomUUID()}").apply {
        writeBytes("Synthetic package transfer fixture".toByteArray())
        sources += this
    }

    private fun release(file: File) = JSONObject().put("size", file.length()).put("sha256", sha256(file.readBytes()))
        .put("android", JSONObject().put("version_code", BuildConfig.VERSION_CODE + 1))

    private fun stage(file: File): StagedUpdate = compatibility.stage(file, release(file)).also { staged += it }

    @Test fun uriExposesOnlyTheExactPrivateApkAndPreservesReadOnlyGrant() {
        val file = source()
        val ticket = stage(file)
        val intent = ticket.intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${app.packageName}.update-files", intent.data?.authority)
        assertEquals(intent.data, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertArrayEquals(file.readBytes(), app.contentResolver.openInputStream(intent.data!!)!!.use { it.readBytes() })
        @Suppress("DEPRECATION")
        val info = app.packageManager.getProviderInfo(ComponentName(app, UpdateFileProvider::class.java), 0)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        try {
            FileProvider.getUriForFile(app, "${app.packageName}.update-files", app.filesDir.resolve("not-exposed.txt"))
            fail("Provider must not expose notes or other private files")
        } catch (_: IllegalArgumentException) { /* Expected narrow provider root. */ }
    }

    @Test fun corruptOrTruncatedCopyNeverProducesAnInstallableUri() {
        val file = source()
        val cases = listOf(
            release(file).put("sha256", "0".repeat(64)),
            release(file).put("size", file.length() - 1),
            release(file).put("size", file.length() + 1)
        )
        val directory = app.cacheDir.resolve("verified-update")
        val original = directory.list()?.toSet().orEmpty()
        cases.forEach { metadata ->
            val error = runCatching { compatibility.stage(file, metadata) }.exceptionOrNull()
            assertEquals("UPDATE_PACKAGE", (error as UpdateFailure).code)
            assertEquals(original, directory.list()?.toSet().orEmpty())
        }
    }

    @Test fun legitimateSystemCacheAliasIsAccepted() {
        val alias = app.cacheDir.resolve("compat-cache-alias-${UUID.randomUUID()}")
        android.system.Os.symlink(app.cacheDir.absolutePath, alias.absolutePath)
        try {
            val context = object : ContextWrapper(app) { override fun getCacheDir(): File = alias }
            val file = source()
            val ticket = UpdateCompatibility(context).stage(file, release(file)).also { staged += it }
            assertArrayEquals(
                file.readBytes(),
                app.contentResolver.openInputStream(ticket.intent.data!!)!!.use { it.readBytes() }
            )
        } finally { alias.delete() }
    }

    @Test fun providerCreationFailureDoesNotLeaveAnOrphanApk() {
        val directory = app.cacheDir.resolve("verified-update")
        val files = directory.list()?.toSet().orEmpty()
        val context = object : ContextWrapper(
            app
        ) { override fun getPackageName(): String = "${app.packageName}.missing" }
        val file = source()
        assertNotNull(runCatching { UpdateCompatibility(context).stage(file, release(file)) }.exceptionOrNull())
        assertEquals(files, directory.list()?.toSet().orEmpty())
    }

    @Test fun staleResultCannotReleaseNewAttemptAndUnknownResultRetainsTheBarrier() = runBlocking {
        val file = source()
        val first = stage(file)
        compatibility.begin(first, release(file))
        compatibility.clear(first.attempt)
        val second = stage(file)
        assertNotEquals(first.intent.data, second.intent.data)
        compatibility.begin(second, release(file))
        val note = app.gateway.obj(
            "create",
            "profile_id" to "local",
            "request_id" to UUID.randomUUID().toString(),
            "content" to "兼容安装合成笔记😀"
        )
        app.gateway.pauseForUpdate()
        lateinit var updates: AppUpdates
        main {
            updates = AppUpdates(app)
            controller = updates
            updates.systemInstallerReturned(first.attempt)
        }
        assertTrue(updates.installing)
        assertEquals(second.attempt, compatibility.attempt)
        val blocked = runCatching { app.gateway.obj("state") }.exceptionOrNull()
        assertEquals("UPDATE_BUSY", (blocked as CoreFailure).code)
        main { updates.systemInstallerReturned(second.attempt) }
        assertEquals("UPDATE_INSTALL_UNKNOWN", updates.installError)
        assertTrue(updates.installing)
        assertEquals(second.attempt, compatibility.attempt)
        assertNotNull(app.contentResolver.openInputStream(second.intent.data!!)?.use { it.readBytes() })
        main { updates.cancelInstallation() }
        waitFor { !updates.installing }
        assertFalse(compatibility.pending)
        val saved = app.gateway.obj("get", "profile_id" to "local", "id" to note.getString("id"))
        assertEquals("兼容安装合成笔记😀", saved.getString("content"))
        app.gateway.obj(
            "set_deleted",
            "profile_id" to "local",
            "id" to saved.getString("id"),
            "expected_generation" to saved.getString("generation"),
            "deleted" to true
        )
        Unit
    }

    private fun session(): Int = app.packageManager.packageInstaller.createSession(
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    ).also {
        sessions += it
        assertTrue(journal.edit().putInt("session", it).commit())
    }

    @Test fun systemFailureSurvivesControllerRecreationWithoutPersistingRawMessage() {
        val id = session()
        lateinit var updates: AppUpdates
        main {
            updates = AppUpdates(app)
            controller = updates
            updates.receive(
                Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                    .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_BLOCKED)
                    .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "raw-fixture-path-not-for-storage")
            )
        }
        waitFor { !updates.installing }
        assertEquals("UPDATE_INSTALL_BLOCKED", updates.installError)
        assertEquals("UPDATE_INSTALL_BLOCKED", AppUpdates(app).installError)
        assertFalse(journal.all.values.any { it.toString().contains("raw-fixture-path-not-for-storage") })
    }

    @Test fun completeConfirmationIntentKeepsParcelableAndRestartDoesNotParseLossyUri() {
        val id = session()
        val parcelable = Uri.parse("content://fixture/complete-intent")
        val original = Intent(Intent.ACTION_VIEW).putExtra("parcelable", parcelable)
        lateinit var updates: AppUpdates
        main {
            updates = AppUpdates(app)
            controller = updates
            updates.receive(
                Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                    .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                    .putExtra(Intent.EXTRA_INTENT, original)
            )
        }
        val field = AppUpdates::class.java.getDeclaredField("confirmationIntent").apply { isAccessible = true }

        @Suppress("DEPRECATION")
        val saved = (field.get(updates) as Intent).getParcelableExtra<Uri>("parcelable")
        assertEquals(parcelable, saved)
        assertTrue(updates.needsConfirmation)
        assertFalse(AppUpdates(app).needsConfirmation)
    }

    private class FaultContext(base: Context) : ContextWrapper(base) {
        var failRevoke = false
        var failCommit = false
        override fun revokeUriPermission(uri: Uri, flags: Int) {
            if (failRevoke) throw SecurityException("fixture cleanup denial")
            super.revokeUriPermission(uri, flags)
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val original = prefs.edit()
                    return java.lang.reflect.Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)
                    ) { proxy, method, args ->
                        if (method.name == "commit" && failCommit) {
                            false
                        } else {
                            val result = method.invoke(original, *(args ?: emptyArray()))
                            if (result is SharedPreferences.Editor) proxy else result
                        }
                    } as SharedPreferences.Editor
                }
            }
        }
    }

    private fun cleanupFailureCanResumeAndRetry(failCommit: Boolean) = runBlocking {
        val file = source()
        val ticket = stage(file)
        compatibility.begin(ticket, release(file))
        app.gateway.pauseForUpdate()
        val faulty = FaultContext(app).apply {
            this.failCommit = failCommit
            failRevoke = !failCommit
        }
        lateinit var updates: AppUpdates
        main {
            updates = AppUpdates(app, UpdateCompatibility(faulty))
            controller = updates
            updates.cancelInstallation()
        }
        waitFor { !updates.installing }
        assertTrue(updates.recoveryRequired)
        assertEquals("UPDATE_INSTALL_RECOVERY", updates.installError)
        assertNotNull(app.gateway.obj("state"))
        main {
            faulty.failCommit = false
            faulty.failRevoke = false
            updates.cancelInstallation()
        }
        waitFor { !updates.installing && !updates.recoveryRequired }
        assertFalse(compatibility.pending)
        assertFalse(compatibility.needsCleanup)
    }

    @Test fun revokeFailureDoesNotStrandSavingAndRetainsRetryTicket() = cleanupFailureCanResumeAndRetry(false)

    @Test fun journalFailureDoesNotStrandSavingAndRetainsRetryState() = cleanupFailureCanResumeAndRetry(true)

    private fun nativeRecoveryFailureCanRetry(markFailure: Boolean) = runBlocking {
        val file = source()
        val ticket = stage(file)
        compatibility.begin(ticket, release(file))
        val note = app.gateway.obj(
            "create",
            "profile_id" to "local",
            "request_id" to UUID.randomUUID().toString(),
            "content" to "安装恢复边界合成笔记😀"
        )
        app.gateway.pauseForUpdate()
        var failing = true
        var resumeCalls = 0
        val recovery = object : InstallRecovery {
            override suspend fun markFailed() {
                if (markFailure && failing) error("fixture native failure-state write")
            }
            override suspend fun resume() {
                resumeCalls++
                if (!markFailure && failing) error("fixture core resume failure")
                app.gateway.resumeAfterUpdate()
            }
        }
        lateinit var updates: AppUpdates
        main {
            updates = AppUpdates(app, compatibility, recovery)
            controller = updates
            updates.cancelInstallation()
        }
        waitFor { !updates.installing }
        assertEquals(1, resumeCalls)
        assertTrue(updates.recoveryRequired)
        assertTrue(updates.canCancelInstallation)
        assertEquals(if (markFailure) "UPDATE_INSTALL_RECOVERY" else "UPDATE_SAVE", updates.installError)
        if (markFailure) {
            assertNotNull(app.gateway.obj("state"))
        } else {
            val blocked = runCatching { app.gateway.obj("state") }.exceptionOrNull()
            assertEquals("UPDATE_BUSY", (blocked as CoreFailure).code)
        }
        main {
            failing = false
            updates.cancelInstallation()
        }
        waitFor { !updates.installing && !updates.recoveryRequired }
        assertEquals(2, resumeCalls)
        val saved = app.gateway.obj("get", "profile_id" to "local", "id" to note.getString("id"))
        assertEquals("安装恢复边界合成笔记😀", saved.getString("content"))
        app.gateway.obj(
            "set_deleted",
            "profile_id" to "local",
            "id" to saved.getString("id"),
            "expected_generation" to saved.getString("generation"),
            "deleted" to true
        )
        Unit
    }

    @Test fun failureStateWriteCannotSkipCoreResume() = nativeRecoveryFailureCanRetry(true)

    @Test fun failedCoreResumeKeepsAnExplicitRetryAndDoesNotClaimEditingResumed() = nativeRecoveryFailureCanRetry(false)

    @Test fun stoppedActivityCannotLaunchTheStagedInstaller() = runBlocking {
        val file = source()
        val ticket = stage(file)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var oldActivity: MainActivity
            scenario.onActivity { oldActivity = it }
            val note = app.gateway.obj(
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to "退后台安装交接合成笔记😀"
            )
            app.gateway.pauseForUpdate()
            compatibility.begin(ticket, release(file))
            main { controller = AppUpdates(app) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            main {
                val rejected = runCatching { oldActivity.launchUpdateInstaller(ticket) }.exceptionOrNull()
                assertEquals("UPDATE_INSTALL_BACKGROUND", (rejected as UpdateFailure).code)
                controller!!.cancelInstallation()
            }
            waitFor { controller?.installing == false }
            val saved = app.gateway.obj("get", "profile_id" to "local", "id" to note.getString("id"))
            assertEquals("退后台安装交接合成笔记😀", saved.getString("content"))
            app.gateway.obj(
                "set_deleted",
                "profile_id" to "local",
                "id" to saved.getString("id"),
                "expected_generation" to saved.getString("generation"),
                "deleted" to true
            )
        }
        Unit
    }
}
