package app.djhx.memo

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Image
import org.commonmark.node.ListItem
import org.junit.Assert.*
import org.junit.Test

class MarkdownSafetyTest {
    @Test fun dangerousSchemesAndCredentialsAreNeverOpened() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,x",
            "file:///data/x",
            "intent://x",
            "https://user:password@example.com",
            "https://",
            "//example.com"
        ).forEach {
            assertFalse(it, MarkdownSafety.safeLink(it))
        }
        assertTrue(MarkdownSafety.safeLink("https://example.com/a?q=中文"))
        assertTrue(MarkdownSafety.safeLink("http://127.0.0.1:8080/path"))
    }

    @Test fun taskSourceLinesExcludeCodeFencesAndPreserveChineseAndIndentation() {
        val source = "# 中文😀\n\n- [ ] 保留空格  \n  - [X] 子任务\n\n```md\n- [ ] 代码不是任务\n```\n\n    - [ ] 缩进代码\n"
        val tasks = mutableListOf<Pair<Int, Boolean>>()
        MarkdownSafety.parse(source).accept(object : AbstractVisitor() {
            override fun visit(item: ListItem) {
                MarkdownSafety.taskLine(item, source.lines())?.let(tasks::add)
                super.visit(item)
            }
        })
        assertEquals(listOf(2 to false, 3 to true), tasks)
    }

    @Test fun htmlAndImagesStayInAstRatherThanExecutingOrFetching() {
        var htmlCount = 0
        var imageCount = 0
        MarkdownSafety.parse(
            "<script>alert('x')</script>\n\n![远端](https://example.com/private.png)"
        ).accept(object : AbstractVisitor() {
            override fun visit(block: HtmlBlock) { htmlCount++ }
            override fun visit(image: Image) { imageCount++ }
        })
        assertEquals(1, htmlCount)
        assertEquals(1, imageCount)
    }

    @Test fun quotedTaskUsesTheListMarkerSourceColumn() {
        val source = "> - [x] 引用内任务"
        val tasks = mutableListOf<Pair<Int, Boolean>>()
        MarkdownSafety.parse(source).accept(object : AbstractVisitor() {
            override fun visit(item: ListItem) {
                MarkdownSafety.taskLine(item, source.lines())?.let(tasks::add)
                super.visit(item)
            }
        })
        assertEquals(listOf(0 to true), tasks)
    }
}
