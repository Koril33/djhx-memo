package app.djhx.memo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerRetryPolicyTest {
    @Test fun permanentErrorsAndAuthenticationDoNotRetryForever() {
        assertFalse(shouldRetrySync("error", false))
        assertFalse(shouldRetrySync("auth_required", false))
        assertFalse(shouldRetrySync("auth_required", true))
        assertFalse(shouldRetrySync("recovery_required", true))
        assertFalse(shouldRetrySync("synced", false))
    }

    @Test fun onlyExplicitTemporaryErrorsAreRetried() {
        assertTrue(shouldRetrySync("error", true))
        assertTrue(shouldRetrySync("offline", true))
        assertFalse(shouldRetrySync("offline", false))
    }
}
