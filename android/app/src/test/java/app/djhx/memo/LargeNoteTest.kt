package app.djhx.memo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeNoteTest {
    @Test fun asciiByteLengthIsCharacterCount() {
        assertEquals(0, utf8ByteLength(""))
        assertEquals(3, utf8ByteLength("abc"))
        assertEquals(LARGE_NOTE_UTF8_BYTES, utf8ByteLength("a".repeat(LARGE_NOTE_UTF8_BYTES)))
    }

    @Test fun nonAsciiCountsPerUtf8Rules() {
        assertEquals(6, utf8ByteLength("会议"))
        assertEquals(4, utf8ByteLength("\uD83D\uDE00")) // U+1F600 代理对
        assertEquals(6, utf8ByteLength("a\uD83D\uDE00b"))
    }

    @Test fun thresholdIsInclusiveAt128KiB() {
        assertFalse(isLargeNote("a".repeat(LARGE_NOTE_UTF8_BYTES - 1)))
        assertTrue(isLargeNote("a".repeat(LARGE_NOTE_UTF8_BYTES)))
        assertTrue(isLargeNote("a".repeat(LARGE_NOTE_UTF8_BYTES + 1)))
    }

    @Test fun multibyteThresholdUsesBytesNotCharacters() {
        // 43690 个 CJK 字符 = 131070 字节 < 128 KiB；43691 = 131073 字节 >= 128 KiB。
        assertFalse(isLargeNote("会".repeat(LARGE_NOTE_UTF8_BYTES / 3)))
        assertTrue(isLargeNote("会".repeat(LARGE_NOTE_UTF8_BYTES / 3 + 1)))
    }

    @Test fun shortCircuitMatchesExactLength() {
        assertTrue(utf8ByteLengthAtLeast("abc", 3))
        assertFalse(utf8ByteLengthAtLeast("abc", 4))
        assertTrue(utf8ByteLengthAtLeast("会议", 6))
        assertFalse(utf8ByteLengthAtLeast("会议", 7))
        assertTrue(utf8ByteLengthAtLeast("a", 0))
    }
}
