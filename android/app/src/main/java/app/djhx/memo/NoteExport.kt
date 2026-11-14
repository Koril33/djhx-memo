package app.djhx.memo

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.djhx_memo_core.markdownExportFilename
import java.util.UUID

internal data class NoteExportSnapshot(val id: String, val filename: String, val text: String)

/** One immutable picker request; a stale result cannot consume a subsequent note. */
internal class NoteExportSession {
    private var pending: NoteExportSnapshot? = null
    fun begin(text: String): NoteExportSnapshot? {
        if (pending != null || text.isBlank()) return null
        return NoteExportSnapshot(
            UUID.randomUUID().toString(),
            markdownExportFilename(text),
            text
        ).also { pending = it }
    }
    fun peek(id: String): NoteExportSnapshot? = pending?.takeIf { it.id == id }
    fun take(id: String): NoteExportSnapshot? = peek(id)?.also { pending = null }
}

/** The caller keys this composition by request ID, giving each attempt a distinct result key. */
@Composable
internal fun NoteExportPicker(id: String, model: MemoModel, finished: (String) -> Unit) {
    var launched by rememberSaveable(id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        model.completeNoteExport(id, uri)
        finished(id)
    }
    LaunchedEffect(id) {
        val snapshot = model.peekNoteExport(id)
        if (snapshot == null) {
            model.reportText(R.string.export_expired)
            finished(id)
        } else if (!launched) {
            launched = true
            try {
                picker.launch(snapshot.filename)
            } catch (_: android.content.ActivityNotFoundException) {
                model.takeNoteExport(id)
                model.reportText(R.string.export_missing)
                finished(id)
            } catch (_: SecurityException) {
                model.takeNoteExport(id)
                model.reportText(R.string.export_permission)
                finished(id)
            }
        }
    }
}

/** Writes the captured UTF-8 bytes, not a fresh lookup of the current editor after the picker. */
internal suspend fun writeMarkdown(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("EXPORT_FAILED")
    stream.use {
        it.write(text.toByteArray(Charsets.UTF_8))
        it.flush()
    }
}

/** File writes outlive Activity recreation, but are canceled with their owning ViewModel. */
@Suppress("TooGenericExceptionCaught") // Fixed UI codes only; never expose provider diagnostics.
internal class NoteExportCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val success: () -> Unit,
    private val failed: (Int) -> Unit
) {
    val session = NoteExportSession()
    var writing by mutableStateOf(false)
        private set
    fun complete(id: String, uri: Uri?) {
        val snapshot = session.take(id)
        if (uri == null) return
        if (snapshot == null) {
            failed(R.string.export_expired)
            return
        }
        writing = true
        scope.launch {
            try {
                writeMarkdown(context, uri, snapshot.text)
                success()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: SecurityException) {
                failed(R.string.export_permission)
            } catch (_: java.io.IOException) {
                failed(R.string.export_io)
            } catch (_: Exception) {
                failed(R.string.export_incomplete)
            } finally { writing = false }
        }
    }
}

internal fun MemoModel.beginNoteExport(): NoteExportSnapshot? {
    if (busy || noteExport.writing || updateLocked) return null
    if (!editorOpen || selected?.optString("purge_state") in setOf("pending", "purged")) return null
    if (composingText) {
        reportText(R.string.finish_composition)
        return null
    }
    return noteExport.session.begin(content)
}
internal fun MemoModel.peekNoteExport(id: String): NoteExportSnapshot? = noteExport.session.peek(id)
internal fun MemoModel.takeNoteExport(id: String): NoteExportSnapshot? = noteExport.session.take(id)
internal fun MemoModel.completeNoteExport(id: String, uri: Uri?) = noteExport.complete(id, uri)
