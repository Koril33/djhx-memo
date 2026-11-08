package app.djhx.memo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 原生主界面；退出到后台前请求本地提交，云端失败不阻止离线编辑。 */
class MainActivity : ComponentActivity() {
    private val model: MemoModel by viewModels()
    internal val presentation by lazy { PresentationStore(applicationContext) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MemoPresentation(presentation) {
                val dark = LocalDarkAppearance.current
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
                MemoScreen(model, ::openLink)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        model.foreground(true)
    }
    override fun onStop() {
        model.foreground(false)
        super.onStop()
    }
    private fun openLink(url: String) {
        if (!MarkdownSafety.safeLink(url)) return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        } catch (_: ActivityNotFoundException) { model.reportText(R.string.error_browser) }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
private fun MemoScreen(model: MemoModel, onLink: (String) -> Unit) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var deleteConfirm by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentContext = rememberUpdatedState(context)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val payload = model.takeExportData()
        if (uri != null && payload != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = Base64.decode(payload.getString("bytes_base64"), Base64.DEFAULT)
                        try {
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { stream ->
                                stream.write(bytes)
                                stream.flush()
                            }
                        } catch (error: Exception) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                            throw error
                        }
                    }
                }.onSuccess {
                    android.widget.Toast.makeText(
                        context,
                        currentContext.value.getString(R.string.export_success),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                    .onFailure { error ->
                        val resource = when (error) {
                            is SecurityException -> R.string.export_permission
                            is java.io.FileNotFoundException -> R.string.export_missing
                            is java.io.IOException -> R.string.export_io
                            else -> R.string.export_incomplete
                        }
                        model.reportText(resource)
                    }
            }
        }
    }
    BackHandler(model.editorOpen) { model.backToList() }
    val profile = model.state.optJSONObject("profile")
    val readOnly = profile?.optBoolean("read_only") == true || model.selected?.optBoolean("deleted") == true
    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (model.editorOpen) {
                            TextButton(
                                onClick = { model.backToList() },
                                enabled = !model.busy
                            ) { Text(stringResource(R.string.back)) }
                        }
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { settings = true },
                            enabled = !model.busy
                        ) { Text(stringResource(R.string.settings)) }
                    }
                    Text(profileLabel(profile), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cloudLabel(model.state.optJSONObject("sync")),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { model.sync() },
                            enabled = !model.busy && profile?.optString("kind") == "account"
                        ) { Text(stringResource(R.string.sync)) }
                    }
                    if (
                        model.state.has("credential_persistent") && !model.state.optBoolean("credential_persistent") &&
                        profile?.optString("kind") == "account"
                    ) {
                        Text(stringResource(R.string.credential_volatile), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        model.showTrash(!model.trash)
                    }, enabled = !model.busy) {
                        Text(
                            if (model.trash) stringResource(R.string.back_notes) else stringResource(R.string.trash)
                        )
                    }
                    TextButton(onClick = {
                        scope.launch { model.export()?.let { exporter.launch(it.getString("filename")) } }
                    }, enabled = !model.busy) {
                        Text(stringResource(R.string.export_zip))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        preview = false
                        model.newNote()
                    }, enabled = !model.busy && !model.trash && profile?.optBoolean("read_only") != true) {
                        Text(
                            stringResource(R.string.new_note)
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.editorOpen) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        model.localStatus.resolve(context),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        color = if (model.saveFailed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    TextButton(onClick = {
                        model.retrySave()
                        preview = !preview
                    }) { Text(if (preview) stringResource(R.string.source) else stringResource(R.string.preview)) }
                    if (model.selected != null) {
                        TextButton(onClick = {
                            if (model.selected!!.optBoolean(
                                    "deleted"
                                )
                            ) {
                                model.setDeleted(false)
                            } else {
                                deleteConfirm = true
                            }
                        }, enabled = !model.busy) {
                            Text(
                                if (model.selected!!.optBoolean(
                                        "deleted"
                                    )
                                ) {
                                    stringResource(R.string.restore)
                                } else {
                                    stringResource(R.string.delete)
                                }
                            )
                        }
                    }
                }
                ConflictInfo(model.selected)
                if (model.saveFailed) {
                    Row {
                        TextButton(onClick = { model.retrySave() }) { Text(stringResource(R.string.retry_save)) }
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(model.content)) }
                        ) { Text(stringResource(R.string.copy_rescue)) }
                        TextButton(onClick = { model.copyAsNewNote() }) { Text(stringResource(R.string.save_as_new)) }
                    }
                }
                if (preview || readOnly) {
                    MarkdownPreview(
                        model.content,
                        editable = !readOnly && !model.busy,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onTask = model::toggleTask,
                        onLink = onLink
                    )
                } else {
                    SourceEditor(
                        model.content,
                        enabled = !model.busy,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onBlur = model::retrySave,
                        onComposition = model::composing,
                        onEdit = model::edit
                    )
                }
            } else {
                OutlinedTextField(value = model.query, onValueChange = model::search, label = {
                    Text(
                        if (model.trash) {
                            stringResource(
                                R.string.search_trash
                            )
                        } else {
                            stringResource(R.string.search_notes)
                        }
                    )
                }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
                if (model.notes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (model.query.isNotEmpty()) {
                                stringResource(
                                    R.string.no_results
                                )
                            } else if (model.trash) {
                                stringResource(
                                    R.string.empty_trash
                                )
                            } else {
                                stringResource(R.string.empty_notes)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(model.notes, key = { it.getString("id") }) { note ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        if (note.optString(
                                                "content"
                                            ).isBlank()
                                        ) {
                                            stringResource(R.string.untitled)
                                        } else {
                                            note.optString("title")
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        if (!note.isNull(
                                                "conflict"
                                            )
                                        ) {
                                            Text(
                                                stringResource(R.string.conflict_preserved),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Text(
                                            note.optString("content").replace('\n', ' '),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            formattedTime(note.optString("updated_at")),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                },
                                modifier = Modifier.clickable(enabled = !model.busy) {
                                    preview = false
                                    model.open(note)
                                },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
    if (settings) AccountSettings(model) { settings = false }
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = false
                    model.setDeleted(true)
                }) { Text(stringResource(R.string.move_trash)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteConfirm = false }
                ) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    model.message?.let { message ->
        AlertDialog(onDismissRequest = model::dismissMessage, title = {
            Text(stringResource(R.string.notice))
        }, text = {
            Text(
                message.resolve(context)
            )
        }, confirmButton = { TextButton(onClick = model::dismissMessage) { Text(stringResource(R.string.okay)) } })
    }
}

@Composable
private fun ConflictInfo(note: JSONObject?) {
    val conflict = note?.optJSONObject("conflict") ?: return
    val reason = stringResource(
        if (conflict.optString("reason") == "stale_delete") R.string.stale_delete else R.string.conflict_copy
    )
    val source = conflict.optString("source_note_id")
    val device = conflict.optString("source_device_name")
    val detected = formattedTime(conflict.optString("detected_at"))
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            stringResource(R.string.conflict_details, reason, source, device, detected),
            Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun cloudLabel(sync: JSONObject?): String = when (sync?.optString("state")) {
    "local" -> stringResource(R.string.cloud_local)
    "pending" -> pluralStringResource(R.plurals.cloud_pending, sync.optInt("pending"), sync.optInt("pending"))
    "syncing" -> stringResource(R.string.cloud_syncing)
    "synced" -> stringResource(R.string.cloud_synced, formattedTime(sync.optString("last_success")))
    "offline" -> stringResource(R.string.cloud_offline)
    "auth_required" -> stringResource(R.string.cloud_auth)
    "recovery_required" -> stringResource(R.string.cloud_recovery)
    "error" -> stringResource(R.string.cloud_error)
    else -> stringResource(R.string.cloud_opening)
}
