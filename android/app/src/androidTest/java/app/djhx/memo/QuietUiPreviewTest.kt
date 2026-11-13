package app.djhx.memo

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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

@RunWith(AndroidJUnit4::class)
class QuietUiPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<MemoApplication>()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("UI preview state did not settle", condition())
    }

    private fun findNode(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.refresh()
            if (node.text?.toString()?.lineSequence()?.any {
                    it == text
                } == true || node.contentDescription?.toString() == text
            ) {
                return node
            }
            for (index in 0 until node.childCount) node.getChild(
                index
            )?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let { visit(it) }
    }

    private fun click(text: String) {
        waitFor { findNode(text) != null }
        var node = checkNotNull(findNode(text))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Action must be available: $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun scrollBackTo(text: String) {
        fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable && !node.isEditable) return node
            for (index in 0 until node.childCount) node.getChild(
                index
            )?.let { scrollable(it)?.let { found -> return found } }
            return null
        }
        repeat(8) {
            if (findNode(text) != null) return
            val list = instrumentation.uiAutomation.rootInActiveWindow?.let(::scrollable)
            list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
        }
        waitFor { findNode(text) != null }
    }

    private fun editor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun back() {
        val now = SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0),
            true
        )
        instrumentation.uiAutomation.injectInputEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0),
            true
        )
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "0.1.1-$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DJHX-Memo-ui-preview")
        }
        val destination =
            checkNotNull(application.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(application.contentResolver.openOutputStream(destination)).use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun seed(): List<String> = runBlocking {
        val content = listOf(
            "# 一杯咖啡的时间\n\n想法不必完整，先记下来。\n\n清晨的光落在窗边，今天也从一件小事开始。",
            "# 周末的小计划\n\n去河边散步，整理书桌，给朋友写封信。\n\n- [ ] 买一束花\n- [ ] 去那家旧书店\n- [x] 留一个没有安排的下午",
            "# 灵感拾记\n\n让工具轻一点，让想法多一点空间。\n\n纸白、墨灰，一点安静的绿色。",
            "# 留白与日常\n\n把今天值得记住的小事，放在这里。\n\n## 午后的片刻\n\n合上忙碌的日程，给自己留一页空白。\n\n- [ ] 整理今天的想法\n- [x] 读完一本书的序章\n\n> 慢慢写，也慢慢想。\n"
        )
        content.map { text ->
            application.gateway.obj(
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to text
            ).getString("id")
        }
    }

    private fun cleanup(ids: List<String>) = runBlocking {
        ids.forEach { id ->
            val note = application.gateway.obj("get", "profile_id" to "local", "id" to id)
            if (!note.optBoolean("deleted")) {
                application.gateway.obj(
                    "set_deleted",
                    "profile_id" to "local",
                    "id" to id,
                    "expected_generation" to note.getString("generation"),
                    "deleted" to true
                )
            }
        }
    }

    private fun previewKeepsEditorAndUndo(scenario: ActivityScenario<MainActivity>, model: MemoModel) {
        lateinit var input: EditText
        scenario.onActivity { input = checkNotNull(editor(it.window.decorView)) }
        val original = model.content
        scenario.onActivity {
            input.requestFocus()
            input.setSelection(input.length())
            checkNotNull(input.onCreateInputConnection(android.view.inputmethod.EditorInfo())).commitText("\n撤销验证", 1)
        }
        waitFor { !model.pending && model.content == original + "\n撤销验证" }
        click("预览")
        waitFor {
            !input.isShown && !input.isEnabled &&
                input.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        assertFalse(input.isEnabled)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, input.importantForAccessibility)
        click("源码")
        waitFor { input.isShown && input.isEnabled }
        scenario.onActivity {
            assertSame(input, editor(it.window.decorView))
            assertEquals(original + "\n撤销验证", input.text.toString())
            input.requestFocus()
            assertTrue("Native undo must survive preview", input.onTextContextMenuItem(android.R.id.undo))
        }
        waitFor { model.content == original && !model.pending }
        scenario.onActivity {
            input.setSelection(input.length())
            checkNotNull(
                input.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            ).setComposingText("\n组合文字", 1)
            assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text) >= 0)
        }
        click("预览")
        waitFor { !input.isShown && model.content == original + "\n组合文字" }
        click("源码")
        waitFor { input.isShown && input.isEnabled }
        scenario.onActivity {
            assertSame(input, editor(it.window.decorView))
            assertEquals(original + "\n组合文字", input.text.toString())
            input.requestFocus()
            assertTrue(input.onTextContextMenuItem(android.R.id.undo))
        }
        waitFor { model.content == original && !model.pending }
        scenario.onActivity { input.clearFocus() }
    }

    @Suppress("LongMethod", "NestedBlockDepth")
    @Test
    fun quietScreensKeepEditingMenusAndSafetyActionsReachable() {
        val originalPreferences = PresentationStore(application).preferences
        val ids = seed()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MemoModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[MemoModel::class.java]
                    it.presentation.update(DisplayPreferences("zh-CN", "light", "Asia/Shanghai"))
                }
                waitFor { model.state.has("profile") }
                scenario.onActivity { model.switchProfile("local") }
                waitFor { !model.busy && model.notes.any { it.optString("id") == ids.last() } }
                capture("zh-list")
                click("留白与日常")
                waitFor { model.editorOpen && !model.busy }
                capture("zh-editor")
                assertNull("The editor has no global new-note bar", findNode("新建笔记"))
                previewKeepsEditorAndUndo(scenario, model)
                click("更多")
                click("保存与同步状态")
                waitFor { findNode("本机保存") != null && findNode("云同步") != null }
                capture("zh-status")
                click("完成")
                click("更多")
                click("设置")
                waitFor { findNode("显示") != null && findNode("空间") != null && findNode("设备") != null && findNode("数据") != null }
                capture("zh-settings")
                assertNull("Account form does not crowd display preferences", findNode("服务器地址"))
                click("空间")
                waitFor { findNode("本机笔记") != null }
                var currentSpace = checkNotNull(findNode("本机笔记"))
                while (!currentSpace.isSelected && !currentSpace.isChecked && currentSpace.parent != null) {
                    currentSpace = currentSpace.parent
                }
                assertTrue(
                    "Current space must be announced as selected",
                    currentSpace.isSelected || currentSpace.isChecked
                )
                click("关闭设置")
                waitFor { findNode("预览") != null }
                scenario.onActivity { it.presentation.update(DisplayPreferences("zh-CN", "dark", "Asia/Shanghai")) }
                waitFor { findNode("预览") != null }
                capture("zh-editor-dark")
                scenario.onActivity { it.presentation.update(DisplayPreferences("en", "light", "Asia/Shanghai")) }
                waitFor { findNode("Preview") != null }
                capture("en-editor")
                click("More")
                click("Delete")
                waitFor { !model.editorOpen && !model.busy }
                waitFor { findNode("Undo deletion") != null }
                assertTrue(runBlocking { application.gateway.obj("get", "id" to ids.last()).getBoolean("deleted") })
                click("Undo deletion")
                waitFor { !model.busy && model.notes.any { it.optString("id") == ids.last() } }
                assertFalse(runBlocking { application.gateway.obj("get", "id" to ids.last()).getBoolean("deleted") })
                assertEquals("留白与日常", model.notes.first { it.optString("id") == ids.last() }.getString("title"))
                scrollBackTo("留白与日常")
                capture("en-restored-list")
                click("留白与日常")
                waitFor { model.editorOpen && !model.busy }
                assertEquals(ids.last(), model.selected!!.getString("id"))
                click("More")
                click("Delete")
                waitFor { !model.editorOpen && !model.busy }
                click("More")
                click("Trash")
                waitFor { model.trash && model.notes.any { it.optString("id") == ids.last() } }
                click("留白与日常")
                waitFor { model.editorOpen && !model.busy }
                scenario.onActivity {
                    val hidden = checkNotNull(editor(it.window.decorView))
                    assertFalse(hidden.isEnabled)
                    assertFalse(hidden.isShown)
                }
                click("More")
                click("Restore")
                waitFor { !model.editorOpen && !model.busy }
                click("Back")
                waitFor { !model.trash && !model.busy }
                click("More")
                click("Export current space")
                waitFor { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
                back()
            }
        } finally {
            val keepSamples = InstrumentationRegistry.getArguments().getString("keepUiPreviewSamples") == "true"
            if (!keepSamples) cleanup(ids)
            instrumentation.runOnMainSync {
                PresentationStore(
                    application
                ).update(
                    if (keepSamples) DisplayPreferences("zh-CN", "light", "Asia/Shanghai") else originalPreferences
                )
            }
        }
    }
}
