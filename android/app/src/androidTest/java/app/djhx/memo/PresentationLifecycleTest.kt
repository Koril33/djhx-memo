package app.djhx.memo

import android.content.ContentValues
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresentationLifecycleTest {
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("presentation state did not settle", condition())
    }

    private fun editor(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun visibleText(text: String): android.view.accessibility.AccessibilityNodeInfo? {
        fun find(
            node: android.view.accessibility.AccessibilityNodeInfo
        ): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { find(it)?.let { result -> return result } }
            }
            return null
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let { find(it) }
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (visibleText(text) == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        val activePackage = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName
        assertNotNull("UI action unavailable: $text; active package: $activePackage", visibleText(text))
        var node = checkNotNull(visibleText(text))
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Suppress("NestedBlockDepth")
    @Test
    fun languageAndThemePreserveNativeCompositionAndPreferencesSurviveRecreation() {
        val context = ApplicationProvider.getApplicationContext<MemoApplication>()
        val original = PresentationStore(context).preferences
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MemoModel
                scenario.onActivity { model = ViewModelProvider(it)[MemoModel::class.java] }
                waitFor { model.state.has("profile") }
                scenario.onActivity { model.switchProfile("local") }
                waitFor { !model.busy }
                scenario.onActivity { model.newNote() }
                waitFor { model.editorOpen && !model.busy }
                lateinit var input: EditText
                var ready = false
                waitFor {
                    scenario.onActivity { activity ->
                        editor(activity.window.decorView)?.let {
                            input = it
                            ready = true
                        }
                    }
                    ready
                }
                scenario.onActivity {
                    input.requestFocus()
                    val connection = checkNotNull(input.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
                    connection.setComposingText("# DJHX Memo\n中文输入 English 😀", 1)
                    assertTrue(BaseInputConnection.getComposingSpanStart(input.text) >= 0)
                }
                val buffer = model.content
                val cases = listOf("zh-CN" to "light", "zh-CN" to "dark", "en" to "light", "en" to "dark")
                var lightColor = 0
                cases.forEach { (language, theme) ->
                    scenario.onActivity { activity ->
                        activity.presentation.update(DisplayPreferences(language, theme, "Asia/Shanghai"))
                    }
                    val description = if (language == "en") "Markdown source editor" else "Markdown 源码编辑器"
                    var updated = false
                    waitFor {
                        scenario.onActivity {
                            updated = input.contentDescription == description &&
                                (theme == "light" || lightColor != input.currentTextColor)
                        }
                        updated
                    }
                    scenario.onActivity { activity ->
                        assertSame(input, editor(activity.window.decorView))
                        assertEquals(buffer, model.content)
                        assertTrue(BaseInputConnection.getComposingSpanStart(input.text) >= 0)
                        assertEquals(description, input.contentDescription)
                        if (theme == "light") {
                            lightColor = input.currentTextColor
                        } else {
                            assertNotEquals(lightColor, input.currentTextColor)
                        }
                    }
                    // AndroidView 的 update 在 RenderThread 提交本帧之前完成；截图等待两端绘制稳定。
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    SystemClock.sleep(250)
                    val screenshot =
                        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    val capture = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "djhx-presentation-$language-$theme.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DJHX-Memo-validation")
                    }
                    val destination = checkNotNull(
                        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, capture)
                    )
                    checkNotNull(context.contentResolver.openOutputStream(destination)).use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                assertEquals(DisplayPreferences("en", "dark", "Asia/Shanghai"), PresentationStore(context).preferences)
                scenario.onActivity {
                    checkNotNull(
                        input.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                    ).finishComposingText()
                }
                waitFor { !model.pending }
                scenario.recreate()
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[MemoModel::class.java]
                    assertEquals(buffer, model.content)
                    assertEquals(DisplayPreferences("en", "dark", "Asia/Shanghai"), activity.presentation.preferences)
                }
                scenario.onActivity { model.setDeleted(true) }
                waitFor { !model.editorOpen && !model.busy }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { PresentationStore(context).update(original) }
        }
    }

    @Test fun editedServerAddressIsKeptAlongsideDisplayPreferences() {
        val context = ApplicationProvider.getApplicationContext<MemoApplication>()
        val store = PresentationStore(context)
        val originalServer = store.serverDraft
        val originalPreferences = store.preferences
        try {
            store.serverDraft = "https://alternate.example.test"
            store.update(DisplayPreferences("zh-CN", "dark", "UTC"))
            val reopened = PresentationStore(context)
            assertEquals("https://alternate.example.test", reopened.serverDraft)
            assertEquals(DisplayPreferences("zh-CN", "dark", "UTC"), reopened.preferences)
            val english = context.createConfigurationContext(
                android.content.res.Configuration(
                    context.resources.configuration
                ).apply {
                    setLocale(java.util.Locale.ENGLISH)
                }
            )
            assertEquals(
                "Copied 1 note; the source is preserved.",
                UiText(R.plurals.import_success, listOf(1), 1).resolve(english)
            )
            assertEquals(
                "Copied 2 notes; the source is preserved.",
                UiText(R.plurals.import_success, listOf(2), 2).resolve(english)
            )
            store.serverDraft = ""
            assertEquals("", PresentationStore(context).serverDraft)
        } finally {
            store.serverDraft = originalServer
            store.update(originalPreferences)
        }
    }

    @Test fun openSettingsDialogUpdatesItsLabelsWhenTheLanguageChanges() {
        val context = ApplicationProvider.getApplicationContext<MemoApplication>()
        val original = PresentationStore(context).preferences
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.presentation.update(DisplayPreferences("en", "system", "Asia/Shanghai")) }
                clickText("Account / Settings")
                clickText("Language: English")
                clickText("简体中文")
                waitFor { visibleText("外观: 跟随系统") != null && visibleText("账号与设置") != null }
                clickText("外观: 跟随系统")
                clickText("深色")
                waitFor { visibleText("外观: 深色") != null }
                clickText("语言: 简体中文")
                clickText("English")
                waitFor { visibleText("Appearance: Dark") != null && visibleText("Account and settings") != null }
                clickText("Done")
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { PresentationStore(context).update(original) }
        }
    }
}
