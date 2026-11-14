package app.djhx.memo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeThresholdTest {
    @Test fun distanceThresholdCannotBeBypassedByAShortFling() {
        assertFalse(swipeCommits(-49f, 100f))
        assertTrue(swipeCommits(-50f, 100f))
        assertFalse(swipeCommits(70f, 100f))
        assertFalse(swipeCommits(-5f, 0f))
    }
}
