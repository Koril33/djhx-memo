package app.djhx.memo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListEnterTest {
    /** 应用计划后的（正文, 光标），null 表示原生换行。 */
    private fun apply(content: String, cursor: Int): Pair<String, Int>? {
        val plan = planListEnter(content, cursor) ?: return null
        return content.replaceRange(plan.replaceStart, plan.replaceEnd, plan.text) to plan.cursor
    }

    // L01：非空无序项末尾续写同符号 + 空格。
    @Test fun unorderedItemContinuesWithSameMarker() {
        assertEquals("- item\n- " to 9, apply("- item", 6))
        assertEquals("+ item\n+ " to 9, apply("+ item", 6))
        assertEquals("* item\n* " to 9, apply("* item", 6))
        assertEquals("- 中文\n- " to 7, apply("- 中文", 4))
        assertEquals("- 😀\n- " to 7, apply("- 😀", 4))
    }

    // L01：行尾已有换行时也在换行前插入。
    @Test fun unorderedItemBeforeTrailingNewline() {
        assertEquals("- item\n- \n" to 9, apply("- item\n", 6))
    }

    // L02：有序项递增，只生成下一项。
    @Test fun orderedItemIncrementsNumber() {
        assertEquals("1. item\n2. " to 11, apply("1. item", 7))
        assertEquals("9. item\n10. " to 12, apply("9. item", 7))
        assertEquals("99. item\n100. " to 14, apply("99. item", 8))
        assertEquals("10. item\n11. " to 13, apply("10. item", 8))
        assertEquals("0. item\n1. " to 11, apply("0. item", 7))
    }

    // L02：递增超出 CommonMark 9 位上限时回退原生换行。
    @Test fun orderedNumberOverflowFallsBack() {
        assertNull(apply("999999999. item", 15))
    }

    // L03：空标记行移除前缀留普通空行（保留缩进）。
    @Test fun emptyMarkerRemovesPrefix() {
        assertEquals("" to 0, apply("- ", 2))
        assertEquals("" to 0, apply("2. ", 3))
        assertEquals("  " to 2, apply("  - ", 4))
    }

    // L04：围栏代码内不续写，围栏关闭后恢复。
    @Test fun fencedCodeIsNotContinued() {
        assertNull(apply("```\n- item\n", 10))
        assertNull(apply("```markdown\n- item\n", 18))
        assertEquals("```\n- item\n```\n- item\n- \n" to 24, apply("```\n- item\n```\n- item\n", 21))
    }

    // L04：缩进代码（4 空格）不当作列表。
    @Test fun indentedCodeIsNotContinued() {
        assertNull(apply("    - item", 10))
    }

    // L04：主题分隔线、转义标记、普通段落走原生换行。
    @Test fun thematicBreakEscapedAndParagraphAreNative() {
        assertNull(apply("---", 3))
        assertNull(apply("- - -", 5))
        assertNull(apply("***", 3))
        assertNull(apply("\\- item", 7))
        assertNull(apply("plain text", 10))
    }

    // L05：嵌套普通项沿用缩进；任务项、引用、`)` 编号、Tab 混排不续写。
    @Test fun nestedItemKeepsIndentAndUnsupportedContextsAreNative() {
        assertEquals("  - item\n  - " to 13, apply("  - item", 8))
        assertNull(apply("- [ ] item", 10))
        assertNull(apply("- [x] item", 10))
        assertNull(apply("> - item", 8))
        assertNull(apply("1) item", 7))
        assertNull(apply("\t- item", 7))
        assertNull(apply(" \t- item", 8))
    }

    // L06：光标在行中返回 null（原生行为）。
    @Test fun cursorMidLineIsNative() {
        assertNull(apply("- item x", 4))
    }
}
