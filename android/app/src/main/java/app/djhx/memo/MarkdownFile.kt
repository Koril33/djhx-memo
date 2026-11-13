package app.djhx.memo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Bounded platform read; content encoding and note creation remain in core. */
internal suspend fun readMarkdownFile(
    context: Context,
    uri: Uri
): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
    val filename = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else ""
    }.orEmpty()
    val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, 2 * 1_048_576 + 4 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            if (output.size() > 2 * 1_048_576 + 3) throw CoreFailure("NOTE_TOO_LARGE", "")
        }
        output.toByteArray()
    }
    filename to bytes
}
