package app.djhx.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.node.Text as TextNode

@Composable
internal fun MarkdownPreview(
    source: String,
    editable: Boolean,
    codeHighlights: CodeHighlights? = null,
    modifier: Modifier = Modifier,
    onTask: (Int) -> Unit,
    onLink: (String) -> Unit
) {
    val tree = remember(source) { MarkdownSafety.parse(source) }
    val lines = remember(source) { source.lines() }
    val style = rememberMarkdownStyle(isLargeNote(source))
    CompositionLocalProvider(LocalMarkdownStyle provides style) {
        Box(modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                MarkdownChildren(tree, lines, editable, onTask, onLink, codeHighlights, MarkdownContext())
            }
        }
    }
}

private fun Node.children(): List<Node> = buildList {
    var node = firstChild
    while (node != null) {
        add(node)
        node = node.next
    }
}

private fun blockKind(node: Node): BlockKind = when (node) {
    is Heading -> when (node.level) {
        1 -> BlockKind.H1
        2 -> BlockKind.H2
        3 -> BlockKind.H3
        else -> BlockKind.H4_6
    }
    is BulletList, is OrderedList -> BlockKind.LIST
    is BlockQuote -> BlockKind.QUOTE
    is FencedCodeBlock, is IndentedCodeBlock -> BlockKind.CODE
    is ThematicBreak -> BlockKind.RULE
    else -> BlockKind.PARAGRAPH
}

@Composable
private fun MarkdownChildren(
    node: Node,
    lines: List<String>,
    editable: Boolean,
    onTask: (Int) -> Unit,
    onLink: (String) -> Unit,
    codeHighlights: CodeHighlights?,
    context: MarkdownContext
) {
    val children = node.children()
    children.forEachIndexed { index, block ->
        val previous = if (index == 0) null else blockKind(children[index - 1])
        MarkdownBlock(block, lines, editable, onTask, onLink, codeHighlights, context.copy(previous = previous))
    }
}

@Composable
private fun MarkdownBlock(
    block: Node,
    lines: List<String>,
    editable: Boolean,
    onTask: (Int) -> Unit,
    onLink: (String) -> Unit,
    codeHighlights: CodeHighlights?,
    context: MarkdownContext
) {
    val kind = blockKind(block)
    val spaceBefore = if (context.previous == null) 0.dp else gapBetween(context.previous!!, kind)
    Column(Modifier.fillMaxWidth().padding(top = spaceBefore)) {
        when (block) {
            is Heading -> MarkdownText(block, onLink, heading = block.level)
            is Paragraph -> MarkdownText(block, onLink)
            is FencedCodeBlock -> {
                val code = block.literal.removeSuffix("\n")
                CodeBlock(
                    code,
                    codeHighlights?.previewSpans(block.info.orEmpty(), code).orEmpty(),
                    block.sourceSpans.firstOrNull()?.lineIndex
                )
            }
            is IndentedCodeBlock -> CodeBlock(
                block.literal.removeSuffix("\n"),
                key = block.sourceSpans.firstOrNull()?.lineIndex
            )
            is HtmlBlock -> MarkdownText(block, onLink)
            is ThematicBreak -> HorizontalDivider(color = LocalMarkdownStyle.current.ruleColor)
            is BlockQuote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(LocalMarkdownStyle.current.quoteBar))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    MarkdownChildren(
                        block,
                        lines,
                        editable,
                        onTask,
                        onLink,
                        codeHighlights,
                        context.copy(depth = context.depth + 1)
                    )
                }
            }
            is BulletList, is OrderedList -> ListBlock(
                block,
                lines,
                editable,
                onTask,
                onLink,
                codeHighlights,
                context
            )
            else -> MarkdownChildren(
                block,
                lines,
                editable,
                onTask,
                onLink,
                codeHighlights,
                context
            )
        }
    }
}

