package app.djhx.memo

import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.net.URI

/** 纯解析器，不创建 WebView，不加载 HTML、脚本、图片或嵌入内容。 */
object MarkdownSafety {
    private val parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
    fun parse(source: String): Node = parser.parse(source)
    fun safeLink(destination: String): Boolean = runCatching {
        val uri = URI(destination)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun taskLine(item: ListItem, source: List<String>): Pair<Int, Boolean>? {
        val span = item.sourceSpans.firstOrNull() ?: return null
        val line = span.lineIndex
        val text = source.getOrNull(line)?.drop(span.columnIndex) ?: return null
        val marker = Regex("^\\s*(?:[-+*]|[0-9]+[.)])\\s+\\[([ xX])\\](?:\\s|$)").find(text) ?: return null
        return line to (marker.groupValues[1] != " ")
    }
}
