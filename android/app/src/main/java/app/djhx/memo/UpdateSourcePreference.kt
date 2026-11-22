package app.djhx.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** A device-only transport choice; package trust and install state remain in Rust. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateSourcePreference(updates: AppUpdates, working: Boolean) {
    val state = updates.status
    val phase = state.optString("phase")
    val installationBlocked = updates.installing || updates.recoveryRequired
    val sourcePending = updates.preferencesPending || state.optBoolean("source_changing")
    val commandBlocked = working && phase != "downloading"
    UpdateCard {
        Text(stringResource(R.string.update_source), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf("memo" to "memo.djhx.site", "github" to "GitHub")) {
                FilterChip(
                    selected = state.optString("download_source", "memo") == key,
                    onClick = { updates.changeSource(key) },
                    enabled = !(installationBlocked || sourcePending || commandBlocked),
                    label = { Text(label) }
                )
            }
        }
        Text(
            stringResource(if (sourcePending) R.string.update_source_wait else R.string.update_source_help),
            style = MaterialTheme.typography.bodySmall
        )
        if (state.nullableString("source_error") != null) {
            Text(stringResource(R.string.update_source_failed), color = MaterialTheme.colorScheme.error)
        }
        if (phase == "ready") {
            state.nullableString("downloaded_from")?.let {
                val label = if (it == "memo") "memo.djhx.site" else "GitHub"
                Text(stringResource(R.string.update_source_received, label), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = updates::openReleasePage) { Text(stringResource(R.string.update_release_page)) }
    }
}