@Composable
private fun ListBlock(
    block: Node,
    lines: List<String>,
    editable: Boolean,
    onTask: (Int) -> Unit,
    onLink: (String) -> Unit,
    codeHighlights: CodeHighlights?,
    context: MarkdownContext
) {
    val ordered = block is OrderedList
    val tight = if (block is ListBlock) block.isTight else false
    val items = block.children().filterIsInstance<ListItem>()
    val start = if (ordered) (block as OrderedList).markerStartNumber ?: 1 else 1
    val maxNumber = start + items.size - 1
    val markerWidth = if (ordered) (maxNumber.toString().length * 8 + 8).dp else context.markerWidth
    val itemGap = if (tight) 4.dp else 8.dp
    Column {
        items.forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.height(itemGap))
            val task = MarkdownSafety.taskLine(item, lines)
            Row(Modifier.fillMaxWidth()) {
                if (task != null) {
                    val taskLabel = stringResource(R.string.task_accessibility, task.first + 1)
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Checkbox(
                            modifier = Modifier.size(20.dp).semantics { contentDescription = taskLabel },
                            checked = task.second,
                            enabled = editable,
                            onCheckedChange = { onTask(task.first) }
                        )
                    }
                    Box(Modifier.width(4.dp))
                } else {
                    Marker(block, index, start, markerWidth, context.depth)
                }
                Column(Modifier.weight(1f)) {
                    item.children().forEachIndexed { childIndex, child ->
                        val itemContext = context.copy(previous = null, tight = tight)
                        if (child is Paragraph) {
                            MarkdownText(
                                child,
                                onLink,
                                dropTask = task != null && childIndex == 0,
                                completed = task?.second == true
                            )
                        } else {
                            MarkdownBlock(
                                child,
                                lines,
                                editable,
                                onTask,
                                onLink,
                                codeHighlights,
                                itemContext.copy(depth = context.depth + 1)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Marker(block: Node, index: Int, start: Int, markerWidth: Dp, depth: Int) {
    val label = if (block is OrderedList) {
        val delimiter = if (block.markerDelimiter == ")") ")" else "."
        "${start + index}$delimiter"
    } else {
        when (depth % 3) {
            0 -> "•"
            1 -> "◦"
            else -> "▪"
        }
    }
    Text(
        label,
        Modifier.width(markerWidth).padding(top = 4.dp),
        textAlign = TextAlign.End,
        style = LocalMarkdownStyle.current.body.copy(fontFeatureSettings = "tnum")
    )
}

@Composable
private fun CodeBlock(code: String, spans: List<CodeSpan> = emptyList(), key: Int? = null) {
    val dark = LocalDarkAppearance.current
    val style = LocalMarkdownStyle.current
    val highlighted = remember(code, spans, dark) {
        buildAnnotatedString {
            append(code)
            spans.forEach { span -> addStyle(SpanStyle(color = span.kind.color(dark)), span.start, span.end) }
        }
    }
    androidx.compose.runtime.key(key) {
        Text(
            highlighted,
            Modifier.fillMaxWidth().background(style.codeBackground, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()).padding(12.dp),
            style = style.code,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
    }
}

@Composable
private fun MarkdownText(
    node: Node,
    onLink: (String) -> Unit,
    heading: Int? = null,
    dropTask: Boolean = false,
    completed: Boolean = false
) {
    val style = LocalMarkdownStyle.current
    val imageTemplate = stringResource(R.string.image_not_loaded)
    var annotated = remember(node, style, onLink, imageTemplate) {
        buildAnnotatedString {
            appendInline(node, style, onLink, imageTemplate)
        }
    }
    if (dropTask && Regex("^\\[[ xX]\\]\\s*").containsMatchIn(annotated.text)) {
        val length = Regex("^\\[[ xX]\\]\\s*").find(annotated.text)!!.value.length
        annotated = annotated.subSequence(length, annotated.length)
    }
    if (completed) {
        annotated = buildAnnotatedString {
            pushStyle(SpanStyle(color = style.completed, textDecoration = TextDecoration.LineThrough))
            append(annotated)
            pop()
        }
    }
    val textStyle = when (heading) {
        1 -> style.h1
        2 -> style.h2
        3 -> style.h3
        4 -> style.h4
        5 -> style.h5
        6 -> style.h6
        else -> style.body
    }
    Text(annotated, style = textStyle)
}

private fun AnnotatedString.Builder.appendInline(
    node: Node,
    style: MarkdownStyle,
    onLink: (String) -> Unit,
    imageTemplate: String
) {
    node.children().forEach { child ->
        when (child) {
            is TextNode -> append(child.literal)
            is SoftLineBreak -> append("\n")
            is HardLineBreak -> append("\n")
            is Code -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = style.inlineCodeBackground))
                append(child.literal)
                pop()
            }
            is Emphasis -> {
                pushStyle(style.emphasis.toSpanStyle())
                appendInline(child, style, onLink, imageTemplate)
                pop()
            }
            is StrongEmphasis -> {
                pushStyle(style.strong.toSpanStyle())
                appendInline(child, style, onLink, imageTemplate)
                pop()
            }
            is Strikethrough -> {
                pushStyle(style.strikethrough.toSpanStyle())
                appendInline(child, style, onLink, imageTemplate)
                pop()
            }
            is Link -> {
                val allowed = MarkdownSafety.safeLink(child.destination)
                if (allowed) {
                    pushLink(
                        LinkAnnotation.Url(
                            child.destination,
                            TextLinkStyles(style.link.toSpanStyle())
                        ) {
                            if (MarkdownSafety.safeLink(child.destination)) onLink(child.destination)
                        }
                    )
                }
                appendInline(child, style, onLink, imageTemplate)
                if (allowed) {
                    pop()
                }
            }
            is Image -> {
                val alt = buildAnnotatedString { appendInline(child, style, onLink, imageTemplate) }
                append(String.format(imageTemplate, alt.text))
            }
            is HtmlInline -> append(child.literal)
            else -> appendInline(child, style, onLink, imageTemplate)
        }
    }
}

private fun TextStyle.toSpanStyle(): SpanStyle = SpanStyle(
    color = color,
    fontSize = fontSize,
    fontWeight = fontWeight,
    fontStyle = fontStyle,
    fontFamily = fontFamily,
    textDecoration = textDecoration,
    background = background,
    letterSpacing = letterSpacing
)
