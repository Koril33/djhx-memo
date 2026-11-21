package app.djhx.memo

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Measures actual Compose accessibility bounds and exercises the real local search gateway. */
@RunWith(AndroidJUnit4::class)
class SearchLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<MemoApplication>()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Search UI did not settle", condition())
        instrumentation.waitForIdleSync()
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.refresh()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> visit(child)?.let { return it } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun input(): AccessibilityNodeInfo? = find { it.isEditable && it.isVisibleToUser }

    private fun assertCentered() {
        instrumentation.waitForIdleSync()
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val window = Rect().also(root::getBoundsInScreen)
        val field = Rect().also(checkNotNull(input())::getBoundsInScreen)
        val density = application.resources.displayMetrics.density
        val expectedWidth = min(window.width() - 32 * density, 720 * density).roundToInt()
        assertTrue(
            "Search width: window=$window field=$field density=$density expected=$expectedWidth",
            abs(expectedWidth - field.width()) <= 2
        )
        assertTrue(
            "Search must have equal outer margins",
            abs((field.left - window.left) - (window.right - field.right)) <= 2
        )
        assertTrue("Search must keep a 16dp outer margin", field.left - window.left >= (16 * density).roundToInt() - 1)
        assertTrue("Search input must not be vertically clipped", field.height() >= (56 * density).roundToInt() - 1)
    }

    private fun back() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
    }

    @Test fun collapsedAndExpandedSearchRemainCenteredAndUsable() {
        val token = "layout${UUID.randomUUID().toString().replace("-", "")}"
        val note = runBlocking {
            application.gateway.obj("switch", "profile_id" to "local")
            application.gateway.obj(
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to "# $token\n\nSynthetic search layout fixture."
            )
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MemoModel
                scenario.onActivity { model = ViewModelProvider(it)[MemoModel::class.java] }
                waitFor { model.state.optJSONObject("profile") != null && input() != null }
                assertCentered()
                assertTrue(checkNotNull(input()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                // A landscape phone's IME can occupy the entire result area. Dismiss
                // it without collapsing search before exercising the result controls.
                if (application.resources.configuration.screenWidthDp > 720) {
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(350)
                    scenario.onActivity {
                        it.getSystemService(InputMethodManager::class.java)
                            .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
                    }
                }
                val hint = application.getString(R.string.search_scope_hint_local)
                waitFor { find { it.text?.toString() == hint } != null }
                val query = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, token)
                }
                assertTrue(checkNotNull(input()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, query))
                waitFor { model.searchQuery == token && !model.searchLoading && model.searchHits.isNotEmpty() }
                assertFalse("Real core search must succeed", model.searchError)
                assertEquals(note.getString("id"), model.searchHits.first().getString("note_id"))
                assertCentered()
                val clear = application.getString(R.string.clear_search)
                waitFor { find { it.contentDescription?.toString() == clear } != null }
                var clearButton = checkNotNull(find { it.contentDescription?.toString() == clear })
                while (!clearButton.isClickable && clearButton.parent != null) {
                    clearButton = checkNotNull(clearButton.parent)
                }
                val cleared = clearButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                assertTrue("Clear must be an actionable button", cleared)
                waitFor { model.searchQuery.isEmpty() }
                assertCentered()
                // IME consumes the first Back on some devices; only send another while expanded.
                back()
                if (find { it.text?.toString() == hint } != null) back()
                waitFor { find { it.text?.toString() == hint } == null }
                assertCentered()
            }
        } finally {
            runBlocking {
                val current = application.gateway.obj("get", "profile_id" to "local", "id" to note.getString("id"))
                application.gateway.obj(
                    "set_deleted",
                    "profile_id" to "local",
                    "id" to current.getString("id"),
                    "expected_generation" to current.getString("generation"),
                    "deleted" to true
                )
            }
        }
    }
}
