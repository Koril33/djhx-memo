package app.djhx.memo

import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ListActionsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = ApplicationProvider.getApplicationContext<MemoApplication>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("list action did not settle", condition())
    }
    private fun fixture(bridge: CoreBridge, exercise: (MemoModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MemoModel
        main {
            model = MemoModel(application, bridge)
            store.put("fixture", model)
        }
        try {
            waitFor { model.state.optJSONObject("profile") != null }
            exercise(model)
        } finally { main { store.clear() } }
    }

    @Test fun invalidFileCanBeReplacedAndRepeatedCallbackDoesNotDuplicate() = fixture(application.gateway) { model ->
        lateinit var invalid: String
        main {
            invalid = model.beginMarkdownImport()!!
            model.completeMarkdownImport(invalid, "invalid.md", byteArrayOf(-1))
        }
        waitFor { !model.busy && model.message != null }
        assertEquals(R.string.import_invalid, model.message!!.resource)
        lateinit var valid: String
        main {
            model.dismissMessage()
            valid = model.beginMarkdownImport()!!
        }
        assertNotEquals(invalid, valid)
        val content = "# Imported 中文😀\r\n```kotlin\r\nval x = 1\r\n```"
        main {
            model.completeMarkdownImport(valid, "valid.Md", ("\uFEFF" + content).toByteArray())
            model.completeMarkdownImport(valid, "valid.Md", ("\uFEFF" + content).toByteArray())
        }
        waitFor { !model.busy && model.editorOpen }
        assertEquals(content.replace("\r\n", "\n"), model.content)
        val id = model.selected!!.getString("id")
        main { model.completeMarkdownImport(valid, "valid.Md", content.toByteArray()) }
        assertEquals(id, model.selected!!.getString("id"))
        main { model.setDeleted(true) }
        waitFor { !model.busy && !model.editorOpen }
    }

    private class PickerBridge : CoreBridge {
        var profile = "a"
        val imported = mutableListOf<String>()
        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any {
            val args = fields.toMap()
            return when (action) {
                "state" -> JSONObject().put(
                    "profile",
                    JSONObject().put("id", profile).put("read_only", false)
                ).put("event_seq", profile)
                "list" -> JSONArray()
                "switch" -> {
                    profile = args["profile_id"].toString()
                    JSONObject()
                }
                "import_markdown" -> {
                    imported += args["request_id"].toString()
                    JSONObject().put(
                        "id",
                        "imported"
                    ).put("generation", "1").put("content", "new file").put("deleted", false)
                }
                else -> error("unexpected picker fixture action")
            }
        }
    }

    @Test fun oldPickerReadAndCancelCannotRetargetNewProfileRequest() {
        val bridge = PickerBridge()
        fixture(bridge) { model ->
            lateinit var old: String
            main {
                old = model.beginMarkdownImport()!!
                model.switchProfile("b")
            }
            waitFor { !model.busy && model.state.getJSONObject("profile").getString("id") == "b" }
            lateinit var current: String
            main {
                current = model.beginMarkdownImport()!!
                model.completeMarkdownImport(old, "old.md", "old file".toByteArray())
                model.cancelMarkdownImport(old)
            }
            assertTrue(bridge.imported.isEmpty())
            main { model.completeMarkdownImport(current, "new.md", "new file".toByteArray()) }
            waitFor { !model.busy && model.selected != null }
            assertEquals(listOf(current), bridge.imported)
        }
    }

    @Test fun selectionIsBoundToSearchResultsAndCommittedTrashCanBeUndone() = runBlocking {
        val prefix = "list-fixture-" + UUID.randomUUID()
        val notes = (1..2).map {
            application.gateway.obj(
                "create",
                "profile_id" to "local",
                "request_id" to UUID.randomUUID().toString(),
                "content" to "$prefix-$it"
            )
        }
        fixture(application.gateway) { model ->
            main { model.search(prefix) }
            waitFor { model.notes.size == 2 }
            main {
                model.toggleSelection(notes.first().getString("id"))
                model.selectAll()
            }
            assertEquals(2, model.selection.size)
            main { model.search(prefix + "-1") }
            waitFor { model.notes.size == 1 }
            assertFalse(model.selecting)
            main {
                model.trashNotes(notes)
                model.undoTrash()
            }
            // The first call has not returned yet: undo is unavailable until it commits.
            waitFor { !model.busy && model.trashUndo != null }
            main { model.undoTrash() }
            waitFor { !model.busy && model.trashUndo == null && model.notes.size == 1 }
        }
        notes.forEach { original ->
            val note = application.gateway.obj("get", "profile_id" to "local", "id" to original.getString("id"))
            assertFalse(note.getBoolean("deleted"))
            application.gateway.obj(
                "set_deleted",
                "id" to note.getString("id"),
                "expected_generation" to note.getString("generation"),
                "deleted" to true
            )
        }
    }

    private class ImportReplayBridge(private val delegate: CoreBridge) : CoreBridge {
        val requestIds = mutableListOf<String>()
        val savedIds = mutableListOf<String>()
        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any {
            val result = delegate.call(action, *fields)
            if (action == "import_markdown") {
                requestIds += fields.toMap()["request_id"].toString()
                savedIds += (result as JSONObject).getString("id")
                if (requestIds.size == 1) throw CoreFailure("LOCAL_STORAGE_FAILED", "")
            }
            return result
        }
    }

    @Test fun importWithLostReceiptRetriesTheSameRequestAndCreatesOneNote() {
        val bridge = ImportReplayBridge(application.gateway)
        fixture(bridge) { model ->
            lateinit var token: String
            main {
                token = model.beginMarkdownImport()!!
                model.completeMarkdownImport(token, "receipt.md", "import receipt fixture".toByteArray())
            }
            waitFor { !model.busy && model.message != null }
            assertNull(model.selected)
            main {
                model.dismissMessage()
                assertNull(model.beginMarkdownImport())
            }
            waitFor { !model.busy && model.selected != null }
            assertEquals(listOf(token, token), bridge.requestIds)
            assertEquals(1, bridge.savedIds.distinct().size)
            assertEquals(bridge.savedIds.first(), model.selected!!.getString("id"))
            main { model.setDeleted(true) }
            waitFor { !model.busy && !model.editorOpen }
        }
    }

    private class SlowBatchBridge : CoreBridge {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var deletes = 0
        val restored = mutableListOf<String>()
        private fun note(id: String, generation: String = "1", deleted: Boolean = false) = JSONObject()
            .put("id", id).put("generation", generation).put("deleted", deleted).put("content", id)
        override suspend fun call(action: String, vararg fields: Pair<String, Any?>): Any {
            val args = fields.toMap()
            return when (action) {
                "state" -> JSONObject().put(
                    "profile",
                    JSONObject().put("id", "local").put("read_only", false)
                ).put("event_seq", "1")
                "list" -> JSONArray()
                "batch_set_deleted" -> {
                    val deleted = args["deleted"] == true
                    if (deleted && ++deletes == 2) {
                        started.complete(Unit)
                        release.await()
                    }
                    val results = (args["items"] as JSONArray).objects().map {
                        val id = it.getString("id")
                        if (!deleted) restored += id
                        JSONObject().put("id", id).put("ok", true).put("note", note(id, "2", deleted))
                    }
                    JSONObject().put("results", JSONArray(results))
                }
                else -> error("unexpected batch fixture action")
            }
        }
        fun target(id: String) = note(id)
    }

    @Test fun undoWhileAnotherDeletionIsBusyRestoresTheCapturedGroup() {
        val bridge = SlowBatchBridge()
        fixture(bridge) { model ->
            main { model.trashNotes(listOf(bridge.target("first")), mergeUndo = true) }
            waitFor { !model.busy && model.trashUndo != null }
            main { model.trashNotes(listOf(bridge.target("second")), mergeUndo = true) }
            waitFor { bridge.started.isCompleted }
            main { model.undoTrash() }
            assertNull(model.trashUndo)
            bridge.release.complete(Unit)
            waitFor { !model.busy && bridge.restored.isNotEmpty() }
            assertEquals(listOf("first"), bridge.restored)
            assertEquals(listOf("second"), model.trashUndo!!.notes.map { it.getString("id") })
        }
    }

    @Test fun swipeThresholdRequiresHalfWidthRegardlessOfVelocity() {
        assertFalse(swipeCommits(-49f, 100f))
        assertTrue(swipeCommits(-50f, 100f))
        assertFalse(swipeCommits(70f, 100f))
        assertFalse(swipeCommits(-5f, 0f))
    }
}
