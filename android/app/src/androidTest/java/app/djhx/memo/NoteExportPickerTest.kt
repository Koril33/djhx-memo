package app.djhx.memo

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in native SAF check. The host also verifies the synthetic exported file's exact bytes. */
class NoteExportPickerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<MemoApplication>()
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("Native export state did not settle", condition())
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun click(label: String) {
        waitFor {
            find { it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString() == label } != null
        }
        var node =
            checkNotNull(
                find { it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString() == label }
            )
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun systemPickerKeepsTheClickedSnapshotAcrossRecreationAndNoteSwitch() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeNoteExport") == "1")
        val title = "memo-export-" + UUID.randomUUID()
        val source = "# $title\n\n中文与 emoji 😀\n\n```python\nprint('hello')\n```\n- [ ] task\n"
        val ids = listOf(source, "Other export fixture").map {
            app.gateway.obj(
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to it
            )
                .getString("id")
        }
        val old = PresentationStore(app).preferences
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: MemoModel
                scenario.onActivity {
                    model = ViewModelProvider(it)[MemoModel::class.java]
                    it.presentation.update(DisplayPreferences("en", "light", "Asia/Shanghai"))
                }
                waitFor { model.state.has("profile") }
                scenario.onActivity { model.switchProfile("local") }
                waitFor { !model.busy && model.notes.any { it.optString("id") == ids.first() } }
                scenario.onActivity { model.open(model.notes.first { it.getString("id") == ids.first() }) }
                waitFor { !model.busy && model.content == source }
                click("More")
                click("Export current note")
                waitFor {
                    find {
                        it.packageName?.toString() in setOf("com.google.android.documentsui", "com.android.documentsui")
                    } != null
                }
                assertNotNull(find { it.text?.toString() == "$title.md" })
                val originalModel = model
                // ActivityScenario.recreate() first forces RESUMED, which an external picker prevents.
                // Recreate the stopped Activity directly, as the OS can do while SAF owns the screen.
                var originalActivity: MainActivity? = null
                scenario.onActivity {
                    originalActivity = it
                    it.recreate()
                }
                waitFor {
                    var replaced = false
                    scenario.onActivity { replaced = it !== originalActivity }
                    replaced
                }
                scenario.onActivity {
                    model = ViewModelProvider(it)[MemoModel::class.java]
                    assertSame(originalModel, model)
                    model.open(model.notes.first { note -> note.getString("id") == ids.last() })
                }
                waitFor { !model.busy && model.content == "Other export fixture" }
                click("Save")
                try {
                    waitFor { model.actionNotice?.resource == R.string.note_export_success }
                } catch (error: AssertionError) {
                    instrumentation.sendStatus(
                        2,
                        Bundle().apply {
                            putString("export_filename", "$title.md")
                            putString("export_message", model.message?.resource?.toString())
                            putString("export_expected", R.string.note_export_success.toString())
                            putString("export_writing", model.noteExport.writing.toString())
                        }
                    )
                    throw error
                }
                val receipt = Bundle().apply {
                    putString("export_filename", "$title.md")
                    putString(
                        "export_sha256",
                        java.security.MessageDigest.getInstance(
                            "SHA-256"
                        ).digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
                    )
                }
                instrumentation.sendStatus(2, receipt)
            }
        } finally {
            PresentationStore(app).update(old)
            ids.forEach { id ->
                val note = app.gateway.obj("get", "profile_id" to "local", "id" to id)
                if (!note.optBoolean("deleted")) {
                    app.gateway.obj(
                        "set_deleted",
                        "profile_id" to "local",
                        "id" to id,
                        "expected_generation" to note.getString("generation"),
                        "deleted" to true
                    )
                }
            }
        }
    }
}
