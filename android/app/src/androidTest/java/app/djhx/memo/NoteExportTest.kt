package app.djhx.memo

import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NoteExportTest {
    private val app = ApplicationProvider.getApplicationContext<MemoApplication>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val source = "# 导出😀\n\n```python\nprint('hello')\n```\n- [ ] task\n"
    private fun uri(name: String = UUID.randomUUID().toString() + ".md"): Uri =
        Uri.parse("content://${instrumentation.context.packageName}.note-export-fixture/$name")

    @Test fun snapshotsAreImmutableAndOldResultsCannotConsumeANewRequest() {
        val exports = NoteExportSession()
        val first = checkNotNull(exports.begin(source))
        assertEquals("导出😀.md", first.filename)
        assertNull(exports.begin("second"))
        assertEquals(source, exports.take(first.id)?.text)
        val second = checkNotNull(exports.begin("second"))
        assertNull(exports.take(first.id))
        assertEquals("second", exports.take(second.id)?.text)
        assertNull(exports.take(second.id))
        assertNull(NoteExportSession().take(first.id))
        assertNull(exports.begin("  \n"))
    }

    @Test fun selectedDocumentReceivesExactUtf8AndPermissionFailureIsReported() = runBlocking {
        val destination = uri()
        try {
            writeMarkdown(app, destination, source)
            val bytes = app.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
            assertArrayEquals(source.toByteArray(Charsets.UTF_8), bytes)
            val error = runCatching { writeMarkdown(app, uri("denied.md"), source) }.exceptionOrNull()
            assertTrue(error is SecurityException)
        } finally { app.contentResolver.delete(destination, null, null) }
        Unit
    }

    @Test fun cancelAndWriteFailureNeverEmitSuccessOrConsumeAnotherRequest() {
        var successes = 0
        var failure: Int? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val exports = NoteExportCoordinator(app, scope, { successes++ }, { failure = it })
        try {
            val canceled = checkNotNull(exports.session.begin(source))
            instrumentation.runOnMainSync { exports.complete(canceled.id, null) }
            assertEquals(0, successes)
            val failed = checkNotNull(exports.session.begin(source))
            instrumentation.runOnMainSync { exports.complete(failed.id, uri("denied.md")) }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (exports.writing && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertFalse(exports.writing)
            assertEquals(0, successes)
            assertEquals(R.string.export_permission, failure)
            val current = checkNotNull(exports.session.begin("current"))
            instrumentation.runOnMainSync { exports.complete(canceled.id, uri()) }
            assertEquals("current", exports.session.take(current.id)?.text)
            assertEquals(R.string.export_expired, failure)
            assertEquals(0, successes)
        } finally { scope.cancel() }
    }
}
