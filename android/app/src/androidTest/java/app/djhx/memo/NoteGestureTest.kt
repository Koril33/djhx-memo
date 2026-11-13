package app.djhx.memo

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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

/** Real touches exercise Compose gesture arbitration, not only the threshold predicate. */
@RunWith(AndroidJUnit4::class)
class NoteGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<MemoApplication>()
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(40)
        assertTrue("gesture fixture did not settle", condition())
    }
    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString() == text) return node
        for (index in 0 until node.childCount) node.getChild(
            index
        )?.let { find(it, text)?.let { found -> return found } }
        return null
    }
    private fun accessible(text: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitFor {
            found = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, text) }
            found != null
        }
        return found!!
    }
    private fun row(text: String): Rect {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString()?.lineSequence()?.any { it == text } == true) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isEditable || current.className?.toString() == "android.widget.EditText") break
                    if (current.isClickable) return current
                    current = current.parent
                }
            }
            for (index in 0 until node.childCount) node.getChild(
                index
            )?.let { visit(it)?.let { found -> return found } }
            return null
        }
        var target: AccessibilityNodeInfo? = null
        waitFor {
            target = instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
            target != null
        }
        return Rect().also { target!!.getBoundsInScreen(it) }
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "1") return
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(application.getExternalFilesDir(null), name).outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
    private fun touch(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
        event.recycle()
    }
    private fun swipe(bounds: Rect, fraction: Float, quick: Boolean = false, beforeLift: () -> Unit = {}) {
        val x = bounds.left + bounds.width() * 0.85f
        val y = bounds.exactCenterY()
        val end = x - bounds.width() * fraction
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        repeat(if (quick) 2 else 10) { index ->
            val steps = if (quick) 2 else 10
            SystemClock.sleep(if (quick) 1 else 16)
            touch(MotionEvent.ACTION_MOVE, down, x + (end - x) * (index + 1) / steps, y)
        }
        beforeLift()
        touch(MotionEvent.ACTION_UP, down, end, y)
        instrumentation.waitForIdleSync()
    }

    @Test fun realLongPressRotationAndReleaseDistanceKeepTheCorrectNote() = runBlocking {
        val title = "gesture-" + UUID.randomUUID().toString().take(8)
        val note = application.gateway.obj("create", "request_id" to UUID.randomUUID().toString(), "content" to title)
        val id = note.getString("id")
        fun deleted() = runBlocking { application.gateway.obj("get", "id" to id).getBoolean("deleted") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: MemoModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[MemoModel::class.java]
                model.backToList()
            }
            waitFor { !model.busy && !model.editorOpen }
            scenario.onActivity { model.search(title) }
            waitFor { model.notes.size == 1 }
            var bounds = row(title)
            assertTrue(bounds.width() > 300)
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, down, bounds.exactCenterX(), bounds.exactCenterY())
            SystemClock.sleep(700)
            touch(MotionEvent.ACTION_UP, down, bounds.exactCenterX(), bounds.exactCenterY())
            waitFor { model.selecting && model.selection == setOf(id) }
            capture("selection-016.png")
            scenario.recreate()
            waitFor { model.selecting && model.selection == setOf(id) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor { !model.selecting }
            bounds = row(title)
            swipe(bounds, 0.49f)
            SystemClock.sleep(500)
            assertFalse(deleted())
            swipe(bounds, 0.15f, quick = true)
            SystemClock.sleep(300)
            assertFalse(deleted())
            swipe(bounds, 0.51f) { assertFalse("Drag alone cannot delete before release", deleted()) }
            waitFor { deleted() && !model.busy }
            var undo = accessible(application.getString(R.string.undo_trash))
            capture("undo-016.png")
            while (!undo.isClickable && undo.parent != null) undo = undo.parent
            assertTrue(undo.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor { !deleted() && !model.busy }
        }
        val current = application.gateway.obj("get", "id" to id)
        application.gateway.obj(
            "set_deleted",
            "id" to id,
            "expected_generation" to current.getString("generation"),
            "deleted" to true
        )
        Unit
    }
}
