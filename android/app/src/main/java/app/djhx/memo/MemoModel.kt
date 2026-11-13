package app.djhx.memo

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** UI 的未提交编辑缓冲。generation 来自 core；UI 不决定同步版本或冲突。 */
// 此处是 UI 错误边界，保留缓冲并只显示脱敏消息；协程取消仍向上抛出。
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
class MemoModel(application: Application, private val gateway: CoreBridge) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as MemoApplication).gateway)

    internal suspend fun highlightCode(source: String): CodeHighlights {
        val result = gateway.obj("highlight_code", "source" to source)
        return withContext(Dispatchers.Default) { CodeHighlights(result) }
    }

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
    var localStatus by mutableStateOf(UiText(R.string.save_idle))
        private set
    var message by mutableStateOf<UiText?>(null)
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

    /** Installation consent temporarily locks editing without dropping its durable buffer. */
    val updateLocked: Boolean get() = (getApplication<Application>() as? MemoApplication)?.updates?.installing == true

    /** Refuses handoff while composition or another UI operation is unresolved. */
    suspend fun prepareUpdate(): Boolean {
        if (busy || compositionActive || !flush()) return false
        val native = gateway as? CoreGateway ?: return false
        native.pauseForUpdate()
        return true
    }
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
                if (!updateLocked) runCatching { refresh() }.onFailure { report(it) }
                delay(1000)
            }
        }
    }

    val saveFailed: Boolean get() = localStatus.resource == R.string.save_failed

    fun reportText(resource: Int) { message = UiText(resource) }

    fun dismissMessage() { message = null }

    fun edit(text: String) {
        if (updateLocked) return
        if (selected?.optBoolean("deleted") == true || state.optJSONObject("profile")?.optBoolean("read_only") == true) return
        if (text == content) return
        content = text.replace("\r\n", "\n").replace('\r', '\n')
        revision++
        if (!pending) firstUncommittedAt = SystemClock.elapsedRealtime()
        pending = true
        localStatus = UiText(R.string.save_pending)
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
        if (selected?.optString("purge_state") == "purged") {
            localStatus = UiText(R.string.save_failed)
            message = UiText(R.string.note_purged)
            return@withLock false
        }
        try {
            while (pending) {
                if (selected == null && creationContent == null && content.isBlank()) {
                    pending = false
                    localStatus = UiText(R.string.save_blank)
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
                if (selected?.optString("purge_state") == "purged") throw CoreFailure("NOTE_PURGED", "")
                selected = result
                if (revision == committingRevision && result.optString("content") == text) {
                    pending = false
                    localStatus = UiText(R.string.save_success)
                } else {
                    localStatus = UiText(R.string.save_pending)
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
            localStatus = UiText(R.string.save_failed)
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
            refreshSelected()
        }
    }

    private suspend fun refreshSelected() {
        val currentId = selected?.optString("id") ?: return
        val profileId = editorProfileId
        if (currentProfileId() != profileId) return
        val currentRevision = revision
        val fresh = gateway.obj("get", "profile_id" to profileId, "id" to currentId)
        // Equal note IDs in different profiles do not identify the same note.
        // A permanent deletion bypasses the input revision barrier, but never this ownership check.
        if (editorProfileId != profileId || currentProfileId() != profileId || selected?.optString("id") != currentId) return
        if (fresh.optString("purge_state") == "purged") {
            selected = fresh
            saveJob?.cancel()
            saveJob = null
            if (pending || compositionActive) {
                localStatus = UiText(R.string.save_failed)
                message = UiText(R.string.note_purged)
            } else {
                content = ""
            }
        } else if (canRefreshEditor() && revision == currentRevision) {
            selected = fresh
            content = fresh.optString("content")
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
        localStatus = UiText(R.string.save_blank)
        revision++
    }

    fun open(note: JSONObject) = action {
        if (!flush()) return@action
        editorProfileId = currentProfileId()
        selected = gateway.obj("get", "profile_id" to editorProfileId, "id" to note.getString("id"))
        content = selected!!.optString("content")
        editorOpen = true
        localStatus = UiText(R.string.save_success)
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
        localStatus = UiText(R.string.save_new)
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

    /** Confirmation captures the original note/version; a later selection cannot retarget deletion. */
    fun purge(target: JSONObject) = action {
        if (!flush()) return@action
        val result = gateway.obj(
            "purge",
            "profile_id" to target.getString("profile_id"),
            "id" to target.getString("id"),
            "expected_generation" to target.getString("generation")
        )
        editorOpen = false
        selected = null
        content = ""
        message = UiText(if (result.optString("purge_state") == "purged") R.string.purge_done else R.string.purge_pending)
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
        localStatus = UiText(R.string.save_success)
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
            UiText(R.string.login_import_available)
        } else {
            UiText(R.string.login_success)
        }
    }

    fun importLocal(sourceProfileId: String = "local") = action {
        if (!flush()) return@action
        val result = gateway.obj("import", "profile_id" to currentProfileId(), "source_profile_id" to sourceProfileId)
        message = UiText(R.plurals.import_success, listOf(result.optInt("imported")), result.optInt("imported"))
        refreshAll()
    }

    fun logout() = action {
        if (!flush()) return@action
        val result = gateway.obj("logout")
        clearSelection()
        refreshAll()
        message = if (result.optBoolean("remote_revoked")) {
            UiText(R.string.logout_success)
        } else {
            UiText(R.string.logout_remote_pending)
        }
    }

    fun loadDevices() = action { devices = gateway.array("devices").objects() }
    fun revokeDevice(id: String) = action {
        gateway.call("revoke_device", "id" to id)
        refreshAll()
        devices = runCatching { gateway.array("devices").objects() }.getOrDefault(emptyList())
        message = UiText(R.string.revoke_success)
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
        if (updateLocked) return
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
        if (busy || updateLocked) return
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
        message = UiText(errorResource((error as? CoreFailure)?.code))
    }
}
