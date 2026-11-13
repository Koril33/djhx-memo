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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
        (application as MemoApplication).updates.foreground(this, true)
    }
    override fun onStop() {
        (application as MemoApplication).updates.foreground(this, false)
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
    var settingsTab by rememberSaveable { mutableStateOf("display") }
    var more by remember { mutableStateOf(false) }
    var statusDetails by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var purgeTarget by remember { mutableStateOf<org.json.JSONObject?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val accessibility = androidx.compose.ui.platform.LocalAccessibilityManager.current
    val undoLabel = stringResource(R.string.undo_trash)
    val undoGroup = model.trashUndo
    val undoText = if ((undoGroup?.failed ?: 0) > 0) {
        stringResource(R.string.batch_result, undoGroup?.notes?.size ?: 0, undoGroup?.failed ?: 0)
    } else {
        stringResource(R.string.trashed_count, undoGroup?.notes?.size ?: 0)
    }
    val actionNotice = model.actionNotice
    val noticeContext = LocalContext.current
    LaunchedEffect(actionNotice) {
        val notice = actionNotice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.resolve(noticeContext))
        model.dismissActionNotice(notice)
    }
    LaunchedEffect(undoGroup?.serial) {
        val group = undoGroup ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            8000L, containsIcons = true, containsText = true, containsControls = true
        ) ?: 8000L
        val timer = launch {
            kotlinx.coroutines.delay(timeout)
            snackbar.currentSnackbarData?.dismiss()
        }
        val result = snackbar.showSnackbar(
            undoText,
            undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite
        )
        timer.cancel()
        if (result == SnackbarResult.ActionPerformed) {
            model.undoTrash()
        } else if (model.trashUndo?.serial == group.serial) model.dismissTrashUndo()
    }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val menuConfiguration = LocalConfiguration.current
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
    var importConfirm by rememberSaveable { mutableStateOf(false) }
    var pickerToken by rememberSaveable { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val token = pickerToken
        pickerToken = null
        if (token == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            model.cancelMarkdownImport(token)
        } else {
            scope.launch {
                try {
                    val file = readMarkdownFile(context, uri)
                    model.completeMarkdownImport(token, file.first, file.second)
                } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: CoreFailure) {
                    model.cancelMarkdownImport(token)
                    model.report(error)
                } catch (_: Exception) {
                    model.cancelMarkdownImport(token)
                    model.reportText(R.string.import_read_failed)
                }
            }
        }
    }
    val launchImport: () -> Unit = {
        if (pickerToken == null) {
            model.beginMarkdownImport()?.let { token ->
                pickerToken = token
                importer.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream"))
            }
        }
    }
    BackHandler(model.selecting && !settings) { if (!model.busy) model.cancelSelection() }
    BackHandler(model.editorOpen && !settings) { model.backToList() }
    val profile = model.state.optJSONObject("profile")
    val readOnly = profile?.optBoolean("read_only") == true || model.selected?.optBoolean("deleted") == true
    val openSettings: (String) -> Unit = { tab ->
        settingsTab = tab
        settings = true
    }
    val export: () -> Unit = { scope.launch { model.export()?.let { exporter.launch(it.getString("filename")) } } }
    val statusLabel = stringResource(R.string.status_details)
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    actionColor = MaterialTheme.colorScheme.inversePrimary
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.statusBarsPadding().fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).background(
                        if (model.selecting) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
                        RoundedCornerShape(16.dp)
                    ).heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (model.selecting && !model.editorOpen) {
                        MemoIconButton(
                            R.drawable.ic_ui_x,
                            stringResource(R.string.cancel),
                            !model.busy
                        ) { model.cancelSelection() }
                        Text(
                            stringResource(R.string.selected_count, model.selection.size),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val allLabel = stringResource(R.string.select_all_results)
                        val selectedState = when {
                            model.selection.isEmpty() -> androidx.compose.ui.state.ToggleableState.Off
                            model.selection.size == model.notes.size -> androidx.compose.ui.state.ToggleableState.On
                            else -> androidx.compose.ui.state.ToggleableState.Indeterminate
                        }
                        TriStateCheckbox(
                            state = selectedState,
                            onClick = model::toggleAllSelection,
                            enabled = !model.busy && model.notes.isNotEmpty(),
                            modifier = Modifier.semantics { contentDescription = allLabel }
                        )
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
                            MemoIconButton(
                                R.drawable.ic_ui_trash_2,
                                stringResource(R.string.move_trash),
                                !model.busy && model.selection.isNotEmpty()
                            ) {
                                model.trashNotes(model.notes.filter { it.getString("id") in model.selection })
                            }
                        }
                    }
                    if (!model.selecting || model.editorOpen) {
                        if (model.editorOpen || model.trash) {
                            MemoIconButton(R.drawable.ic_ui_arrow_left, stringResource(R.string.back), !model.busy) {
                                if (model.editorOpen) model.backToList() else model.showTrash(false)
                            }
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .clickable { if (model.editorOpen) statusDetails = true else openSettings("spaces") }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                if (model.editorOpen) {
                                    shortProfileLabel(
                                        profile
                                    )
                                } else if (model.trash) {
                                    stringResource(
                                        R.string.trash
                                    )
                                } else {
                                    stringResource(R.string.app_name)
                                },
                                style = if (model.editorOpen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (model.editorOpen) compactSaveLabel(model) else shortProfileLabel(profile),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (model.saveFailed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (model.editorOpen) {
                            MemoIconButton(
                                if (preview) R.drawable.ic_ui_pencil else R.drawable.ic_ui_eye,
                                stringResource(if (preview) R.string.source else R.string.preview)
                            ) {
                                model.retrySave()
                                preview = !preview
                            }
                        } else {
                            MemoIconButton(
                                cloudIcon(model.state.optJSONObject("sync")),
                                statusLabel
                            ) { statusDetails = true }
                        }
                        Box {
                            MemoIconButton(
                                R.drawable.ic_ui_ellipsis,
                                stringResource(R.string.more_actions),
                                !model.busy
                            ) { more = true }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                CompositionLocalProvider(
                                    LocalContext provides context,
                                    LocalConfiguration provides menuConfiguration
                                ) {
                                    if (model.editorOpen && model.selected != null) {
                                        val deleted = model.selected!!.optBoolean("deleted")
                                        val purgeState = model.selected!!.optString("purge_state")
                                        MemoMenuItem(
                                            if (deleted) R.drawable.ic_ui_rotate_ccw else R.drawable.ic_ui_trash_2,
                                            stringResource(if (deleted) R.string.restore else R.string.delete),
                                            !model.busy && purgeState !in setOf("pending", "purged") &&
                                                profile?.optBoolean("read_only") != true
                                        ) {
                                            more = false
                                            model.setDeleted(!deleted)
                                        }
                                        if (deleted && purgeState != "purged") {
                                            MemoMenuItem(
                                                R.drawable.ic_ui_trash_2,
                                                stringResource(R.string.purge_note),
                                                !model.busy && purgeState != "pending" && profile?.optBoolean("read_only") != true
                                            ) {
                                                more = false
                                                purgeTarget = org.json.JSONObject(model.selected.toString())
                                                    .put("profile_id", profile!!.getString("id"))
                                            }
                                        }
                                    }
                                    if (!model.editorOpen) {
                                        MemoMenuItem(
                                            if (model.trash) R.drawable.ic_ui_arrow_left else R.drawable.ic_ui_trash_2,
                                            stringResource(if (model.trash) R.string.back_notes else R.string.trash)
                                        ) {
                                            more = false
                                            model.showTrash(!model.trash)
                                        }
                                    }
                                    MemoMenuItem(
                                        R.drawable.ic_ui_download,
                                        stringResource(R.string.import_markdown),
                                        !model.busy && profile?.optBoolean("read_only") != true
                                    ) {
                                        more = false
                                        if (profile?.optString("kind") == "account") importConfirm = true else launchImport()
                                    }
                                    MemoMenuItem(
                                        R.drawable.ic_ui_download,
                                        stringResource(
                                            if (model.trash) R.string.export_trash else R.string.export_space
                                        )
                                    ) {
                                        more = false
                                        export()
                                    }
                                    if (model.editorOpen) {
                                        MemoMenuItem(R.drawable.ic_ui_info, statusLabel) {
                                            more = false
                                            statusDetails = true
                                        }
                                    }
                                    MemoMenuItem(R.drawable.ic_ui_settings, stringResource(R.string.settings_short)) {
                                        more = false
                                        openSettings("display")
                                    }
                                }
                            }
                        }
                    }
                }
                AttentionBanner(model, onSettings = openSettings)
            }
        },
        floatingActionButton = {
            val canCreate = !model.editorOpen && !model.trash && !model.selecting
            if (canCreate && profile?.optBoolean("read_only") != true) {
                NewNoteButton(enabled = !model.busy) {
                    preview = false
                    model.newNote()
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().navigationBarsPadding().imePadding()) {
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.editorOpen) {
                val codeHighlights = rememberCodeHighlights(model.content, model)
                ConflictInfo(model.selected)
                when (model.selected?.optString("purge_state")) {
                    "pending" -> Text(stringResource(R.string.purge_pending), Modifier.padding(16.dp))
                    "rejected" -> Text(stringResource(R.string.purge_rejected), Modifier.padding(16.dp))
                    "purged" -> Text(stringResource(R.string.note_purged), Modifier.padding(16.dp))
                }
                if (model.saveFailed) {
                    SaveRescue(model, onCopy = { clipboard.setText(AnnotatedString(model.content)) })
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SourceEditor(
                        model.content,
                        enabled = !model.busy && !readOnly && !model.updateLocked,
                        codeSpans = codeHighlights?.sourceSpans.orEmpty(),
                        visible = !preview && !readOnly,
                        modifier = Modifier.fillMaxSize(),
                        onBlur = model::retrySave,
                        onComposition = model::composing,
                        onEdit = model::edit
                    )
                    if (preview || readOnly) {
                        MarkdownPreview(
                            model.content,
                            editable = !readOnly && !model.busy,
                            codeHighlights = codeHighlights,
                            modifier = Modifier.fillMaxSize(),
                            onTask = model::toggleTask,
                            onLink = onLink
                        )
                    }
                }
            } else {
                NoteList(model) { note ->
                    preview = false
                    model.open(note)
                }
            }
        }
    }
    if (importConfirm) {
        MemoAlertDialog(
            onDismissRequest = { importConfirm = false },
            title = { Text(stringResource(R.string.import_markdown)) },
            text = { Text(stringResource(R.string.import_cloud_help)) },
            confirmButton = {
                TextButton(onClick = {
                    importConfirm = false
                    launchImport()
                }) { Text(stringResource(R.string.okay)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { importConfirm = false }
                ) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (settings) AccountSettings(model, initialTab = settingsTab, onExport = export) { settings = false }
    if (statusDetails) {
        StatusDetails(model, onSpace = {
            statusDetails = false
            openSettings("spaces")
        }) { statusDetails = false }
    }
    purgeTarget?.let { target ->
        MemoAlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text(stringResource(R.string.purge_confirm)) },
            text = { Text(stringResource(R.string.purge_help)) },
            confirmButton = {
                TextButton(onClick = {
                    purgeTarget = null
                    model.purge(target)
                }) { Text(stringResource(R.string.purge_note)) }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    model.message?.let { message ->
        MemoAlertDialog(
            onDismissRequest = model::dismissMessage,
            title = { Text(stringResource(R.string.notice)) },
            text = { Text(message.resolve(context)) },
            confirmButton = { TextButton(onClick = model::dismissMessage) { Text(stringResource(R.string.okay)) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteList(model: MemoModel, onOpen: (JSONObject) -> Unit) {
    TextField(
        value = model.query, onValueChange = model::search,
        enabled = !model.busy,
        placeholder = { Text(stringResource(if (model.trash) R.string.search_trash else R.string.search_notes)) },
        leadingIcon = { MemoIcon(R.drawable.ic_ui_search) },
        trailingIcon = if (model.query.isNotEmpty()) {
            {
                MemoIconButton(
                    R.drawable.ic_ui_x,
                    stringResource(R.string.clear_search),
                    !model.busy
                ) { model.search("") }
            }
        } else {
            null
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp), singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
    )
    if (model.notes.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(
                    when {
                        model.query.isNotEmpty() -> R.string.no_results
                        model.trash -> R.string.empty_trash
                        else -> R.string.empty_notes_quiet
                    }
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(model.notes, key = { it.getString("id") }) { note ->
                SwipeNote(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    enabled = !model.trash && !model.selecting && !model.busy &&
                        model.state.optJSONObject("profile")?.optBoolean("read_only") != true,
                    onTrash = { model.trashNotes(listOf(note), mergeUndo = true) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(
                            enabled = !model.busy,
                            onLongClick = { model.toggleSelection(note.getString("id")) },
                            onClick = {
                                if (model.selecting) {
                                    model.toggleSelection(
                                        note.getString("id")
                                    )
                                } else {
                                    onOpen(note)
                                }
                            }
                        ).semantics { if (model.selecting) selected = note.optString("id") in model.selection }
                            .background(
                                if (note.optString("id") in model.selection) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.background
                                }
                            )
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        if (model.selecting) {
                            Box(Modifier.width(36.dp).padding(top = 2.dp)) {
                                Checkbox(
                                    enabled = !model.busy,
                                    checked = note.optString("id") in model.selection,
                                    onCheckedChange = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (note.optString(
                                        "content"
                                    ).isBlank()
                                ) {
                                    stringResource(R.string.untitled)
                                } else {
                                    note.optString("title")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val excerpt = noteExcerpt(note.optString("content"))
                            if (excerpt.isNotEmpty()) {
                                Text(
                                    excerpt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (note.optString("purge_state")) {
                                    "pending" -> Text(
                                        stringResource(R.string.purge_pending),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    "rejected" -> Text(
                                        stringResource(R.string.purge_rejected),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Text(
                                    formattedTime(note.optString("updated_at")),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!note.isNull(
                                        "conflict"
                                    )
                                ) {
                                    Text(
                                        stringResource(R.string.conflict_copy),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewNoteButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.new_note_quiet)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        FloatingActionButton(
            onClick = { if (enabled) onClick() },
            modifier = Modifier.navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(
                androidx.compose.ui.res.painterResource(R.drawable.ic_ui_plus),
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SaveRescue(model: MemoModel, onCopy: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(
            MaterialTheme.colorScheme.errorContainer
        ).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            stringResource(R.string.save_failed),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow {
            TextButton(onClick = model::retrySave) { Text(stringResource(R.string.retry_save)) }
            TextButton(onClick = onCopy) { Text(stringResource(R.string.copy_rescue)) }
            TextButton(onClick = model::copyAsNewNote) { Text(stringResource(R.string.save_as_new)) }
        }
    }
}

@Composable
private fun AttentionBanner(model: MemoModel, onSettings: (String) -> Unit) {
    val sync = model.state.optJSONObject("sync")?.optString("state")
    val volatile = model.state.has("credential_persistent") && !model.state.optBoolean("credential_persistent") &&
        model.state.optJSONObject("profile")?.optString("kind") == "account"
    val resource = when {
        volatile -> R.string.credential_volatile
        sync == "auth_required" -> R.string.cloud_auth
        sync == "recovery_required" -> R.string.cloud_recovery
        sync == "error" -> R.string.cloud_error
        else -> null
    }
    if (resource != null) {
        Row(
            Modifier.fillMaxWidth().clickable {
                if (sync == "error") model.sync() else onSettings(if (sync == "recovery_required") "data" else "spaces")
            }
                .background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MemoIcon(R.drawable.ic_ui_circle_alert, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                stringResource(resource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun compactSaveLabel(model: MemoModel): String = stringResource(
    when {
        model.saveFailed -> R.string.save_failed_short
        model.pending -> R.string.save_pending
        model.selected == null -> R.string.draft_short
        else -> R.string.saved_short
    }
)

private fun cloudIcon(sync: JSONObject?): Int = when (sync?.optString("state")) {
    "local" -> R.drawable.ic_ui_file_text
    "synced" -> R.drawable.ic_ui_cloud_check
    "offline" -> R.drawable.ic_ui_cloud_off
    "auth_required", "recovery_required", "error" -> R.drawable.ic_ui_circle_alert
    else -> R.drawable.ic_ui_cloud
}

@Composable
private fun StatusDetails(model: MemoModel, onSpace: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val sync = model.state.optJSONObject("sync")
    MemoAlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.status_details)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(shortProfileLabel(model.state.optJSONObject("profile")), color = MaterialTheme.colorScheme.primary)
            Column {
                Text(stringResource(R.string.local_status), style = MaterialTheme.typography.labelMedium)
                Text(
                    model.localStatus.resolve(context),
                    color = if (model.saveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            Column {
                Text(stringResource(R.string.cloud_status), style = MaterialTheme.typography.labelMedium)
                Text(cloudLabel(sync))
            }
            if (sync?.optString("state") != "local") {
                Text(
                    stringResource(R.string.last_sync, formattedTime(sync?.optString("last_success"))),
                    style = MaterialTheme.typography.bodySmall
                )
                sync?.optJSONObject("error")?.let {
                    Text(stringResource(errorResource(it.optString("code"))), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onSpace) { Text(stringResource(R.string.space_settings)) }
        }
    }, confirmButton = {
        TextButton(
            onClick = model::sync,
            enabled = !model.busy && model.state.optJSONObject("profile")?.optString("kind") == "account"
        ) {
            Text(stringResource(R.string.sync))
        }
    }, dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.done)) } })
}

@Composable
private fun ConflictInfo(note: JSONObject?) {
    val conflict = note?.optJSONObject("conflict") ?: return
    var details by remember { mutableStateOf(false) }
    val reason = stringResource(
        if (conflict.optString("reason") == "stale_delete") R.string.stale_delete else R.string.conflict_copy
    )
    Row(
        Modifier.fillMaxWidth().clickable {
            details = true
        }.background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemoIcon(R.drawable.ic_ui_circle_alert, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
    if (details) {
        MemoAlertDialog(onDismissRequest = {
            details = false
        }, title = { Text(stringResource(R.string.conflict_copy)) }, text = {
            Text(
                stringResource(
                    R.string.conflict_details,
                    reason,
                    conflict.optString("source_note_id"),
                    conflict.optString("source_device_name"),
                    formattedTime(conflict.optString("detected_at"))
                )
            )
        }, confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.done)) } })
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
