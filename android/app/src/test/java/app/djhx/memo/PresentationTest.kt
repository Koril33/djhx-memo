package app.djhx.memo

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class PresentationTest {
    @Test fun languageSelectionUsesChineseOnlyForChineseSystems() {
        assertEquals("zh", displayLocale("system", Locale.TAIWAN).language)
        assertEquals("en", displayLocale("system", Locale.JAPAN).language)
        assertEquals("en", displayLocale("en", Locale.SIMPLIFIED_CHINESE).language)
        assertEquals("zh", displayLocale("zh-CN", Locale.US).language)
    }

    @Test fun explicitThemeWinsAndSystemChoiceTracksSystem() {
        assertTrue(displayDark("dark", false))
        assertFalse(displayDark("light", true))
        assertTrue(displayDark("system", true))
        assertFalse(displayDark("system", false))
    }

    @Test fun timeZoneChangesDisplayWithoutChangingTheInstant() {
        val instant = "2026-11-07T23:30:00Z"
        val shanghai = displayTime(instant, Locale.US, ZoneId.of("Asia/Shanghai"))
        val utc = displayTime(instant, Locale.US, ZoneId.of("UTC"))
        assertTrue(shanghai, shanghai.contains("Nov 8, 2026"))
        assertTrue(utc, utc.contains("Nov 7, 2026"))
        assertTrue(shanghai, shanghai.contains("7:30"))
        assertTrue(utc, utc.contains("11:30"))
        assertEquals("—", displayTime("invalid", Locale.US, ZoneId.of("UTC")))
        assertEquals("—", displayTime(null, Locale.US, ZoneId.of("UTC")))
        assertEquals("Asia/Shanghai", DisplayPreferences().timeZone)
        assertEquals("https://memo.djhx.site", DEFAULT_SERVER)
    }

    @Test fun localizedResourcesCoverTheSameKeysAndPlaceholders() {
        val resources = File("src/main/res")
        fun strings(folder: String): Map<String, String> {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(resources, "$folder/strings.xml"))
            val nodes = document.getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            val groups = document.getElementsByTagName("plurals")
            val plurals = (0 until groups.length).flatMap { index ->
                val group = groups.item(index) as org.w3c.dom.Element
                val quantities = group.getElementsByTagName("item")
                (0 until quantities.length).map { item ->
                    val quantity = quantities.item(item) as org.w3c.dom.Element
                    "${group.getAttribute("name")}.${quantity.getAttribute("quantity")}" to quantity.textContent
                }
            }.toMap()
            val brandDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(resources, "$folder/brand.xml"))
            val brandNodes = brandDocument.getElementsByTagName("string")
            val brand = (0 until brandNodes.length).associate { index ->
                val node = brandNodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            assertTrue(
                "Generated brand keys must not duplicate ordinary strings",
                (strings.keys intersect brand.keys).isEmpty()
            )
            return strings + plurals + brand
        }
        val english = strings("values")
        val chinese = strings("values-zh-rCN")
        assertEquals(english.keys, chinese.keys)
        val placeholder = Regex("%[0-9]+\\$[ds]")
        english.forEach { (key, text) ->
            assertEquals(
                key,
                placeholder.findAll(text).map { it.value }.toList(),
                placeholder.findAll(chinese.getValue(key)).map { it.value }.toList()
            )
        }
        assertEquals("搜索笔记", chinese.getValue("search_notes"))
        assertEquals("Search notes", english.getValue("search_notes"))
        assertEquals("阳墨随记", chinese.getValue("app_name"))
        assertEquals("DJHX MEMO", english.getValue("app_name"))
        // spec/auth.md: hyphens are valid; the first character must be a letter or digit.
        assertTrue(english.getValue("error_request").contains("underscores or hyphens"))
        assertTrue(english.getValue("error_request").contains("starting with a letter or digit"))
        assertTrue(chinese.getValue("error_request").contains("下划线或连字符"))
        assertTrue(chinese.getValue("error_request").contains("首位为字母或数字"))
        assertEquals("Pending sync · %1\$d item", english.getValue("cloud_pending.one"))
        assertEquals("Pending sync · %1\$d items", english.getValue("cloud_pending.other"))
    }

    @Test fun listExcerptUsesOnlyTheFirstBodyLineAndPreservesUnicode() {
        assertEquals("第一行😀", noteExcerpt("\n# 标题\n\n第一行😀\n第二行"))
        assertEquals("", noteExcerpt("只有标题"))
        assertEquals("  保留空格", noteExcerpt("# 标题\n  保留空格\n其他内容"))
        assertEquals("😀".repeat(160), noteExcerpt("标题\n" + "😀".repeat(160)))
    }

    @Test fun unknownErrorsUseFixedSafeMessage() {
        assertEquals(R.string.operation_failed, errorResource("UNTRUSTED_SERVER_TEXT"))
        assertEquals(R.string.error_auth_invalid, errorResource("AUTH_INVALID_CREDENTIALS"))
        assertEquals(R.string.error_recovery, errorResource("SYNC_EPOCH_MISMATCH"))
    }
}
