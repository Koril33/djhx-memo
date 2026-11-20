package app.djhx.memo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFindTest {
    @Test fun asciiCaseInsensitiveLiteralMatches() {
        assertEquals(listOf(0..2, 4..6, 8..10), findLiteralMatches("Foo foo FOO", "foo"))
        assertEquals(listOf(0..2), findLiteralMatches("ABC", "abc"))
        assertEquals(listOf(0..2), findLiteralMatches("AbC", "aBc"))
    }

    @Test fun nonOverlappingMatchesSkipConsumedText() {
        assertEquals(listOf(0..1, 2..3), findLiteralMatches("aaaa", "aa"))
    }

    @Test fun shortChineseAndMixedQueriesMatchExactly() {
        assertEquals(listOf(0..1), findLiteralMatches("会议记录", "会议"))
        assertEquals(listOf(1..2), findLiteralMatches("a会议b", "会议"))
    }

    @Test fun specialCharactersAreLiteral() {
        assertEquals(listOf(0..0), findLiteralMatches("%", "%"))
        assertEquals(listOf(0..0), findLiteralMatches("_", "_"))
        assertEquals(listOf(0..0), findLiteralMatches("\\", "\\"))
        assertEquals(listOf(0..0), findLiteralMatches("\"", "\""))
        assertEquals(listOf(1..2), findLiteralMatches("aORb", "OR"))
    }

    @Test fun nonAsciiIsNotCaseFolded() {
        // U+0130 (LATIN CAPITAL LETTER I WITH DOT ABOVE) must not fold to a lowercase ASCII 'i'.
        assertTrue(findLiteralMatches("\u0130", "i").isEmpty())
        assertTrue(findLiteralMatches("i", "\u0130").isEmpty())
    }

    @Test fun emptyOrLongerQueryMatchesNothing() {
        assertEquals(emptyList<IntRange>(), findLiteralMatches("abc", ""))
        assertEquals(emptyList<IntRange>(), findLiteralMatches("abc", "abcd"))
    }
}
