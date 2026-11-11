package app.djhx.memo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Native update progress. Untrusted release notes remain plain text. */
@Composable
fun UpdateSettings(model: MemoModel) {
    val updates = (LocalContext.current.applicationContext as MemoApplication).updates
    val focus = LocalFocusManager.current
    LaunchedEffect(updates) {
        while (true) {
            updates.refresh()
            delay(1000)
        }
    }
    val state = updates.status
    val candidate = state.optJSONObject("candidate")
    val phase = state.optString("phase")
    val working = phase in setOf("checking", "downloading", "preparing") || updates.installing
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge)
        Text(BuildConfig.VERSION_NAME)
        if (state.optLong("last_checked") > 0) {
            Text(
                stringResource(
                    R.string.update_last_check,
                    formattedTime(java.time.Instant.ofEpochSecond(state.optLong("last_checked")).toString())
                )
            )
        }
        Text(stringResource(updatePhase(phase)))
        if (candidate != null) {
            Text(candidate.optString("version") + " · " + (candidate.optLong("size") / 1048576) + " MiB")
            Text(candidate.optString("notes"))
            if (phase == "downloading") {
                LinearProgressIndicator(
                    progress = { state.optLong("downloaded").toFloat() / candidate.optLong("size").coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        val error = updates.installError ?: state.nullableString("error")
        if (error != null && error != "UPDATE_CANCELED") {
            Text(stringResource(updateError(error)), color = MaterialTheme.colorScheme.error)
        }
        TextButton(
            onClick = { updates.action("check") },
            enabled = !working
        ) { Text(stringResource(R.string.update_check)) }
        if (phase == "ready" && !updates.installing) {
            Button(
                onClick = {
                    focus.clearFocus()
                    updates.install(model)
                },
                enabled = !working
            ) { Text(stringResource(R.string.update_install)) }
        } else if (candidate != null && phase != "downloading" && !updates.installing) {
            Button(
                onClick = { updates.action("download") },
                enabled = !working
            ) { Text(stringResource(R.string.update_download)) }
        }
        if (phase == "downloading") {
            TextButton(onClick = { updates.action("cancel") }) { Text(stringResource(R.string.cancel)) }
        }
        if (candidate != null) {
            TextButton(
                onClick = { updates.action("defer") },
                enabled = !working
            ) { Text(stringResource(R.string.update_later)) }
        }
        if (updates.needsConfirmation) {
            Button(onClick = updates::continueInstallation) { Text(stringResource(R.string.update_continue)) }
            TextButton(onClick = updates::cancelInstallation) { Text(stringResource(R.string.cancel)) }
        }
        Row {
            Switch(
                checked = state.optBoolean("auto_check", true),
                enabled = !updates.installing && !updates.preferencesPending,
                onCheckedChange = { updates.preferences(it, state.optString("download_policy", "unmetered")) }
            )
            Text(stringResource(R.string.update_auto))
        }
        listOf(
            "notify" to R.string.update_notify,
            "unmetered" to R.string.update_unmetered,
            "any" to R.string.update_any
        ).forEach { (policy, label) ->
            Row {
                RadioButton(
                    selected = state.optString("download_policy", "unmetered") == policy,
                    enabled = !updates.installing && !updates.preferencesPending && state.optBoolean("auto_check", true),
                    onClick = { updates.preferences(state.optBoolean("auto_check", true), policy) }
                )
                Text(stringResource(label))
            }
        }
        Text(stringResource(R.string.update_privacy))
    }
}

private fun updatePhase(phase: String): Int = when (phase) {
    "checking" -> R.string.update_checking
    "available" -> R.string.update_available
    "downloading" -> R.string.update_downloading
    "ready" -> R.string.update_ready
    "up_to_date" -> R.string.update_current
    "completed" -> R.string.update_completed
    "failed" -> R.string.update_failed
    "preparing" -> R.string.update_preparing
    else -> R.string.update_idle
}

internal fun updateError(code: String): Int = when (code) {
    "UPDATE_NETWORK" -> R.string.update_network
    "UPDATE_SIGNATURE", "UPDATE_PACKAGE", "UPDATE_CATALOG", "UPDATE_REPLAY" -> R.string.update_invalid
    "UPDATE_EXPIRED" -> R.string.update_expired
    "UPDATE_INCOMPATIBLE", "UPDATE_UNSUPPORTED" -> R.string.update_incompatible
    "UPDATE_SAVE" -> R.string.update_save
    "UPDATE_STORAGE" -> R.string.update_storage
    else -> R.string.update_failed
}
