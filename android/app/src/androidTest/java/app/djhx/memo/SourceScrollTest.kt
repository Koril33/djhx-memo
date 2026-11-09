package app.djhx.memo

import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SourceScrollTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<MemoApplication>()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("scroll fixture did not settle", condition())
    }

    private fun editor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun clickDescription(label: String) {
        fun find(
            node: android.view.accessibility.AccessibilityNodeInfo
        ): android.view.accessibility.AccessibilityNodeInfo? {
            node.refresh()
            if (node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        var target: android.view.accessibility.AccessibilityNodeInfo? = null
        waitFor {
            target = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            target != null
        }
        var node = checkNotNull(target)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }

    private val gestureBounds = android.graphics.Rect()

    private fun swipe(input: EditText, forward: Boolean = true, onLift: () -> Unit = {}) {
        val bounds = android.graphics.Rect()
        instrumentation.runOnMainSync {
            if (input.isShown) {
                assertTrue(((input.parent as? android.widget.ScrollView) ?: input).getGlobalVisibleRect(gestureBounds))
            }
            bounds.set(gestureBounds)
        }
        swipeBounds(bounds, forward, onLift)
    }

    private fun swipeBounds(bounds: android.graphics.Rect, forward: Boolean = true, onLift: () -> Unit = {}) {
        val x = bounds.left + bounds.width() * 0.6f
        val startY = bounds.top + bounds.height() * (if (forward) 0.75f else 0.35f)
        val endY = bounds.top + bounds.height() * (if (forward) 0.35f else 0.75f)
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = fingerEvent(start, action, x, y)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, startY)
        repeat(12) { index ->
            SystemClock.sleep(12)
            send(MotionEvent.ACTION_MOVE, startY + (endY - startY) * (index + 1) / 12)
        }
        onLift()
        send(MotionEvent.ACTION_UP, endY)
    }

    private fun positions(read: () -> Int): List<Int> = buildList {
        add(read())
        repeat(12) {
            SystemClock.sleep(40)
            add(read())
        }
    }

    private fun verifyEdgesAndNativeTouch(
        scenario: ActivityScenario<MainActivity>,
        input: EditText,
        model: MemoModel,
        content: String
    ) {
        val viewport = input.parent as android.widget.ScrollView
        captureHeaderReference(viewport)
        var maximum = 0
        var generation = ""
        scenario.onActivity {
            maximum = (input.height - viewport.height).coerceAtLeast(0)
            generation = model.selected!!.getString("generation")
            assertTrue("Fixture must extend well beyond the viewport", maximum > viewport.height * 3)
            viewport.scrollTo(0, maximum)
        }
        swipe(input)
        SystemClock.sleep(700)
        scenario.onActivity {
            assertEquals("Bottom edge must stop", maximum, viewport.scrollY)
            assertEquals(0, input.selectionStart)
            viewport.scrollTo(0, 0)
        }
        swipe(input, forward = false)
        SystemClock.sleep(700)
        scenario.onActivity {
            assertEquals("Top edge must stop", 0, viewport.scrollY)
            assertEquals(0, input.selectionStart)
            assertEquals(content, input.text.toString())
            assertEquals(generation, model.selected!!.getString("generation"))
            viewport.scrollTo(0, maximum / 2)
        }
        val visible = android.graphics.Rect()
        scenario.onActivity { assertTrue(viewport.getGlobalVisibleRect(visible)) }
        val x = visible.left + visible.width() * 0.55f
        val y = visible.top + visible.height() * 0.3f
        touch(x, y, longPress = false)
        var tapOffset = 0
        scenario.onActivity {
            tapOffset = input.selectionStart
            assertEquals(tapOffset, input.selectionEnd)
            assertTrue("A tap deep in the note must place the caret there", tapOffset > content.length / 4)
            if (InstrumentationRegistry.getArguments().getString("expectIme") == "true") {
                (
                    input.context.getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod.InputMethodManager
                    )
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        if (InstrumentationRegistry.getArguments().getString("expectIme") == "true") {
            var imeVisible = false
            waitFor {
                scenario.onActivity {
                    imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
                imeVisible
            }
            SystemClock.sleep(250)
            scenario.onActivity {
                assertTrue(viewport.getGlobalVisibleRect(visible))
                val location = IntArray(2)
                input.getLocationOnScreen(location)
                val line = input.layout.getLineForOffset(tapOffset)
                val caretTop = location[1] + input.totalPaddingTop + input.layout.getLineTop(line) - input.scrollY
                val caretBottom = location[1] + input.totalPaddingTop + input.layout.getLineBottom(line) - input.scrollY
                assertTrue("Caret must remain above the IME", caretTop >= visible.top && caretBottom <= visible.bottom)
            }
        }
        SystemClock.sleep(400)
        var wordX = 0f
        var wordY = 0f
        scenario.onActivity {
            assertTrue(viewport.getGlobalVisibleRect(visible))
            val location = IntArray(2)
            input.getLocationOnScreen(location)
            val firstLine = input.layout.getLineForVertical(
                visible.top - location[1] - input.totalPaddingTop + input.scrollY
            )
            val line = (firstLine + 1 until input.layout.lineCount).first { index ->
                val begin = input.layout.getLineStart(index)
                val end = input.layout.getLineEnd(index)
                input.text.subSequence(begin, end).any { it.isLetterOrDigit() }
            }
            val begin = input.layout.getLineStart(line)
            val end = input.layout.getLineEnd(line)
            val offset = begin + input.text.subSequence(begin, end).indexOfFirst { it.isLetterOrDigit() }
            wordX = location[0] + input.totalPaddingLeft +
                (input.layout.getPrimaryHorizontal(offset) + input.layout.getPrimaryHorizontal(offset + 1)) / 2
            wordY = location[1] + input.totalPaddingTop +
                (input.layout.getLineTop(line) + input.layout.getLineBottom(line)) / 2f - input.scrollY
            assertTrue(wordY >= visible.top && wordY <= visible.bottom)
        }
        touch(wordX, wordY, longPress = true)
        captureAndCheckViewport()
        scenario.onActivity {
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        "LONG_PRESS_SELECTION=${input.selectionStart}..${input.selectionEnd} " +
                            "target=$wordX,$wordY enabled=${input.isEnabled} longClickable=${input.isLongClickable}\n"
                    )
                }
            )
            assertTrue("Long press must keep native word selection", input.selectionStart < input.selectionEnd)
            assertEquals(content, input.text.toString())
            assertEquals(generation, model.selected!!.getString("generation"))
        }
        instrumentation.sendStatus(
            2,
            android.os.Bundle().apply {
                putString(
                    "stream",
                    "SOURCE_EDGES_TOUCH_OK max=$maximum tapOffset=$tapOffset ime=${InstrumentationRegistry.getArguments().getString(
                        "expectIme"
                    )}\n"
                )
            }
        )
    }

    private fun touch(x: Float, y: Float, longPress: Boolean) {
        val start = SystemClock.uptimeMillis()
        val down = fingerEvent(start, MotionEvent.ACTION_DOWN, x, y)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
        down.recycle()
        SystemClock.sleep(if (longPress) android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150 else 40)
        val up = fingerEvent(start, MotionEvent.ACTION_UP, x, y)
        assertTrue(instrumentation.uiAutomation.injectInputEvent(up, true))
        up.recycle()
        instrumentation.waitForIdleSync()
    }

    private val headerBounds = android.graphics.Rect()
    private var headerReference: android.graphics.Bitmap? = null

    private fun captureHeaderReference(viewport: android.widget.ScrollView) {
        instrumentation.runOnMainSync {
            viewport.fling(0)
            viewport.scrollTo(0, 0)
            assertTrue(viewport.getGlobalVisibleRect(headerBounds))
            headerBounds.top = (headerBounds.top - 64 * viewport.resources.displayMetrics.density).toInt().coerceAtLeast(0)
            val location = IntArray(2)
            viewport.getLocationOnScreen(location)
            headerBounds.bottom = location[1]
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        headerReference = android.graphics.Bitmap.createBitmap(
            screenshot, headerBounds.left, headerBounds.top, headerBounds.width(), headerBounds.height()
        )
        screenshot.recycle()
    }

    private fun captureAndCheckViewport() {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        application.cacheDir.resolve("source-longpress.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val actualHeader = android.graphics.Bitmap.createBitmap(
            screenshot,
            headerBounds.left,
            headerBounds.top,
            headerBounds.width(),
            headerBounds.height()
        )
        assertTrue(
            "Long text must not overdraw the unchanged toolbar",
            checkNotNull(headerReference).sameAs(actualHeader)
        )
        actualHeader.recycle()
        screenshot.recycle()
    }

    private fun fingerEvent(start: Long, action: Int, x: Float, y: Float): MotionEvent {
        val pointer = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords()
        coordinates.x = x
        coordinates.y = y
        coordinates.pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
        coordinates.size = 1f
        return MotionEvent.obtain(
            start, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    private fun demoSnapshot(): Map<String, String> = runBlocking {
        val titles = setOf("# 留白与日常", "# 灵感拾记", "# 周末的小计划", "# 一杯咖啡的时间")
        application.gateway.array("list", "profile_id" to "local", "deleted" to false).objects()
            .filter { it.optString("content").lineSequence().firstOrNull() in titles }
            .associate {
                it.getString("id") to application.gateway.obj("get", "profile_id" to "local", "id" to it.getString("id")).toString()
            }
    }

    @Suppress("LongMethod", "NestedBlockDepth")
    @Test
    fun sourceFlingContinuesAfterLiftAndKeepsBufferSelectionCompositionAndUndo() {
        val originalPreferences = PresentationStore(application).preferences
        val demos = demoSnapshot()
        val original = "# 滚动验证\n\n" + (1..180).joinToString("\n\n") { "第 $it 段：中文 English 😀，滚动时正文保持不变。" }
        val note = runBlocking {
            application.gateway.obj(
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to original
            )
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MemoModel
                lateinit var input: EditText
                scenario.onActivity {
                    model = ViewModelProvider(it)[MemoModel::class.java]
                    it.presentation.update(DisplayPreferences("en", "light", "Asia/Shanghai"))
                }
                waitFor { model.state.has("profile") }
                scenario.onActivity { model.switchProfile("local") }
                waitFor { !model.busy }
                scenario.onActivity { model.open(note) }
                waitFor { model.editorOpen && !model.busy }
                scenario.onActivity { input = checkNotNull(editor(it.window.decorView)) }
                val changes = AtomicInteger()
                scenario.onActivity {
                    input.requestFocus()
                    input.setSelection(0)
                    checkNotNull(
                        input.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                    ).commitText("撤销标记", 1)
                    input.setSelection(0)
                    checkNotNull(
                        input.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                    ).setComposingRegion(0, 4)
                    input.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                        ) { changes.incrementAndGet() }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                    ((input.parent as? android.widget.ScrollView) ?: input).scrollTo(0, 0)
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(250)
                val afterLift = java.util.Collections.synchronizedList(mutableListOf<Int>())
                val recording = java.util.concurrent.atomic.AtomicBoolean()
                lateinit var viewport: View
                val observer = android.view.ViewTreeObserver.OnScrollChangedListener {
                    if (recording.get()) afterLift.add(viewport.scrollY)
                }
                scenario.onActivity {
                    viewport = (input.parent as? android.widget.ScrollView) ?: input
                    viewport.viewTreeObserver.addOnScrollChangedListener(observer)
                }
                swipe(input, onLift = { recording.set(true) })
                val nativePositions = positions {
                    var y = 0
                    scenario.onActivity { y = (input.parent as? android.widget.ScrollView)?.scrollY ?: input.scrollY }
                    y
                }
                recording.set(false)
                val frames = synchronized(afterLift) { afterLift.toList().distinct() }
                val sourceState = IntArray(5)
                scenario.onActivity {
                    viewport.viewTreeObserver.removeOnScrollChangedListener(observer)
                    assertEquals("撤销标记$original", input.text.toString())
                    sourceState[0] = input.selectionStart
                    sourceState[1] = input.selectionEnd
                    sourceState[2] = BaseInputConnection.getComposingSpanStart(input.text)
                    sourceState[3] = BaseInputConnection.getComposingSpanEnd(input.text)
                    sourceState[4] = changes.get()
                }
                instrumentation.sendStatus(
                    2,
                    android.os.Bundle().apply {
                        putString(
                            "stream",
                            "SOURCE_SCROLL_AFTER_UP=$nativePositions EVENTS=$frames STATE=${sourceState.toList()}\n"
                        )
                    }
                )
                clickDescription("Preview")
                waitFor { !input.isShown }
                fun scrollable(
                    node: android.view.accessibility.AccessibilityNodeInfo
                ): android.view.accessibility.AccessibilityNodeInfo? {
                    node.refresh()
                    if (node.isScrollable) return node
                    for (index in 0 until node.childCount) node.getChild(
                        index
                    )?.let { scrollable(it)?.let { found -> return found } }
                    return null
                }
                var preview: android.view.accessibility.AccessibilityNodeInfo? = null
                waitFor {
                    preview = instrumentation.uiAutomation.rootInActiveWindow?.let(::scrollable)
                    preview != null
                }
                val previewBounds = android.graphics.Rect()
                checkNotNull(preview).getBoundsInScreen(previewBounds)
                swipeBounds(previewBounds)
                clickDescription("Source")
                waitFor { input.isShown }
                scenario.onActivity {
                    assertSame(input, editor(it.window.decorView))
                    input.requestFocus()
                    assertTrue(input.onTextContextMenuItem(android.R.id.undo))
                }
                waitFor { model.content == original && !model.pending }
                assertEquals(
                    "Scrolling must preserve caret, composition and body",
                    listOf(0, 0, 0, 4, 0),
                    sourceState.toList()
                )
                assertTrue("Drag must move the source viewport", nativePositions.first() > 100)
                assertTrue(
                    "Source must keep moving after the finger lifts: $nativePositions",
                    nativePositions.last() > nativePositions.first() + 32
                )
                assertTrue(
                    "Inertia must advance across native scroll frames: $frames",
                    frames.zipWithNext().count { (a, b) -> b > a } >= 3
                )
                verifyEdgesAndNativeTouch(scenario, input, model, original)
            }
        } finally {
            runBlocking {
                val latest = application.gateway.obj("get", "profile_id" to "local", "id" to note.getString("id"))
                if (!latest.optBoolean("deleted")) {
                    application.gateway.obj(
                        "set_deleted",
                        "profile_id" to "local",
                        "id" to note.getString("id"),
                        "expected_generation" to latest.getString("generation"),
                        "deleted" to true
                    )
                }
            }
            assertEquals("Existing demonstration notes must remain untouched", demos, demoSnapshot())
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString("stream", "EXISTING_DEMO_RECORDS_UNCHANGED=${demos.size}\n")
                }
            )
            instrumentation.runOnMainSync { PresentationStore(application).update(originalPreferences) }
            headerReference?.recycle()
            headerReference = null
        }
    }
}
