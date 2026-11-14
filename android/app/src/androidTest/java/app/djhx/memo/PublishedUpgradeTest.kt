package app.djhx.memo

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Opt-in end-to-end probe against the published signed feed on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class PublishedUpgradeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private suspend fun command(updates: AppUpdates, name: String, timeout: Long) = withTimeout(timeout) {
        withContext(Dispatchers.Main) {
            // Test-only bridge to the same suspend handler used by action(). It also works
            // against released APKs, without adding a production API or trusting a stale snapshot.
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                AppUpdates::class.java.getDeclaredMethod("perform", String::class.java, Continuation::class.java)
                    .apply { isAccessible = true }.invoke(updates, name, continuation)
            }
        }
    }
    private fun report(updates: AppUpdates, stage: String) {
        if (InstrumentationRegistry.getArguments().getString("reportUpdates") != "1") return
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString("update_stage", stage)
                putString("update_phase", updates.status.optString("phase"))
                putLong("update_downloaded", updates.status.optLong("downloaded"))
                putLong("update_size", updates.status.optJSONObject("candidate")?.optLong("size") ?: 0)
                putString("update_error", updates.status.optString("error"))
            }
        )
    }
    private fun waitFor(timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Published upgrade stage did not settle", condition())
    }
    private fun installButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val installer = node.packageName?.toString()?.contains("packageinstaller") == true
        val label = node.text?.toString() in setOf("Update", "Install", "更新", "安装")
        if (installer && label && node.isClickable) return node
        for (index in 0 until node.childCount) node.getChild(
            index
        )?.let { installButton(it)?.let { found -> return found } }
        return null
    }

    @Suppress("DEPRECATION") // getPackageInfo with integer flags supports API29.
    @Test
    fun publishedUpdateDownloadsAndRequestsSystemUpgrade() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires explicit publishedUpgrade=1 on the release emulator",
            args.getString("publishedUpgrade") == "1"
        )
        val sourceVersion = args.getString("sourceVersion") ?: "0.1.5"
        val targetVersion = args.getString("targetVersion") ?: "0.1.6"
        val app = ApplicationProvider.getApplicationContext<MemoApplication>()
        assumeTrue(app.packageManager.getPackageInfo(app.packageName, 0).versionName == sourceVersion)
        val proxyPort = args.getString("localProxyPort")?.toIntOrNull()
        if (proxyPort != null) {
            require(proxyPort in 1..65535)
            // Emulator host alias only; this is process-local test configuration, never a device setting.
            android.system.Os.setenv("HTTPS_PROXY", "http://10.0.2.2:$proxyPort", true)
        }
        val note = app.gateway.obj(
            "create",
            "profile_id" to "local",
            "request_id" to UUID.randomUUID().toString(),
            "content" to "升级检查开始的合成笔记"
        )
        app.filesDir.resolve("update-test-note-id.txt").writeText(note.getString("id"))
        val updates = app.updates
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: MemoModel
            scenario.onActivity {
                model = ViewModelProvider(it)[MemoModel::class.java]
            }
            command(updates, "check", 60000)
            report(updates, "check_returned")
            assertTrue(updates.status.optString("phase") in setOf("available", "ready"))
            assertEquals(sourceVersion, updates.status.getString("current_version"))
            assertEquals(targetVersion, updates.status.getJSONObject("candidate").getString("version"))
            assertNotEquals("failed", updates.status.optString("phase"))
            command(updates, "download", 180000)
            report(updates, "download_returned")
            assertEquals("ready", updates.status.getString("phase"))
            scenario.onActivity { model.open(note) }
            waitFor { model.editorOpen && !model.busy }
            scenario.onActivity {
                model.edit("安装更新前的合成笔记😀")
                updates.install(model)
            }
            var button: AccessibilityNodeInfo? = null
            waitFor(60000) {
                button = instrumentation.uiAutomation.rootInActiveWindow?.let(::installButton)
                button != null || updates.installError != null
            }
            assertNull(updates.installError)
            assertNotNull(button)
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("upgrade_stage", "verified_download_checkpoint_and_system_confirmation")
                }
            )
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            // The OS replaces and terminates this old process. The host verifies the exact target version/code
            // and reruns UpdatePlatformTest.upgradedInstallationRetainsTheCommittedNote afterward.
            SystemClock.sleep(15000)
        }
        Unit
    }
}
