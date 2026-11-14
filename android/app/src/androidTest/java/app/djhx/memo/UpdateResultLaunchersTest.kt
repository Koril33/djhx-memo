package app.djhx.memo

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateResultLaunchersTest {
    private class Registry : ActivityResultRegistry() {
        var lastRequest = 0
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            lastRequest = requestCode
        }
    }

    @Test fun restoredPendingResultDoesNotLeaveACompletedRegistrationBehind() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val registry = Registry()
            val old = registry.register("memo-update:old", ActivityResultContracts.StartActivityForResult()) {}
            old.launch(Intent())
            old.unregister()
            assertTrue(registry.dispatchResult(registry.lastRequest, Activity.RESULT_CANCELED, null))
            val results = mutableListOf<String>()
            val launchers = UpdateResultLaunchers(registry) { results += it }
            launchers.restore(setOf("old"))
            assertEquals(listOf("old"), results)
            assertTrue(launchers.registeredAttempts.isEmpty())
            launchers.launch(StagedUpdate("new", Intent()))
            assertEquals(setOf("new"), launchers.registeredAttempts)
            registry.dispatchResult(registry.lastRequest, Activity.RESULT_CANCELED, null)
            assertEquals(listOf("old", "new"), results)
            assertTrue(launchers.registeredAttempts.isEmpty())
            launchers.clear()
        }
    }
}
