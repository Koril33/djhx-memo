package app.djhx.memo

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts

/** Stable persisted keys correlate results after recreation or a canceled wait. */
internal class UpdateResultLaunchers(
    private val registry: ActivityResultRegistry,
    private val completed: (String) -> Unit
) {
    private val launchers = mutableMapOf<String, ActivityResultLauncher<Intent>>()
    internal val registeredAttempts: Set<String> get() = launchers.keys.toSet()

    fun restore(attempts: Set<String>) { attempts.forEach { register(it) } }
    fun launch(staged: StagedUpdate) { register(staged.attempt)?.launch(staged.intent) }

    private fun register(attempt: String): ActivityResultLauncher<Intent>? {
        launchers[attempt]?.let { return it }
        var delivered = false
        val launcher = registry.register("memo-update:$attempt", ActivityResultContracts.StartActivityForResult()) {
            delivered = true
            completed(attempt)
            launchers.remove(attempt)?.unregister()
        }
        // register may synchronously replay a result saved by the previous Activity.
        if (delivered) launcher.unregister() else launchers[attempt] = launcher
        return if (delivered) null else launcher
    }

    fun clear() {
        launchers.values.toList().forEach { it.unregister() }
        launchers.clear()
    }
}
