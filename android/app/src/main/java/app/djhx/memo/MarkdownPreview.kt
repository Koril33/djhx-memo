package app.djhx.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.commonmark.node.*

@Composable
fun MarkdownPreview(
    source: String,
    editable: Boolean,
    modifier: Modifier = Modifier,
    onTask: (Int) -> Unit,
    onLink: (String) -> Unit
) {
    val tree = remember(source) { MarkdownSafety.parse(source) }
    val lines = remember(source) { source.lines() }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MarkdownChildren(tree, lines, editable, onTask, onLink)
    }
}

private fun Node.children(): List<Node> = buildList {
    var node = firstChild
    while (node != null) {
        add(node)
        node = node.next
    }
}

@Composable
private fun MarkdownChildren(
    node: Node,
    lines: List<String>,
    editable: Boolean,
    onTask: (Int) -> Unit,
    onLink: (String) -> Unit
) {
    node.children().forEach { block -> MarkdownBlock(block, lines, editable, onTask, onLink) }
}

@Composable
private fun MarkdownBlock(
    block: Node,
    lines: List<String>,
    editable: Boolean,
    onTask: (Int) -> Unit,
    onLink: (String) -> Unit
) {
    when (block) {
        is Heading -> MarkdownText(block, onLink, heading = block.level)
        is Paragraph -> MarkdownText(block, onLink)
        is FencedCodeBlock -> CodeBlock(block.literal)
        is IndentedCodeBlock -> CodeBlock(block.literal)
        is HtmlBlock -> Text(block.literal, fontFamily = FontFamily.Monospace)
        is ThematicBreak -> HorizontalDivider()
        is BlockQuote -> Column(
            Modifier.padding(start = 12.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
        ) {
            MarkdownChildren(block, lines, editable, onTask, onLink)
        }
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.children().filterIsInstance<ListItem>().forEachIndexed { index, item ->
                val task = MarkdownSafety.taskLine(item, lines)
                Row {
                    if (task != null) {
                        val taskLabel = stringResource(R.string.task_accessibility, task.first + 1)
                        Checkbox(
                            modifier = Modifier.semantics { contentDescription = taskLabel },
                            checked = task.second,
                            enabled = editable,
                            onCheckedChange = { onTask(task.first) }
                        )
                    } else {
                        Text(
                            if (block is OrderedList) "${(block.markerStartNumber ?: 1) + index}. " else "• ",
                            Modifier.width(32.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        item.children().forEachIndexed { childIndex, child ->
                            if (child is Paragraph) {
                                MarkdownText(
                                    child,
                                    onLink,
                                    dropTask = task != null && childIndex == 0
                                )
                            } else {
                                MarkdownBlock(child, lines, editable, onTask, onLink)
                            }
                        }
                    }
                }
            }
        }
        else -> MarkdownChildren(block, lines, editable, onTask, onLink)
    }
}

@Composable
private fun CodeBlock(code: String) {
    Text(
        code.removeSuffix("\n"),
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun MarkdownText(node: Node, onLink: (String) -> Unit, heading: Int? = null, dropTask: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val imageTemplate = stringResource(R.string.image_not_loaded)
    var annotated = remember(node, colors, onLink, imageTemplate) {
        buildAnnotatedString {
            appendInline(
                node,
                colors.primary,
                colors.surfaceVariant,
                colors.onSurfaceVariant,
                onLink,
                imageTemplate
            )
        }
    }
    if (dropTask && Regex("^\\[[ xX]\\]\\s*").containsMatchIn(annotated.text)) {
        val length = Regex("^\\[[ xX]\\]\\s*").find(annotated.text)!!.value.length
        annotated = annotated.subSequence(length, annotated.length)
    }
    val style = when (heading) {
        1 -> MaterialTheme.typography.headlineLarge
        2 -> MaterialTheme.typography.headlineMedium
        3 -> MaterialTheme.typography.headlineSmall
        4, 5, 6 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.bodyLarge
    }
    Text(annotated, style = style, color = colors.onSurface)
}

private fun AnnotatedString.Builder.appendInline(
    node: Node,
    linkColor: Color,
    codeBackground: Color,
    codeForeground: Color,
    onLink: (String) -> Unit,
    imageTemplate: String
) {
    node.children().forEach { child ->
        when (child) {
            is org.commonmark.node.Text -> append(child.literal)
            is SoftLineBreak -> append("\n")
            is HardLineBreak -> append("\n")
            is Code -> {
                pushStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground, color = codeForeground)
                )
                append(child.literal)
                pop()
            }
            is Emphasis -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInline(child, linkColor, codeBackground, codeForeground, onLink, imageTemplate)
                pop()
            }
            is StrongEmphasis -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInline(child, linkColor, codeBackground, codeForeground, onLink, imageTemplate)
                pop()
            }
            is Link -> {
                val allowed = MarkdownSafety.safeLink(child.destination)
                if (allowed) {
                    pushLink(
                        LinkAnnotation.Url(
                            child.destination,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                        ) {
                            if (MarkdownSafety.safeLink(child.destination)) onLink(child.destination)
                        }
                    )
                }
                appendInline(child, linkColor, codeBackground, codeForeground, onLink, imageTemplate)
                if (allowed) {
                    pop()
                }
            }
            is Image -> {
                val alt = buildAnnotatedString {
                    appendInline(child, linkColor, codeBackground, codeForeground, onLink, imageTemplate)
                }
                append(String.format(imageTemplate, alt.text))
            }
            is HtmlInline -> append(child.literal)
            else -> appendInline(child, linkColor, codeBackground, codeForeground, onLink, imageTemplate)
        }
    }
}
