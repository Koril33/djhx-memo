package app.djhx.memo

import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.djhx_memo_core.CredentialStore
import uniffi.djhx_memo_core.MemoApp
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UpdatePlatformTest {
    @Test fun uncommittedJournaledSessionIsAbandonedAfterControllerRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as MemoApplication
        val installer = context.packageManager.packageInstaller
        val journal = context.getSharedPreferences("updates-install", Context.MODE_PRIVATE)
        val id = installer.createSession(
            android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
        )
        try {
            assertFalse(installer.getSessionInfo(id)!!.isCommitted)
            assertTrue(journal.edit().putInt("session", id).remove("continuation").commit())
            val restored = AppUpdates(app)
            assertTrue(restored.installing)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { restored.foreground(null, true) }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (restored.installing && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertFalse("Interrupted pre-commit installation must not permanently lock notes", restored.installing)
            assertFalse(journal.contains("session"))
            assertNull(installer.getSessionInfo(id))
            assertNotNull(app.gateway.obj("state"))
        } finally {
            if (installer.getSessionInfo(id) != null) installer.abandonSession(id)
        }
    }

    @Test fun sessionRequiresSystemConfirmationAndKeepsCommittedData() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val candidate = args.getString("updateApk")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "Supply a newer isolated APK and grant its install-source permission",
            candidate != null && context.packageManager.canRequestPackageInstalls()
        )
        val app = context.applicationContext as MemoApplication
        val file = candidateFile(context, candidate!!)
        UpdateInstaller(context).verify(file, release(context, file))
        val note = app.gateway.obj(
            "create",
            "profile_id" to "local",
            "request_id" to UUID.randomUUID().toString(),
            "content" to "安装更新前的合成笔记😀"
        )
        context.filesDir.resolve("update-test-note-id.txt").writeText(note.getString("id"))
        app.gateway.pauseForUpdate()
        val id = UpdateInstaller(context).submit(file)
        val journal = context.getSharedPreferences("updates-install", Context.MODE_PRIVATE)
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        fun confirmationReady() = app.updates.needsConfirmation &&
            (journal.getBoolean("confirmation_pending", false) || journal.contains("continuation"))
        while (!confirmationReady() && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("System must request user confirmation", confirmationReady())
        assertEquals(id, journal.getInt("session", -1))
        if (args.getString("completeInstall") == "1") {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { app.updates.continueInstallation() }
                android.os.SystemClock.sleep(15000)
            }
        } else {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.updates.cancelInstallation() }
            var readable: JSONObject? = null
            val resumedBy = android.os.SystemClock.elapsedRealtime() + 5000
            while (readable == null && android.os.SystemClock.elapsedRealtime() < resumedBy) {
                readable = runCatching { app.gateway.obj("get", "profile_id" to "local", "id" to note.getString("id")) }.getOrNull()
                if (readable == null) Thread.sleep(50)
            }
            assertEquals("安装更新前的合成笔记😀", readable?.optString("content"))
        }
    }

    @Test fun upgradedInstallationRetainsTheCommittedNote() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run after the isolated system upgrade", args.getString("afterUpgrade") == "1")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as MemoApplication
        val id = context.filesDir.resolve("update-test-note-id.txt").readText()
        val note = app.gateway.obj("get", "profile_id" to "local", "id" to id)
        assertEquals("安装更新前的合成笔记😀", note.getString("content"))
    }

    private fun candidateFile(context: Context, candidate: String): File {
        if (!candidate.startsWith("asset:")) return File(candidate)
        val destination = context.cacheDir.resolve("update-platform-candidate.apk")
        InstrumentationRegistry.getInstrumentation().context.assets.open(
            candidate.removePrefix("asset:")
        ).use { input ->
            destination.outputStream().use { input.copyTo(it) }
        }
        return destination
    }

    @Suppress("DEPRECATION")
    private fun release(context: Context, file: File): JSONObject {
        val info = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        )!!
        val signer = info.signingInfo!!.apkContentsSigners.single().toByteArray()
        return JSONObject().put("version", info.versionName).put(
            "android",
            JSONObject().put("application_id", info.packageName).put("version_code", info.longVersionCode)
                .put("min_sdk", info.applicationInfo!!.minSdkVersion).put("certificate_sha256", sha256(signer))
                .put("abis", JSONArray(listOf("arm64-v8a", "x86_64")))
        )
    }

    @Test fun sameVersionAndWrongCertificateAreRefusedWithoutOpeningInstallSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.applicationInfo.sourceDir)
        val release = release(context, file)
        val before = context.packageManager.packageInstaller.mySessions.map { it.sessionId }
        val sameVersion = runCatching { UpdateInstaller(context).verify(file, release) }.exceptionOrNull()
        assertEquals("UPDATE_INCOMPATIBLE", (sameVersion as UpdateFailure).code)
        release.getJSONObject("android").put("certificate_sha256", "0".repeat(64))
        val wrongSigner = runCatching { UpdateInstaller(context).verify(file, release) }.exceptionOrNull()
        assertEquals("UPDATE_SIGNATURE", (wrongSigner as UpdateFailure).code)
        assertEquals(before, context.packageManager.packageInstaller.mySessions.map { it.sessionId })
    }

    @Test fun newerSameSignerUniversalApkPassesPlatformPreflight() {
        val candidate = InstrumentationRegistry.getArguments().getString("updateApk")
        assumeTrue("Supply an isolated newer APK as updateApk", candidate != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = candidateFile(context, candidate!!)
        assertTrue(file.exists())
        UpdateInstaller(context).verify(file, release(context, file))
    }

    /** Exercises the platform URI/Activity Result boundary with a known local APK fixture. */
    @Test fun compatibilityUriOpensSystemConfirmationAndWaitingCanBeCanceled() = runBlocking {
        val candidate = InstrumentationRegistry.getArguments().getString("updateApk")
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(
            "Supply a newer same-signer fixture and source permission",
            candidate != null && context.packageManager.canRequestPackageInstalls()
        )
        val app = context.applicationContext as MemoApplication
        val file = candidateFile(context, candidate!!)
        val metadata = release(context, file).put("size", file.length()).put("sha256", sha256(file.readBytes()))
        UpdateInstaller(context).verify(file, metadata)
        val compatibility = UpdateCompatibility(context)
        val staged = compatibility.stage(file, metadata)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var tracker: AppUpdates
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val note = app.gateway.obj(
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to "系统安装器交接合成笔记😀"
            )
            app.gateway.pauseForUpdate()
            compatibility.begin(staged, metadata)
            instrumentation.runOnMainSync { tracker = AppUpdates(app) }
            try {
                scenario.onActivity { it.launchUpdateInstaller(staged) }
                var cancel: AccessibilityNodeInfo? = null
                val deadline = android.os.SystemClock.elapsedRealtime() + 15000
                while (cancel == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (root?.packageName?.toString()?.contains("packageinstaller") == true) cancel = cancelButton(root)
                    if (cancel == null) Thread.sleep(50)
                }
                assertNotNull("The system confirmation must open from the private content URI", cancel)
                var button = checkNotNull(cancel)
                while (!button.isClickable && button.parent != null) button = button.parent
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.runOnMainSync {
                    tracker.systemInstallerReturned(staged.attempt)
                    assertTrue(tracker.installing)
                    assertEquals("UPDATE_INSTALL_UNKNOWN", tracker.installError)
                    tracker.cancelInstallation()
                }
                val resumedBy = android.os.SystemClock.elapsedRealtime() + 5000
                while (tracker.installing && android.os.SystemClock.elapsedRealtime() < resumedBy) Thread.sleep(25)
                assertFalse(tracker.installing)
                val saved = app.gateway.obj("get", "profile_id" to "local", "id" to note.getString("id"))
                assertEquals("系统安装器交接合成笔记😀", saved.getString("content"))
                app.gateway.obj(
                    "set_deleted",
                    "profile_id" to "local",
                    "id" to saved.getString("id"),
                    "expected_generation" to saved.getString("generation"),
                    "deleted" to true
                )
            } finally {
                instrumentation.runOnMainSync { tracker.cancelInstallation() }
                app.gateway.resumeAfterUpdate()
                compatibility.discard(staged)
            }
        }
        Unit
    }

    private fun cancelButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.text?.toString() in setOf("Cancel", "取消")) return node
        for (index in 0 until node.childCount) node.getChild(
            index
        )?.let { cancelButton(it)?.let { found -> return found } }
        return null
    }

    @Test fun nativeInstallBarrierPreservesCommittedChineseTextAndCanResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.filesDir.resolve("update-barrier-${UUID.randomUUID()}")
        val credentials = object : CredentialStore {
            override fun load(profileId: String): String? = null
            override fun save(profileId: String, token: String): Boolean = false
            override fun remove(profileId: String) = Unit
        }
        MemoApp(root.absolutePath, credentials, true).use { core ->
            val create = JSONObject().put("action", "create").put("request_id", UUID.randomUUID().toString())
                .put("content", "更新前已提交的中文😀")
            assertTrue(JSONObject(core.invoke(create.toString())).getBoolean("ok"))
            core.pauseForUpdate()
            val blocked = JSONObject(core.invoke("{\"action\":\"state\"}"))
            assertEquals("UPDATE_BUSY", blocked.getJSONObject("error").getString("code"))
            core.resumeAfterUpdate()
            val listed = JSONObject(core.invoke("{\"action\":\"list\"}"))
            assertTrue(listed.getBoolean("ok"))
            assertEquals("更新前已提交的中文😀", listed.getJSONArray("data").getJSONObject(0).getString("content"))
        }
    }
}
