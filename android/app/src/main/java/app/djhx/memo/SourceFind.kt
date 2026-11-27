package app.djhx.memo

import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.widget.EditText
import android.widget.ScrollView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 在已排序且互不重叠的命中列表里，二分定位与 [visible]（两端含）相交的闭合索引区间；
 * 无重叠返回 [IntRange.EMPTY]。用于大笔记只给可见区域上色而不逐条遍历全量命中。
 */
internal fun visibleMatchIndices(matches: List<IntRange>, visible: IntRange): IntRange {
    if (matches.isEmpty() || visible.isEmpty()) return IntRange.EMPTY
    var first = 0
    var high = matches.size
    while (first < high) {
        val mid = (first + high) ushr 1
        if (matches[mid].last < visible.first) first = mid + 1 else high = mid
    }
    if (first >= matches.size || matches[first].first > visible.last) return IntRange.EMPTY
    var end = first
    high = matches.size
    while (end < high) {
        val mid = (end + high) ushr 1
        if (matches[mid].first <= visible.last) end = mid + 1 else high = mid
    }
    return first until end
}

/** 命中区间与可见区间求交；无重叠返回 null。 */
internal fun clippedRange(range: IntRange, visible: IntRange): IntRange? {
    val start = maxOf(range.first, visible.first)
    val end = minOf(range.last, visible.last)
    return if (start <= end) start..end else null
}

/**
 * Drives the in-note find bar against a native EditText inside a ScrollView: keeps the full
 * literal match list and current index, and scrolls the selection into view. The find bar is
 * pure front-end document search; it never touches core/FTS5.
 *
 * Small notes keep the synchronous full-text scan and every-match span. Large notes (over
 * [LARGE_NOTE_UTF8_BYTES] UTF-8) compute the complete match list on a background dispatcher,
 * expose a non-blocking [computing] status, and only paint the visible region; the full count,
 * next/previous navigation and locate are unchanged. A monotonic version plus a snapshot
 * comparison rejects late results after the buffer, query or editor text changed.
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
    var computing by mutableStateOf(false)
        private set
    val total: Int get() = matches.size

    private var version = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        viewport.setOnScrollChangeListener { _, _, _, _, _ -> repaintVisible() }
    }

    /** Recomputes matches and jumps to the first hit; an empty query clears highlights. */
    fun search(value: String) {
        query = value
        recompute(jumpToFirst = true)
    }

    /** Re-runs the current query after the editor content changed; resets the current index. */
    fun refresh() {
        recompute(jumpToFirst = false)
    }

    fun next() { if (matches.isNotEmpty()) select((current + 1).mod(matches.size)) }

    fun previous() { if (matches.isNotEmpty()) select((current - 1 + matches.size).mod(matches.size)) }

    /** Removes all find highlights and resets state; also used when the find bar closes. */
    fun clear() {
        version++
        query = ""
        matches = emptyList()
        current = -1
        computing = false
        removeHighlights()
    }

    /** Cancels any in-flight computation; called when the host is no longer needed. */
    fun close() {
        version++
        scope.cancel()
    }

    /** Applies a new highlight color (e.g. after a theme change) and re-paints existing spans. */
    fun updateHighlightColor(color: Int) {
        if (highlightColor == color) return
        highlightColor = color
        version++
        if (query.isEmpty()) return
        removeHighlights()
        if (isLargeNote(editor.text)) applyVisibleHighlights(matches) else applyAllHighlights(matches)
    }

    private fun recompute(jumpToFirst: Boolean) {
        val snapshot = editor.text.toString()
        val localQuery = query
        removeHighlights()
        if (localQuery.isEmpty()) {
            version++
            matches = emptyList()
            current = -1
            computing = false
            return
        }
        if (!isLargeNote(snapshot)) {
            version++
            val found = findLiteralMatches(snapshot, localQuery)
            matches = found
            computing = false
            applyAllHighlights(found)
            if (jumpToFirst && found.isNotEmpty()) select(0) else current = -1
            return
        }
        val requestVersion = ++version
        computing = true
        scope.launch {
            val found = findLiteralMatches(snapshot, localQuery)
            withContext(Dispatchers.Main) {
                if (requestVersion != version || query != localQuery) return@withContext
                if (editor.text.toString() != snapshot) return@withContext
                matches = found
                computing = false
                applyVisibleHighlights(found)
                if (jumpToFirst && found.isNotEmpty()) select(0) else current = -1
            }
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
            repaintVisible()
        }
    }

    /** 目录跳转：只滚动到指定字符偏移所在逻辑行，不移动选区/光标。 */
    fun scrollToOffset(offset: Int) {
        editor.post {
            val layout = editor.layout ?: return@post
            val line = layout.getLineForOffset(offset.coerceIn(0, editor.text.length))
            viewport.scrollTo(0, (layout.getLineTop(line) + editor.paddingTop).coerceAtLeast(0))
            repaintVisible()
        }
    }

    private fun applyAllHighlights(found: List<IntRange>) {
        found.forEach { range ->
            editor.text.setSpan(
                FindHighlight(highlightColor),
                range.first,
                range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** Paints only matches intersecting the current viewport; called for large notes. */
    private fun applyVisibleHighlights(found: List<IntRange>) {
        val visible = visibleRange() ?: return
        val indices = visibleMatchIndices(found, visible)
        for (index in indices) {
            val clipped = clippedRange(found[index], visible) ?: continue
            editor.text.setSpan(
                FindHighlight(highlightColor),
                clipped.first,
                clipped.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** Re-paints visible matches after scrolling; no-op for small notes (already fully painted). */
    private fun repaintVisible() {
        if (!isLargeNote(editor.text)) return
        if (query.isEmpty() || matches.isEmpty()) return
        removeHighlights()
        applyVisibleHighlights(matches)
    }

    /** Text offsets of the currently visible region, derived from the viewport scroll position. */
    private fun visibleRange(): IntRange? {
        val layout = editor.layout ?: return null
        val height = viewport.height
        if (height <= 0) return null
        val top = layout.getLineForVertical(viewport.scrollY)
        val bottom = layout.getLineForVertical(viewport.scrollY + height)
        if (top < 0 || bottom < top) return null
        val start = layout.getLineStart(top)
        val end = layout.getLineEnd(bottom)
        if (start > end) return null
        return start..end
    }

    private fun removeHighlights() {
        editor.text.getSpans(0, editor.text.length, FindHighlight::class.java).forEach {
            editor.text.removeSpan(it)
        }
    }
}
