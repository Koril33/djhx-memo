package app.djhx.memo

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** UI 的未提交编辑缓冲。generation 来自 core；UI 不决定同步版本或冲突。 */
// 此处是 UI 错误边界，保留缓冲并只显示脱敏消息；协程取消仍向上抛出。
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class MemoModel(application: Application, private val gateway: CoreBridge) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as MemoApplication).gateway)
    var state by mutableStateOf(JSONObject())
        private set
    var notes by mutableStateOf(emptyList<JSONObject>())
        private set
    var selected by mutableStateOf<JSONObject?>(null)
        private set
    var editorOpen by mutableStateOf(false)
        private set
    var content by mutableStateOf("")
        private set
    var pending by mutableStateOf(false)
        private set
    var localStatus by mutableStateOf("未开始编辑")
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
        private set
    var trash by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var devices by mutableStateOf(emptyList<JSONObject>())
        private set
    private val saveMutex = Mutex()
    private var saveJob: Job? = null
    private var firstUncommittedAt = 0L
    private var revision = 0L
    private var compositionActive = false
    fun composing(active: Boolean) {
        compositionActive = active
        if (!active) lastEvent = ""
    }
    private var requestId = UUID.randomUUID().toString()
    private var creationContent: String? = null
    private var lastEvent = ""
    private var listRequest = 0L
    private var editorProfileId = "local"
    private val observedRedirects = mutableMapOf<String, String>()
    private var pendingExport: JSONObject? = null
    fun takeExportData(): JSONObject? = pendingExport.also { pendingExport = null }
    private fun currentProfileId() = state.optJSONObject("profile")?.optString("id") ?: "local"

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { refresh() }.onFailure { report(it) }
                delay(1000)
            }
        }
    }

    fun dismissMessage() { message = null }

    fun edit(text: String) {
        if (selected?.optBoolean("deleted") == true || state.optJSONObject("profile")?.optBoolean("read_only") == true) return
        if (text == content) return
        content = text.replace("\r\n", "\n").replace('\r', '\n')
        revision++
        if (!pending) firstUncommittedAt = SystemClock.elapsedRealtime()
        pending = true
        localStatus = "保存中"
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        val remaining = (1000L - (SystemClock.elapsedRealtime() - firstUncommittedAt)).coerceAtLeast(0)
        saveJob = viewModelScope.launch {
            delay(minOf(300L, remaining))
            saveJob = null
            flush()
        }
    }

    /** 只在事务成功后更新 saved 状态；调用期间的新输入留在缓冲中继续保存。 */
    suspend fun flush(): Boolean = saveMutex.withLock {
        if (!pending) return@withLock true
        try {
            while (pending) {
                if (selected == null && creationContent == null && content.isBlank()) {
                    pending = false
                    localStatus = "空白草稿"
                    return@withLock true
                }
                val text = content
                val committingRevision = revision
                val current = selected
                val result = if (current == null) {
                    // 原创建可能已经提交但回包未知。同 request_id 重试必须保留首次创建内容，
                    // 取得原 Note 后再按 generation 提交所有续写，包括用户清空正文的情况。
                    if (creationContent == null) creationContent = text
                    gateway.obj(
                        "create",
                        "profile_id" to editorProfileId,
                        "content" to creationContent,
                        "request_id" to requestId
                    )
                } else {
                    gateway.obj(
                        "save",
                        "profile_id" to editorProfileId,
                        "id" to current.getString("id"),
                        "expected_generation" to current.getString("generation"),
                        "content" to text
                    )
                }
                selected = result
                if (revision == committingRevision && result.optString("content") == text) {
                    pending = false
                    localStatus = "已保存到本机"
                } else {
                    localStatus = "保存中"
                }
            }
            runCatching { loadNotes() }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                report(it)
            }
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            localStatus = "保存失败 · 内容仍保留在编辑器"
            report(error)
            false
        }
    }

    private fun canRefreshEditor() = !pending && !compositionActive

    private suspend fun refresh() {
        val next = gateway.obj("state")
        state = next
        val redirects = next.optJSONObject("redirects")
        redirects?.keys()?.forEach { source ->
            val target = redirects.optString(source)
            val key = "${currentProfileId()}:$source"
            if (observedRedirects[key] == target) return@forEach
            if (selected?.optString("id") != source || currentProfileId() != editorProfileId) {
                observedRedirects[key] = target
                return@forEach
            }
            // 输入法组合期间不改变正文或保存基准，也不提前消费映射。
            if (compositionActive) return@forEach
            // 核心使用原 id + 原 generation 判断可否继续写入副本；目标已有独立更新时拒绝。
            // flush 自己持有 saveMutex，必须在锁外调用，不能从目标读取新 generation 后覆盖旧缓冲。
            if (pending && !flush()) return@forEach
            if (!canRefreshEditor()) return@forEach
            saveMutex.withLock {
                if (!canRefreshEditor()) return@withLock
                val currentId = selected?.optString("id")
                if (currentId != source && currentId != target) return@withLock
                val currentRevision = revision
                val fresh = gateway.obj("get", "profile_id" to editorProfileId, "id" to target)
                if (canRefreshEditor() && revision == currentRevision && selected?.optString("id") == currentId) {
                    selected = fresh
                    content = fresh.optString("content")
                    observedRedirects[key] = target
                }
            }
        }
        val event = next.optString("event_seq")
        if (event != lastEvent) {
            lastEvent = event
            loadNotes()
            if (selected != null && canRefreshEditor() && currentProfileId() == editorProfileId) {
                val currentRevision = revision
                val currentId = selected!!.getString("id")
                val fresh = gateway.obj("get", "profile_id" to editorProfileId, "id" to currentId)
                if (canRefreshEditor() && revision == currentRevision && selected?.optString("id") == currentId) {
                    selected = fresh
                    content = fresh.optString("content")
                }
            }
        }
    }

    private suspend fun loadNotes() {
        val request = ++listRequest
        val found = gateway.array(
            "list",
            "profile_id" to currentProfileId(),
            "query" to query,
            "deleted" to trash
        ).objects()
        if (request == listRequest) notes = found
    }

    fun search(value: String) {
        query = value
        viewModelScope.launch { runCatching { loadNotes() }.onFailure { report(it) } }
    }

    fun showTrash(value: Boolean) = action {
        if (!flush()) return@action
        trash = value
        editorOpen = false
        selected = null
        content = ""
        loadNotes()
    }

    fun newNote() = action {
        if (!flush()) return@action
        selected = null
        content = ""
        requestId = UUID.randomUUID().toString()
        creationContent = null
        editorProfileId = currentProfileId()
        editorOpen = true
        localStatus = "空白草稿"
        revision++
    }

    fun open(note: JSONObject) = action {
        if (!flush()) return@action
        editorProfileId = currentProfileId()
        selected = gateway.obj("get", "profile_id" to editorProfileId, "id" to note.getString("id"))
        content = selected!!.optString("content")
        editorOpen = true
        localStatus = "已保存到本机"
        revision++
    }

    fun backToList() = action { if (flush()) editorOpen = false }
    fun retrySave() = action { flush() }

    fun copyAsNewNote() = action {
        val text = content
        val saved = gateway.obj(
            "create",
            "profile_id" to currentProfileId(),
            "content" to text,
            "request_id" to UUID.randomUUID().toString()
        )
        selected = saved
        editorProfileId = currentProfileId()
        pending = false
        localStatus = "已保存为新笔记"
        loadNotes()
    }

    fun setDeleted(deleted: Boolean) = action {
        if (!flush()) return@action
        val current = selected ?: return@action
        gateway.obj(
            "set_deleted",
            "profile_id" to editorProfileId,
            "id" to current.getString("id"),
            "expected_generation" to current.getString("generation"),
            "deleted" to deleted,
        )
        editorOpen = false
        selected = null
        content = ""
        loadNotes()
    }

    fun toggleTask(line: Int) = action {
        if (!flush()) return@action
        val current = selected ?: return@action
        val next = gateway.obj(
            "toggle_task",
            "profile_id" to editorProfileId,
            "id" to current.getString("id"),
            "expected_generation" to current.getString("generation"),
            "line" to line,
        )
        selected = next
        content = next.getString("content")
        revision++
        localStatus = "已保存到本机"
        loadNotes()
    }

    fun sync() = action { if (flush()) gateway.call("sync") }

    fun switchProfile(profile: String) = action {
        if (!flush()) return@action
        gateway.call("switch", "profile_id" to profile)
        clearSelection()
        refreshAll()
    }

    fun authenticate(register: Boolean, server: String, username: String, password: String, invite: String, device: String) = action {
        if (!flush()) return@action
        val result = gateway.obj(
            if (register) "register" else "login",
            "server_url" to server,
            "username" to username,
            "password" to password,
            "invite_code" to invite,
            "device_name" to device,
        )
        clearSelection()
        refreshAll()
        message = if (result.optInt("import_count") > 0) {
            "登录成功。本机笔记尚未上传，可在账号设置明确选择复制导入；默认暂不导入。"
        } else {
            "登录成功"
        }
    }

    fun importLocal(sourceProfileId: String = "local") = action {
        if (!flush()) return@action
        val result = gateway.obj("import", "profile_id" to currentProfileId(), "source_profile_id" to sourceProfileId)
        message = "已复制导入 ${result.optInt("imported")} 条；原空间内容保留。"
        refreshAll()
    }

    fun logout() = action {
        if (!flush()) return@action
        val result = gateway.obj("logout")
        clearSelection()
        refreshAll()
        message = if (result.optBoolean("remote_revoked")) {
            "已注销，账号本地数据保留"
        } else {
            "已清除本机凭据；远程吊销尚未完成，请从另一已登录设备吊销此设备。"
        }
    }

    fun loadDevices() = action { devices = gateway.array("devices").objects() }
    fun revokeDevice(id: String) = action {
        gateway.call("revoke_device", "id" to id)
        refreshAll()
        devices = runCatching { gateway.array("devices").objects() }.getOrDefault(emptyList())
        message = "设备凭据已吊销"
    }

    suspend fun export(): JSONObject? {
        if (busy) return null
        busy = true
        return try {
            if (flush()) {
                gateway.obj(
                    "export",
                    "profile_id" to currentProfileId(),
                    "include_deleted" to trash,
                    "deleted_only" to trash
                )
                    .also { pendingExport = it }
            } else {
                null
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            report(error)
            null
        } finally {
            busy = false
        }
    }

    fun foreground(active: Boolean) {
        viewModelScope.launch {
            if (!active) flush()
            runCatching { gateway.call("foreground", "active" to active) }.onFailure { report(it) }
        }
    }

    private fun clearSelection() {
        editorOpen = false
        selected = null
        content = ""
        pending = false
        creationContent = null
        notes = emptyList()
        devices = emptyList()
        listRequest++
        compositionActive = false
        query = ""
        trash = false
        revision++
    }

    private suspend fun refreshAll() {
        lastEvent = ""
        refresh()
        loadNotes()
    }

    private fun action(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try { block() } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                report(error)
            } finally { busy = false }
        }
    }

    fun report(error: Throwable) {
        message = if (error is CoreFailure) "${error.message}（${error.code}）" else "操作未完成，请重试；编辑内容仍保留。"
    }
}
