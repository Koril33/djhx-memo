package app.djhx.memo

/**
 * 一次真实回车在列表项末尾的局部编辑计划（UTF-16 区间）。
 * 应用方式为用 [text] 替换 [replaceStart, replaceEnd)，再把光标移动到 [cursor]。
 * 返回 null 表示不做自动续写，按原生换行处理。
 */
internal data class ListEnterEdit(
    val replaceStart: Int,
    val replaceEnd: Int,
    val text: String,
    val cursor: Int,
)

/**
 * 计算基础 Markdown 列表回车续写的局部编辑计划。
 *
 * 仅当光标为折叠选区且位于某个逻辑行末尾时才有机会返回计划；行中或非折叠选区
 * 由调用方（SourceEditor 输入适配）在进入前拦截，这里也按“行中”直接返回 null。
 *
 * @param content 当前正文快照（行尾已规范为 \n，由 model.edit 归一化）
 * @param cursor 折叠光标的 UTF-16 偏移，必须落在某个逻辑行末尾
 */
internal fun planListEnter(content: String, cursor: Int): ListEnterEdit? {
    if (cursor < 0 || cursor > content.length) return null
    if (cursor < content.length && content[cursor] != '\n') return null
    val lineStart = content.substring(0, cursor).lastIndexOf('\n') + 1
    val line = content.substring(lineStart, cursor)
    if (insideFencedCode(content, lineStart)) return null
    val plan = planLineEnter(line) ?: return null
    return ListEnterEdit(
        replaceStart = lineStart + plan.replaceStart,
        replaceEnd = lineStart + plan.replaceEnd,
        text = plan.text,
        cursor = lineStart + plan.cursor,
    )
}

/** 相对逻辑行的局部编辑计划，偏移均为 0..line.length 的 UTF-16 值。 */
private data class LineEdit(val replaceStart: Int, val replaceEnd: Int, val text: String, val cursor: Int)

/** 识别当前逻辑行末尾回车的列表续写；无法可靠判定时返回 null（原生换行）。 */
private fun planLineEnter(line: String): LineEdit? {
    var indent = 0
    while (indent < line.length && line[indent] == ' ') indent++
    // 四空格即顶层缩进代码块；Tab 混排缩进无法可靠判定，均不续写。
    if (indent > 3) return null
    if (indent < line.length && line[indent] == '\t') return null
    // 主题分隔线（3 个以上相同 - * _，允许空格分隔）不作为列表项。
    if (isThematicBreak(line.substring(indent))) return null
    val marker = if (indent < line.length) line[indent] else '\u0000'
    if (marker == '-' || marker == '+' || marker == '*') return planUnordered(line, indent, marker)
    if (isAsciiDigit(marker)) return planOrdered(line, indent)
    return null
}

private fun planUnordered(line: String, indent: Int, marker: Char): LineEdit? {
    val paddingStart = indent + 1
    if (paddingStart >= line.length || !isMarkerPadding(line[paddingStart])) return null
    val content = contentAfterPadding(line, paddingStart)
    if (content.isEmpty()) return LineEdit(indent, line.length, "", indent)
    if (isTaskMarker(content)) return null
    val inserted = "\n" + " ".repeat(indent) + marker + " "
    return LineEdit(line.length, line.length, inserted, line.length + inserted.length)
}

private fun planOrdered(line: String, indent: Int): LineEdit? {
    var end = indent
    while (end < line.length && isAsciiDigit(line[end])) end++
    val digits = line.substring(indent, end)
    if (digits.isEmpty() || digits.length > 9) return null
    if (end >= line.length || line[end] != '.') return null
    val paddingStart = end + 1
    if (paddingStart >= line.length || !isMarkerPadding(line[paddingStart])) return null
    val number = digits.toLongOrNull() ?: return null
    val content = contentAfterPadding(line, paddingStart)
    if (content.isEmpty()) return LineEdit(indent, line.length, "", indent)
    val next = number + 1
    // 递增结果超过 CommonMark 9 位上限时回退普通换行。
    if (next.toString().length > 9) return null
    val inserted = "\n" + " ".repeat(indent) + next.toString() + ". "
    return LineEdit(line.length, line.length, inserted, line.length + inserted.length)
}

/** 列表标记后的分隔空白与正文；空白仅认可空格或 Tab。 */
private fun contentAfterPadding(line: String, start: Int): String {
    var i = start
    while (i < line.length && isMarkerPadding(line[i])) i++
    return line.substring(i)
}

private fun isMarkerPadding(c: Char): Boolean = c == ' ' || c == '\t'

private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

/** `- [ ]` / `- [x]` / `- [X]` 任务项，不作为普通列表项续写。 */
private fun isTaskMarker(content: String): Boolean =
    content.length >= 3 && content[0] == '[' && content[2] == ']' &&
        (content[1] == ' ' || content[1] == 'x' || content[1] == 'X')

private fun isThematicBreak(text: String): Boolean {
    val compact = text.filterNot { it.isWhitespace() }
    return compact.length >= 3 && compact[0] in "-*_" && compact.all { it == compact[0] }
}

/** 判断光标所在行是否处于未闭合的围栏代码块内（仅扫描光标之前的行）。 */
private fun insideFencedCode(content: String, lineStart: Int): Boolean {
    if (lineStart <= 0 || !hasFenceMarker(content, lineStart)) return false
    var fenceChar = '\u0000'
    var fenceLength = 0
    var start = 0
    while (start < lineStart) {
        var end = content.indexOf('\n', start)
        if (end < 0) end = content.length
        val marker = fenceMarker(content.substring(start, end))
        if (marker != null) {
            if (fenceChar == '\u0000') {
                fenceChar = marker.char
                fenceLength = marker.length
            } else if (marker.char == fenceChar && marker.canClose && marker.length >= fenceLength) {
                fenceChar = '\u0000'
                fenceLength = 0
            }
        }
        start = end + 1
    }
    return fenceChar != '\u0000'
}

private fun hasFenceMarker(content: String, end: Int): Boolean {
    for (i in 0 until end) {
        val c = content[i]
        if (c == '`' || c == '~') return true
    }
    return false
}

private data class FenceMarker(val char: Char, val length: Int, val canClose: Boolean)

private fun fenceMarker(line: String): FenceMarker? {
    var i = 0
    while (i < line.length && i < 3 && line[i] == ' ') i++
    if (i >= line.length) return null
    val c = line[i]
    if (c != '`' && c != '~') return null
    var run = 0
    while (i < line.length && line[i] == c) { run++; i++ }
    if (run < 3) return null
    val rest = line.substring(i)
    val hasInfo = rest.any { !it.isWhitespace() }
    if (hasInfo && c == '`' && rest.contains('`')) return null
    return FenceMarker(c, run, canClose = !hasInfo)
}
