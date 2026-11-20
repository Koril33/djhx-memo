package app.djhx.memo

import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.widget.EditText
import android.widget.ScrollView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ASCII-only case folding preserves UTF-16 code-unit length, matching core's literal search
 * semantics (`to_ascii_lowercase`). Non-ASCII code points are left untouched.
 */
internal fun asciiLowercase(value: String): String {
    val chars = value.toCharArray()
    for (index in chars.indices) {
        val char = chars[index]
        if (char in 'A'..'Z') chars[index] = (char.code + 32).toChar()
    }
    return String(chars)
}

/**
 * Non-overlapping literal substring matches as UTF-16 ranges into [content]. ASCII is
 * case-insensitive; other Unicode matches exactly (the v1 contract shared with core).
 * An empty query or a query longer than the content produces no matches.
 */
internal fun findLiteralMatches(content: String, query: String): List<IntRange> {
    if (query.isEmpty() || query.length > content.length) return emptyList()
    val foldedContent = asciiLowercase(content)
    val foldedQuery = asciiLowercase(query)
    val ranges = mutableListOf<IntRange>()
    var cursor = 0
    while (cursor <= content.length) {
        val offset = foldedContent.indexOf(foldedQuery, cursor)
        if (offset < 0) break
        ranges.add(offset until offset + foldedQuery.length)
        cursor = offset + foldedQuery.length
    }
    return ranges
}

/** Background highlight for every literal match. */
internal class FindHighlight(color: Int) : BackgroundColorSpan(color)

/**
 * Drives the in-note find bar against a native EditText inside a ScrollView: highlights every
 * match, keeps the current index, and scrolls the selection into view. The find bar is pure
 * front-end document search; it never touches core/FTS5.
 */
internal class SourceFindHost(
    private val editor: EditText,
    private val viewport: ScrollView,
    private var highlightColor: Int
) {
    var query by mutableStateOf("")
        private set
    var matches by mutableStateOf<List<IntRange>>(emptyList())
        private set
    var current by mutableStateOf(-1)
        private set
    val total: Int get() = matches.size

    /** Recomputes matches and jumps to the first hit; an empty query clears highlights. */
    fun search(value: String) {
        query = value
        recompute()
        if (matches.isNotEmpty()) select(0) else current = -1
    }

    /** Re-runs the current query after the editor content changed; resets the current index. */
    fun refresh() {
        recompute()
        current = -1
    }

    fun next() { if (matches.isNotEmpty()) select((current + 1).mod(matches.size)) }

    fun previous() { if (matches.isNotEmpty()) select((current - 1 + matches.size).mod(matches.size)) }

    /** Removes all find highlights and resets state; also used when the find bar closes. */
    fun clear() {
        query = ""
        matches = emptyList()
        current = -1
        removeHighlights()
    }

    /** Applies a new highlight color (e.g. after a theme change) and re-paints existing spans. */
    fun updateHighlightColor(color: Int) {
        if (highlightColor == color) return
        highlightColor = color
        recompute()
    }

    private fun recompute() {
        removeHighlights()
        val text = editor.text.toString()
        matches = if (query.isEmpty()) emptyList() else findLiteralMatches(text, query)
        matches.forEach { range ->
            editor.text.setSpan(
                FindHighlight(highlightColor),
                range.first,
                range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun select(index: Int) {
        current = index
        val range = matches[index]
        android.text.Selection.setSelection(editor.text, range.first, range.last + 1)
        // Defer until the editor is laid out; the EditText does not scroll internally.
        editor.post {
            val layout = editor.layout ?: return@post
            val line = layout.getLineForOffset(range.first)
            viewport.scrollTo(0, (layout.getLineTop(line) + editor.paddingTop).coerceAtLeast(0))
        }
    }

    private fun removeHighlights() {
        editor.text.getSpans(0, editor.text.length, FindHighlight::class.java).forEach {
            editor.text.removeSpan(it)
        }
    }
}
