package app.djhx.memo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchSourceTest {
    @Test fun hostKeepsHostPortAndBasePath() {
        assertEquals("notes.example.invalid", serverHostDisplay("https://notes.example.invalid"))
        assertEquals("127.0.0.1:8080/base", serverHostDisplay("http://127.0.0.1:8080/base"))
        assertEquals("notes.example.invalid/memo", serverHostDisplay("https://notes.example.invalid/memo/"))
    }

    @Test fun userInfoQueryAndFragmentAreDropped() {
        assertEquals("notes.example.invalid/v1", serverHostDisplay("https://user:pass@notes.example.invalid/v1?x=1#frag"))
    }

    @Test fun unparseableOrEmptyUrlIsNull() {
        assertNull(serverHostDisplay(null))
        assertNull(serverHostDisplay(""))
        assertNull(serverHostDisplay("not a url"))
    }

    @Test fun identityFallsBackToLabel() {
        assertEquals("alpha", accountSourceIdentity("alpha", "label"))
        assertEquals("label", accountSourceIdentity("", "label"))
        assertEquals("label", accountSourceIdentity(null, "label"))
        assertNull(accountSourceIdentity(null, null))
        assertNull(accountSourceIdentity("  ", "  "))
    }
}
