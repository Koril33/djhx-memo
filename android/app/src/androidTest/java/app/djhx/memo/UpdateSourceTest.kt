package app.djhx.memo

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateSourceTest {
    private fun node(text: String): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            current.refresh()
            val matchesText = current.text?.toString()?.lineSequence()?.any { it == text } == true
            if (matchesText || current.contentDescription?.toString() == text) return current
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let { find(it) }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Update source state did not settle", condition())
    }

    private fun click(text: String) {
        waitFor { node(text) != null }
        var selected = checkNotNull(node(text))
        while (!selected.isClickable && selected.parent != null) selected = selected.parent
        assertTrue(selected.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test
    fun bothNativeSourceControlsPersistAcrossActivityRecreation() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MemoApplication>()
        val original = PresentationStore(app).preferences
        app.updates.refresh()
        val originalSource = app.updates.status.optString("download_source", "memo")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.presentation.update(DisplayPreferences("en", "system", "Asia/Shanghai")) }
                click("More")
                click("Settings")
                click("Updates")
                waitFor { node("Download source") != null && node("memo.djhx.site") != null && node("GitHub") != null }
                click("GitHub")
                waitFor { app.updates.status.optString("download_source") == "github" && !app.updates.preferencesPending }
                assertNull(app.updates.status.nullableString("source_error"))
                scenario.recreate()
                // The existing settings dialog resets its tab on recreation.
                click("Updates")
                waitFor { node("Download source") != null }
                assertEquals("github", app.updates.status.optString("download_source"))
                click("memo.djhx.site")
                waitFor { app.updates.status.optString("download_source") == "memo" && !app.updates.preferencesPending }
                click("Close settings")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                app.updates.changeSource(originalSource)
                PresentationStore(app).update(original)
            }
            waitFor { !app.updates.preferencesPending }
        }
    }
}
