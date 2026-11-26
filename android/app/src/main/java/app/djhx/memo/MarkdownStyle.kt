package app.djhx.memo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 集中式预览排版令牌，全部从应用既有 MaterialTheme 派生，不写死品牌色、不启用 Material You。 */
@Immutable
internal data class MarkdownStyle(
    val body: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val code: TextStyle,
    val codeBackground: Color,
    val inlineCodeBackground: Color,
    val link: TextStyle,
    val emphasis: TextStyle,
    val strong: TextStyle,
    val strikethrough: TextStyle,
    val quoteBar: Color,
    val ruleColor: Color,
    val completed: Color,
)

/** 预览块级间距（top 为前距、bottom 为后距），相邻块取两者较大值合并，首块 top 归零。 */
@Immutable
internal data class BlockSpacing(val top: Dp, val bottom: Dp)

/** 渲染上下文：层级、前一兄弟块、紧凑列表与有序标记宽度，收敛长参数列表。 */
@Immutable
internal data class MarkdownContext(
    val depth: Int = 0,
    val previous: BlockKind? = null,
    val tight: Boolean = false,
    val markerWidth: Dp = 20.dp,
)

internal enum class BlockKind {
    PARAGRAPH, H1, H2, H3, H4_6, LIST, QUOTE, CODE, RULE;

    val spacing: BlockSpacing
        get() = when (this) {
            PARAGRAPH -> BlockSpacing(12.dp, 0.dp)
            H1 -> BlockSpacing(28.dp, 12.dp)
            H2 -> BlockSpacing(24.dp, 8.dp)
            H3 -> BlockSpacing(20.dp, 6.dp)
            H4_6 -> BlockSpacing(16.dp, 4.dp)
            LIST -> BlockSpacing(12.dp, 12.dp)
            QUOTE -> BlockSpacing(12.dp, 12.dp)
            CODE -> BlockSpacing(12.dp, 12.dp)
            RULE -> BlockSpacing(24.dp, 24.dp)
        }

    val heading: Boolean
        get() = this == H1 || this == H2 || this == H3 || this == H4_6
}

/** 相邻两块的前距：取前一后距与当前前距较大者；标题紧跟标题时减半（最小 8dp）。 */
internal fun gapBetween(previous: BlockKind, current: BlockKind): Dp {
    val merged = maxOf(previous.spacing.bottom, current.spacing.top)
    return if (previous.heading && current.heading) maxOf(8.dp, merged / 2) else merged
}

/** 安全默认值仅用于 CompositionLocal 兜底；真实渲染始终由 [rememberMarkdownStyle] 提供。 */
private val fallbackMarkdownStyle = MarkdownStyle(
    body = TextStyle(fontSize = 16.sp, lineHeight = 27.sp),
    h1 = TextStyle(fontSize = 28.sp, lineHeight = 36.sp),
    h2 = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    h3 = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    h4 = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    h5 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    h6 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    code = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    codeBackground = Color(0xFFF0F0F0),
    inlineCodeBackground = Color(0xFFEEEEEE),
    link = TextStyle(),
    emphasis = TextStyle(),
    strong = TextStyle(),
    strikethrough = TextStyle(),
    quoteBar = Color(0xFFCCCCCC),
    ruleColor = Color(0xFFCCCCCC),
    completed = Color(0xFF777777),
)

internal val LocalMarkdownStyle = compositionLocalOf { fallbackMarkdownStyle }

@Composable
internal fun rememberMarkdownStyle(largeNote: Boolean): MarkdownStyle {
    val colors = MaterialTheme.colorScheme
    val lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    val bodyLineBreak = if (largeNote) LineBreak.Unspecified else LineBreak.Paragraph
    val headingLineBreak = LineBreak.Heading
    val headings = { size: Int, height: Int ->
        TextStyle(
            fontSize = size.sp,
            lineHeight = height.sp,
            lineHeightStyle = lineHeightStyle,
            lineBreak = headingLineBreak,
        )
    }
    return MarkdownStyle(
        body = TextStyle(
            fontSize = 16.sp,
            lineHeight = 27.sp,
            lineHeightStyle = lineHeightStyle,
            lineBreak = bodyLineBreak,
            letterSpacing = 0.2.sp,
            color = colors.onSurface,
        ),
        h1 = headings(28, 36).copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
        h2 = headings(22, 30).copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
        h3 = headings(18, 26).copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
        h4 = headings(16, 24).copy(fontWeight = FontWeight.SemiBold, color = colors.onSurface),
        h5 = headings(14, 20).copy(fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant),
        h6 = headings(14, 20).copy(fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant),
        code = TextStyle(
            fontSize = 13.sp,
            lineHeight = 20.sp,
            lineHeightStyle = lineHeightStyle,
            lineBreak = LineBreak.Unspecified,
        ),
        codeBackground = colors.surfaceContainer,
        inlineCodeBackground = colors.surfaceContainerHighest,
        link = TextStyle(
            color = colors.primary,
            textDecoration = TextDecoration.Underline,
        ),
        emphasis = TextStyle(fontStyle = FontStyle.Italic),
        strong = TextStyle(fontWeight = FontWeight.SemiBold),
        strikethrough = TextStyle(textDecoration = TextDecoration.LineThrough),
        quoteBar = colors.outlineVariant,
        ruleColor = colors.outlineVariant,
        completed = colors.onSurfaceVariant,
    )
}
