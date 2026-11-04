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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 原生主界面；退出到后台前请求本地提交，云端失败不阻止离线编辑。 */
class MainActivity : ComponentActivity() {
    private val model: MemoModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) {
                darkColorScheme(primary = Color(0xFF91D5BE))
            } else {
                lightColorScheme(
                    primary = Color(0xFF32695B),
                    secondary = Color(0xFF52645D),
                    background = Color(0xFFF8FAF7)
                )
            }
            MaterialTheme(colorScheme = colors) { MemoScreen(model, ::openLink) }
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
        } catch (_: ActivityNotFoundException) { model.report(IllegalStateException("No browser available")) }
    }
}

@Composable
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
private fun MemoScreen(model: MemoModel, onLink: (String) -> Unit) {
    var settings by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
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
                        "ZIP 已导出",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                    .onFailure { error ->
                        val message = when (error) {
                            is SecurityException -> "导出失败：目标位置没有写入权限。"
                            is java.io.FileNotFoundException -> "导出失败：目标文档不可写或已移除。"
                            is java.io.IOException -> "导出失败：目标存储无法完整写入，请检查空间和连接。"
                            else -> "导出失败：目标文档未完整写入，请重试。"
                        }
                        model.report(CoreFailure("EXPORT_WRITE_FAILED", message))
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
                            ) { Text("返回") }
                        }
                        Text("djhx 备忘录", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { settings = true }, enabled = !model.busy) { Text("账号 / 设置") }
                    }
                    Text(profile?.optString("label") ?: "本机笔记", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cloudLabel(model.state.optJSONObject("sync")),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { model.sync() },
                            enabled = !model.busy && profile?.optString("kind") == "account"
                        ) { Text("同步") }
                    }
                    if (
                        model.state.has("credential_persistent") && !model.state.optBoolean("credential_persistent") &&
                        profile?.optString("kind") == "account"
                    ) {
                        Text("安全存储不可用：凭据仅在本次进程有效，下次需重新登录。", color = MaterialTheme.colorScheme.error)
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
                    }, enabled = !model.busy) { Text(if (model.trash) "返回笔记" else "回收站") }
                    TextButton(onClick = {
                        scope.launch { model.export()?.let { exporter.launch(it.getString("filename")) } }
                    }, enabled = !model.busy) {
                        Text("导出 ZIP")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        preview = false
                        model.newNote()
                    }, enabled = !model.busy && !model.trash && profile?.optBoolean("read_only") != true) {
                        Text(
                            "＋ 新笔记"
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
                        model.localStatus,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        color = if (model.localStatus.startsWith(
                                "保存失败"
                            )
                        ) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    TextButton(onClick = {
                        model.retrySave()
                        preview = !preview
                    }) { Text(if (preview) "源码" else "预览") }
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
                            Text(if (model.selected!!.optBoolean("deleted")) "恢复" else "删除")
                        }
                    }
                }
                ConflictInfo(model.selected)
                if (model.localStatus.startsWith("保存失败")) {
                    Row {
                        TextButton(onClick = { model.retrySave() }) { Text("重试保存") }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(model.content)) }) { Text("复制救援") }
                        TextButton(onClick = { model.copyAsNewNote() }) { Text("另存新笔记") }
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
                    Text(if (model.trash) "搜索回收站" else "搜索笔记 · 支持一字中文")
                }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
                if (model.notes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (model.query.isNotEmpty()) "未找到匹配笔记" else if (model.trash) "回收站为空" else "随时记下想法\n点击「＋ 新笔记」开始，无需登录。",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(model.notes, key = { it.getString("id") }) { note ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        note.optString("title", "无标题"),
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
                                            Text("冲突副本 · 原笔记保留", color = MaterialTheme.colorScheme.error)
                                        }
                                        Text(
                                            note.optString("content").replace('\n', ' '),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(note.optString("updated_at"), style = MaterialTheme.typography.labelSmall)
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
            title = { Text("移到回收站？") },
            text = { Text("正文会保留，可以随时恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = false
                    model.setDeleted(true)
                }) { Text("移到回收站") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("取消") } }
        )
    }
    model.message?.let { message ->
        AlertDialog(onDismissRequest = model::dismissMessage, title = {
            Text("提示")
        }, text = { Text(message) }, confirmButton = { TextButton(onClick = model::dismissMessage) { Text("知道了") } })
    }
}

@Composable
private fun ConflictInfo(note: JSONObject?) {
    val conflict = note?.optJSONObject("conflict") ?: return
    val reason = if (conflict.optString("reason") == "stale_delete") "删除未执行，内容已保留为冲突副本" else "冲突副本"
    val source = conflict.optString("source_note_id")
    val device = conflict.optString("source_device_name")
    val detected = conflict.optString("detected_at")
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "$reason · 原笔记 $source\n来源设备 $device\n$detected",
            Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun cloudLabel(sync: JSONObject?): String = when (sync?.optString("state")) {
    "local" -> "仅本机 · 不会自动上传"
    "pending" -> "待同步 · ${sync.optInt("pending")} 项"
    "syncing" -> "同步中"
    "synced" -> "已同步 · ${sync.optString("last_success", "")}"
    "offline" -> "离线 · 本地编辑可继续"
    "auth_required" -> "需重新登录 · 本地内容保留"
    "recovery_required" -> "需恢复处理 · 旧空间已保全，请到设置选择恢复来源"
    "error" -> "同步失败 · 可以手动重试"
    else -> "正在打开本机空间"
}
