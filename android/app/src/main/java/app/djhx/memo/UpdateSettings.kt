package app.djhx.memo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

/** Overview, release action and device preferences have distinct visual groups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSettings(model: MemoModel) {
    val updates = (LocalContext.current.applicationContext as MemoApplication).updates
    LaunchedEffect(updates) {
        while (true) {
            updates.refresh()
            delay(1000)
        }
    }
    val state = updates.status
    val candidate = state.optJSONObject("candidate")
    val phase = state.optString("phase")
    val working = updates.operationPending || updates.installing || updates.preferencesPending || updates.recoveryRequired ||
        phase in setOf("checking", "downloading", "preparing")
    val error = updates.installError ?: state.nullableString("error")?.takeUnless { it == "UPDATE_CANCELED" }
    val information = error in setOf("UPDATE_INSTALL_CANCELED", "UPDATE_INSTALL_WAIT_CANCELED", "UPDATE_INSTALL_UNKNOWN")
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    MemoIcon(R.drawable.ic_ui_download, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f)) {
                Text("DJHX Memo", style = MaterialTheme.typography.titleMedium)
                Text(
                    "v${BuildConfig.VERSION_NAME} · ${stringResource(R.string.update_stable)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (phase == "up_to_date") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                stringResource(
                    when {
                        updates.recoveryRequired -> R.string.update_recovery_state
                        updates.waitingForSystemInstaller -> R.string.update_external_wait
                        error == "UPDATE_INSTALL_CANCELED" -> R.string.update_install_cancelled_state
                        error == "UPDATE_INSTALL_WAIT_CANCELED" -> R.string.update_wait_cancelled_state
                        else -> updatePhase(phase)
                    }
                ),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (state.optLong("last_checked") > 0) {
            Text(
                stringResource(
                    R.string.update_last_check,
                    formattedTime(java.time.Instant.ofEpochSecond(state.optLong("last_checked")).toString())
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        UpdateCard {
            if (candidate != null) {
                Text(
                    stringResource(R.string.update_candidate_version, candidate.optString("version")),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    mib(candidate.optLong("size")) + " MiB",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(candidate.optString("notes"), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(if (phase == "up_to_date") R.string.update_current else R.string.update_maintain),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (phase == "downloading" && candidate != null) {
                LinearProgressIndicator(
                    progress = { state.optLong("downloaded").toFloat() / candidate.optLong("size").coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${mib(state.optLong("downloaded"))} / ${mib(candidate.optLong("size"))} MiB",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (error != null) UpdateFailureNotice(error, information)
            UpdateActions(model, updates, phase, working, candidate != null)
        }
        UpdateCard {
            Text(stringResource(R.string.update_preferences), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.update_auto),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = state.optBoolean("auto_check", true),
                    enabled = !working && !updates.preferencesPending,
                    onCheckedChange = { updates.preferences(it, state.optString("download_policy", "unmetered")) }
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DownloadPreference(updates, !working && !updates.preferencesPending && state.optBoolean("auto_check", true))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateActions(
    model: MemoModel,
    updates: AppUpdates,
    phase: String,
    working: Boolean,
    hasCandidate: Boolean
) {
    val focus = LocalFocusManager.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (phase == "downloading") {
            OutlinedButton(
                onClick = { updates.action("cancel") }
            ) { Text(stringResource(R.string.cancel)) }
        } else {
            Button(onClick = {
                when (phase) {
                    "ready" -> {
                        focus.clearFocus()
                        updates.install(model)
                    }
                    "available" -> updates.action("download")
                    else -> updates.action("check")
                }
            }, enabled = !working && !model.busy) {
                if (working) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        when (phase) { "ready" -> R.string.update_install
                            "available" -> R.string.update_download
                            else -> R.string.update_check }
                    )
                )
            }
        }
        if (hasCandidate && !working) {
            if (phase == "ready" || updates.installError?.startsWith("UPDATE_INSTALL") == true) {
                OutlinedButton(onClick = {
                    focus.clearFocus()
                    updates.installWithSystemInstaller(model)
                }, enabled = !model.busy) { Text(stringResource(R.string.update_system_install)) }
            }
            TextButton(
                onClick = { updates.action("defer") }
            ) { Text(stringResource(R.string.update_later)) }
        }
        if (updates.needsConfirmation) {
            Button(
                onClick = updates::continueInstallation
            ) { Text(stringResource(R.string.update_continue)) }
        }
        if (updates.canCancelInstallation) {
            TextButton(
                onClick = updates::cancelInstallation
            ) {
                Text(
                    stringResource(
                        when { updates.recoveryRequired -> R.string.update_retry_recovery
                            updates.waitingForSystemInstaller -> R.string.update_cancel_wait
                            else -> R.string.cancel }
                    )
                )
            }
        }
    }
}

@Composable
private fun UpdateFailureNotice(error: String, information: Boolean) {
    val colors = MaterialTheme.colorScheme
    val background = if (information) colors.surfaceVariant else colors.errorContainer
    val content = if (information) colors.onSurfaceVariant else colors.onErrorContainer
    Surface(shape = RoundedCornerShape(10.dp), color = background) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MemoIcon(R.drawable.ic_ui_circle_alert, tint = content)
            Text(stringResource(updateError(error)), style = MaterialTheme.typography.bodySmall, color = content)
        }
    }
}

@Composable
private fun UpdateCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun DownloadPreference(updates: AppUpdates, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val policy = updates.status.optString("download_policy", "unmetered")
    val choices = listOf(
        "notify" to R.string.update_notify,
        "unmetered" to R.string.update_unmetered_short,
        "any" to R.string.update_any
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.automatic_downloads),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Text(stringResource(choices.firstOrNull { it.first == policy }?.second ?: R.string.update_notify))
                Spacer(Modifier.width(4.dp))
                MemoIcon(R.drawable.ic_ui_chevron_down)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (value, text) ->
                    DropdownMenuItem(enabled = enabled, text = { Text(stringResource(text)) }, onClick = {
                        expanded = false
                        updates.preferences(updates.status.optBoolean("auto_check", true), value)
                    }, trailingIcon = { if (policy == value) MemoIcon(R.drawable.ic_ui_check) })
                }
            }
        }
    }
}

private fun mib(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / 1048576.0)
private fun updatePhase(phase: String): Int = when (phase) {
    "checking" -> R.string.update_checking
    "available" -> R.string.update_available
    "downloading" -> R.string.update_downloading
    "ready" -> R.string.update_ready
    "up_to_date" -> R.string.update_current
    "completed" -> R.string.update_completed
    "failed" -> R.string.update_failed
    "canceled" -> R.string.update_cancelled
    "preparing" -> R.string.update_preparing
    else -> R.string.update_idle
}

internal fun updateError(code: String): Int = when (code) {
    "UPDATE_INSTALL_RECOVERY" -> R.string.update_install_recovery
    "UPDATE_INSTALL_BLOCKED" -> R.string.update_install_blocked
    "UPDATE_INSTALL_CONFLICT" -> R.string.update_install_conflict
    "UPDATE_INSTALL_INVALID" -> R.string.update_install_invalid
    "UPDATE_INSTALL_INCOMPATIBLE" -> R.string.update_incompatible
    "UPDATE_INSTALL_STORAGE" -> R.string.update_install_storage
    "UPDATE_INSTALL_CANCELED" -> R.string.update_install_cancelled
    "UPDATE_INSTALL_WAIT_CANCELED" -> R.string.update_wait_cancelled
    "UPDATE_INSTALL_UNKNOWN" -> R.string.update_install_unknown
    "UPDATE_INSTALL_TIMEOUT" -> R.string.update_install_timeout
    "UPDATE_INSTALL_BACKGROUND" -> R.string.update_install_background
    "UPDATE_INSTALL_UNAVAILABLE" -> R.string.update_install_unavailable
    "UPDATE_NETWORK" -> R.string.update_network
    "UPDATE_SIGNATURE", "UPDATE_PACKAGE", "UPDATE_CATALOG", "UPDATE_REPLAY" -> R.string.update_invalid
    "UPDATE_EXPIRED" -> R.string.update_expired
    "UPDATE_INCOMPATIBLE", "UPDATE_UNSUPPORTED" -> R.string.update_incompatible
    "UPDATE_SAVE" -> R.string.update_save
    "UPDATE_STORAGE" -> R.string.update_storage
    else -> R.string.update_failed
}
