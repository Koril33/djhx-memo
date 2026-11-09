package app.djhx.memo

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorLifecycleTest {
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("editor state did not settle", condition())
    }

    private fun editText(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                editText(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    @Test fun continuousInputPersistsWithinOneSecondAndRotationKeepsBuffer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: MemoModel
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[MemoModel::class.java] }
            waitFor { model.state.has("profile") }
            scenario.onActivity { model.switchProfile("local") }
            waitFor { !model.busy }
            scenario.onActivity { model.newNote() }
            waitFor { model.editorOpen && !model.busy }
            repeat(15) { index ->
                scenario.onActivity { model.edit("# 生命周期验证\n中文😀 连续输入 $index") }
                SystemClock.sleep(100)
            }
            // 输入未停止时已产生实际 SQLite 笔记，不能只依赖后台或结束输入才提交。
            assertNotNull(model.selected)
            waitFor { !model.pending }
            val saved = model.content
            assertEquals(R.string.save_success, model.localStatus.resource)
            scenario.recreate()
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[MemoModel::class.java] }
            assertEquals(saved, model.content)
            scenario.onActivity { model.backToList() }
            waitFor { !model.editorOpen && !model.busy }
            scenario.onActivity { model.open(model.notes.first { it.optString("content") == saved }) }
            waitFor { model.editorOpen && !model.busy }
            assertEquals(saved, model.content)
            scenario.onActivity { model.setDeleted(true) }
            waitFor { !model.editorOpen && !model.busy }
        }
    }

    @Test fun nativeSyntaxHighlightDoesNotRemoveChineseComposingSpan() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: MemoModel
            scenario.onActivity { activity -> model = ViewModelProvider(activity)[MemoModel::class.java] }
            waitFor { model.state.has("profile") }
            scenario.onActivity { model.newNote() }
            waitFor { model.editorOpen && !model.busy }
            var editorReady = false
            waitFor {
                scenario.onActivity { activity -> editorReady = editText(activity.window.decorView) != null }
                editorReady
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val editor = checkNotNull(editText(activity.window.decorView))
                editor.requestFocus()
                val connection = checkNotNull(editor.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                connection.setComposingText("# 中文输入中", 1)
                assertTrue(BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
                editor.append("😀")
                assertTrue(BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
            }
            waitFor { !model.pending && model.selected != null }
            val current = model.selected!!
            val application = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>() as MemoApplication
            val gateway = application.gateway
            runBlocking {
                gateway.obj(
                    "save",
                    "profile_id" to "local",
                    "id" to current.getString("id"),
                    "expected_generation" to current.getString("generation"),
                    "content" to "# 独立更新"
                )
            }
            SystemClock.sleep(1300)
            scenario.onActivity { activity ->
                val editor = checkNotNull(editText(activity.window.decorView))
                assertTrue(BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
                assertTrue(editor.text.toString().contains("中文输入中"))
                checkNotNull(
                    editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                ).finishComposingText()
            }
            waitFor { model.content == "# 独立更新" }
            scenario.onActivity { model.setDeleted(true) }
            waitFor { !model.editorOpen && !model.busy }
        }
    }
}
