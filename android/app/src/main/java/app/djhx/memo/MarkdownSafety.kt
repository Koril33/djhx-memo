package app.djhx.memo

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.net.URI

/** 标题目录条目；line 为 LF 归一化后的 0 基逻辑行号。 */
data class OutlineHeading(val level: Int, val label: String, val line: Int)

/** 纯解析器，不创建 WebView，不加载 HTML、脚本、图片或嵌入内容。 */
object MarkdownSafety {
    private val extensions = listOf(StrikethroughExtension.create())
    private val parser = Parser.builder()
        .extensions(extensions)
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

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

    /**
     * 提取顶层 ATX/setext 标题（共享识别范围）。只遍历顶层节点，引用/列表内的标题自然被排除；
     * 行尾先归一化，保证 CRLF/CR 下偏移稳定。标签取纯文本、去行内标记与 HTML 实体。
     */
    fun headings(source: String): List<OutlineHeading> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val root = parser.parse(normalized)
        val result = mutableListOf<OutlineHeading>()
        var node = root.firstChild
        while (node != null) {
            if (node is Heading) {
                val line = node.sourceSpans.firstOrNull()?.lineIndex ?: 0
                result.add(OutlineHeading(node.level, plainText(node), line))
            }
            node = node.next
        }
        return result
    }

    private fun plainText(node: Node): String {
        val out = StringBuilder()
        appendPlainText(node, out)
        return out.toString()
    }

    private fun appendPlainText(node: Node, out: StringBuilder) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> out.append(child.literal)
                is Code -> out.append(child.literal)
                is SoftLineBreak, is HardLineBreak -> out.append(' ')
                else -> appendPlainText(child, out)
            }
            child = child.next
        }
    }
}
