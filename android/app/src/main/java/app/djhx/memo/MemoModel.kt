package app.djhx.memo

import android.app.Application
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** UI 的未提交编辑缓冲。generation 来自 core；UI 不决定同步版本或冲突。 */
// 此处是 UI 错误边界，保留缓冲并只显示脱敏消息；协程取消仍向上抛出。
@Suppress("TooGenericExceptionCaught", "TooManyFunctions", "SwallowedException")
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
    var searchQuery by mutableStateOf("")
        private set
    var searchHits by mutableStateOf(emptyList<JSONObject>())
        private set
    var searchTruncated by mutableStateOf(false)
        private set
    /** 请求时捕获的 profile_id → 来源展示文案快照；迟到回执与切空间后整体作废。 */
    var searchSources by mutableStateOf(emptyMap<String, String>())
        private set
    var searchError by mutableStateOf(false)
        private set
    var searchLoading by mutableStateOf(false)
        private set
    var findOpen by mutableStateOf(false)
        private set
    var findQuery by mutableStateOf("")
        private set
    var trash by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var devices by mutableStateOf(emptyList<JSONObject>())
        private set
    var devicesLoading by mutableStateOf(false)
        private set
    var devicesRefreshing by mutableStateOf(false)
        private set
    var devicesError by mutableStateOf(false)
        private set
    var devicesStale by mutableStateOf(false)
        private set
    var revoking by mutableStateOf(false)
        private set
    var revokeError by mutableStateOf(false)
        private set
    var selecting by mutableStateOf(false)
        private set
    var selection by mutableStateOf(emptySet<String>())
        private set

    /**
     * deleted = 撤销时提交的删除方向：移入回收站后撤销为恢复(false)，
     * 批量恢复后撤销为重新移回(true)。
     */
    data class TrashUndo(
        val profile: String,
        val notes: List<JSONObject>,
        val serial: Long,
        val failed: Int = 0,
        val deleted: Boolean = false
    )
    var trashUndo by mutableStateOf<TrashUndo?>(null)
        private set
    var actionNotice by mutableStateOf<UiText?>(null)
        private set
    fun dismissActionNotice(notice: UiText) { if (actionNotice == notice) actionNotice = null }
    private var undoSerial = 0L
    private val markdownImport = MarkdownImportSession(gateway)

    /** 目录跳转目标；serial 区分重复点击，noteId 判断跨笔记过期。 */
    data class OutlineTarget(val offset: Int, val serial: Long, val noteId: String)
    var headings by mutableStateOf(emptyList<OutlineHeading>())
        private set
    var outlineTarget by mutableStateOf<OutlineTarget?>(null)
        private set
    private var outlineRevision = 0L
    private var outlineSerial = 0L
    private var outlineJob: Job? = null

    /** 防抖解析标题目录（Dispatchers.Default + 版本号作废），大笔记不逐事件重解析。 */
    fun refreshOutline() {
        outlineJob?.cancel()
        val request = ++outlineRevision
        outlineJob = viewModelScope.launch {
            delay(120)
            val noteId = selected?.optString("id")
            val source = content
            val parsed = withContext(Dispatchers.Default) { MarkdownSafety.headings(source) }
            if (request == outlineRevision && selected?.optString("id") == noteId) headings = parsed
        }
    }

    /** 由底部弹层点击触发：把逻辑行换算为字符偏移，供源码编辑区滚动定位。 */
    fun requestOutlineJump(line: Int) {
        val note = selected ?: return
        val offset = content.split('\n').take(line).sumOf { it.length + 1 }.coerceAtMost(content.length)
        outlineTarget = OutlineTarget(offset, ++outlineSerial, note.getString("id"))
    }

    fun consumeOutlineTarget() { outlineTarget = null }

    fun cancelSelection() {
        selecting = false
        selection = emptySet()
    }
    fun toggleSelection(id: String) {
        if (busy) return
        // 0.2.7：回收站同样支持多选，仅只读空间仍拦截。
        if (state.optJSONObject("profile")?.optBoolean("read_only") == true) return
        selecting = true
        selection = if (id in selection) selection - id else selection + id
    }
    fun selectAll() {
        if (busy) return
        selecting = true
        selection = notes.map { it.getString("id") }.toSet()
    }
    fun toggleAllSelection() {
        if (busy) return
        val ids = notes.map { it.getString("id") }.toSet()
        selection = if (selection == ids) emptySet() else ids
    }
    fun dismissTrashUndo() { trashUndo = null }

    /** Every result carries the committed generation, used unchanged by undo. */
    fun trashNotes(targets: List<JSONObject>, mergeUndo: Boolean = false) = action {
        val profile = currentProfileId()
        if (compositionActive) {
            reportText(R.string.finish_composition)
            return@action
        }
        if (!flush()) return@action
        val items = targets.map { original ->
            val note = selected?.takeIf { it.optString("id") == original.optString("id") } ?: original
            JSONObject().put("id", note.getString("id")).put("expected_generation", note.getString("generation"))
        }
        val results = gateway.obj(
            "batch_set_deleted",
            "profile_id" to profile,
            "deleted" to true,
            "items" to JSONArray(items)
        )
            .getJSONArray("results").objects()
        val saved = results.filter { it.optBoolean("ok") }.map { it.getJSONObject("note") }
        if (currentProfileId() != profile) return@action
        selection = results.filterNot { it.optBoolean("ok") }.map { it.getString("id") }.toSet()
        selecting = selection.isNotEmpty()
        if (saved.any { it.getString("id") == selected?.optString("id") }) {
            selected = null
            content = ""
            editorOpen = false
        }
        if (saved.isNotEmpty()) {
            val previous = trashUndo?.takeIf { mergeUndo && it.profile == profile && !it.deleted }?.notes.orEmpty()
            trashUndo = TrashUndo(
                profile,
                (previous + saved).associateBy {
                    it.getString("id")
                }.values.toList(),
                ++undoSerial, results.size - saved.size, deleted = false
            )
        }
        if (saved.isEmpty() && results.isNotEmpty()) {
            actionNotice = UiText(R.string.batch_result, listOf(0, results.size))
        }
        loadNotes()
    }

    fun undoTrash() {
        val group = trashUndo ?: return
        trashUndo = null
        viewModelScope.launch {
            while (busy || updateLocked) delay(50)
            if (group.profile == currentProfileId()) restoreTrashGroup(group)
        }
    }

    /** 回收站批量恢复；撤销 = 重新移回回收站（deleted:true），仅撤销成功项并使用成功后代次。 */
    fun restoreTrashNotes(targets: List<JSONObject>) = action {
        val profile = currentProfileId()
        if (compositionActive) {
            reportText(R.string.finish_composition)
            return@action
        }
        if (!flush()) return@action
        val items = targets.map { original ->
            val note = selected?.takeIf { it.optString("id") == original.optString("id") } ?: original
            JSONObject().put("id", note.getString("id")).put("expected_generation", note.getString("generation"))
        }
        val results = gateway.obj(
            "batch_set_deleted",
            "profile_id" to profile,
            "deleted" to false,
            "items" to JSONArray(items)
        ).getJSONArray("results").objects()
        val saved = results.filter { it.optBoolean("ok") }.map { it.getJSONObject("note") }
        if (currentProfileId() != profile) return@action
        selection = results.filterNot { it.optBoolean("ok") }.map { it.getString("id") }.toSet()
        selecting = selection.isNotEmpty()
        if (saved.any { it.getString("id") == selected?.optString("id") }) {
            selected = null
            content = ""
            editorOpen = false
        }
        if (saved.isNotEmpty()) {
            trashUndo = TrashUndo(profile, saved, ++undoSerial, results.size - saved.size, deleted = true)
        }
        loadNotes()
    }

    /** 回收站批量彻底删除；快照在打开确认框时冻结，确认后逐项提交，不可撤销。 */
    fun purgeNotes(targets: List<JSONObject>) = action {
        val profile = currentProfileId()
        if (compositionActive) {
            reportText(R.string.finish_composition)
            return@action
        }
        if (!flush()) return@action
        val items = targets.map { target ->
            val note = selected?.takeIf { it.optString("id") == target.optString("id") } ?: target
            JSONObject().put("id", note.getString("id")).put("expected_generation", note.getString("generation"))
        }
        val results = gateway.obj(
            "batch_purge",
            "profile_id" to profile,
            "items" to JSONArray(items)
        ).getJSONArray("results").objects()
        if (currentProfileId() != profile) return@action
        val purged = results.count { it.optBoolean("ok") && it.optString("purge_state") == "purged" }
        val pending = results.count { it.optBoolean("ok") && it.optString("purge_state") == "pending" }
        val currentId = selected?.optString("id")
        if (currentId != null && results.any { it.optString("id") == currentId && it.optBoolean("ok") }) {
            selected = null
            content = ""
            editorOpen = false
        }
        cancelSelection()
        val account = state.optJSONObject("profile")?.optString("kind") == "account"
        actionNotice = if (account) {
            UiText(R.string.purged_pending, listOf(purged, pending))
        } else {
            UiText(R.string.purged_count, listOf(purged))
        }
        loadNotes()
    }

    private fun restoreTrashGroup(group: TrashUndo) = action {
        if (group.profile != currentProfileId()) return@action
        val items = group.notes.map {
            JSONObject().put(
                "id",
                it.getString("id")
            ).put("expected_generation", it.getString("generation"))
        }
        val results = gateway.obj(
            "batch_set_deleted",
            "profile_id" to group.profile,
            "deleted" to group.deleted,
            "items" to JSONArray(items)
        ).getJSONArray("results").objects()
        val restored = results.count { it.optBoolean("ok") }
        actionNotice = if (restored == results.size) {
            UiText(R.string.restored_count, listOf(restored))
        } else {
            UiText(R.string.undo_result, listOf(restored, results.size - restored))
        }
        loadNotes()
    }

    /** Picker ownership and unknown receipts survive rotation in the ViewModel session. */
    fun beginMarkdownImport(): String? {
        if (busy || updateLocked || state.optJSONObject("profile")?.optBoolean("read_only") == true) return null
        markdownImport.pending?.takeIf { it.bytes != null }?.let {
            completeMarkdownImport(it.id, null, null)
            return null
        }
        return markdownImport.begin(currentProfileId())
    }
    fun cancelMarkdownImport(token: String? = null) = markdownImport.cancel(token)
    fun completeMarkdownImport(token: String, filename: String?, bytes: ByteArray?) {
        val ready = try {
            markdownImport.accept(token, currentProfileId(), filename, bytes)
        } catch (error: CoreFailure) {
            report(error)
            null
        } ?: return
        action {
            if (compositionActive) {
                reportText(R.string.finish_composition)
                return@action
            }
            if (!flush()) return@action
            val note = markdownImport.commit(ready)
            if (currentProfileId() != ready.profile) return@action
            selected = note
            content = note.getString("content")
            editorProfileId = ready.profile
            editorOpen = true
            trash = false
            query = ""
            revision++
            cancelSelection()
            localStatus = UiText(R.string.save_success)
            actionNotice = UiText(R.string.markdown_imported)
            refreshOutline()
            loadNotes()
        }
    }
    private val saveMutex = Mutex()
    private var saveJob: Job? = null
    private var firstUncommittedAt = 0L
    private var revision = 0L
    private var compositionActive = false

    /** Installation consent temporarily locks editing without dropping its durable buffer. */
    val updateLocked: Boolean get() = (getApplication<Application>() as? MemoApplication)?.updates?.installing == true

    /** Refuses handoff while composition or another UI operation is unresolved. */
    suspend fun prepareUpdate(): Boolean {
        if (busy || noteExport.writing) return false
        if (compositionActive || !flush()) return false
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
    private var searchRequest = 0L
    private var searchJob: Job? = null
    private var stateRequest = 0L
    private var editorProfileId = "local"
    private val observedRedirects = mutableMapOf<String, String>()
    private var devicesSession = 0L
    private var devicesJob: Job? = null
    private var largeNoteNotified = false
    private var pendingExport: JSONObject? = null
    internal val noteExport = NoteExportCoordinator(
        getApplication<Application>(),
        viewModelScope,
        { actionNotice = UiText(R.string.note_export_success) },
        ::reportText
    )
    internal val composingText: Boolean get() = compositionActive
    fun takeExportData(): JSONObject? = pendingExport.also { pendingExport = null }
    private fun currentProfileId() = state.optJSONObject("profile")?.optString("id") ?: "local"

    init {
        viewModelScope.launch {
            while (true) {
                if (!updateLocked && !busy) runCatching { refresh() }.onFailure { report(it) }
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
        noteLargeNoteIfNeeded()
        refreshOutline()
        scheduleSave()
    }

    /** 大笔记降级只在首个超过阈值时给出一次轻量提示，不重复弹框。 */
    private fun noteLargeNoteIfNeeded() {
        if (largeNoteNotified) return
        if (isLargeNote(content)) {
            largeNoteNotified = true
            actionNotice = UiText(R.string.large_note_simplified)
        }
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
        val request = ++stateRequest
        val next = gateway.obj("state")
        if (request != stateRequest) return
        val previousProfileId = currentProfileId()
        val nextProfileId = next.optJSONObject("profile")?.optString("id")
        state = next
        if (previousProfileId != nextProfileId) {
            cancelSelection()
            trashUndo = null
            actionNotice = null
            markdownImport.cancel()
            invalidateSearch()
            invalidateDevices()
            val pendingQuery = searchQuery
            if (pendingQuery.isNotBlank()) searchNotes(pendingQuery)
        }
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
                if (request != stateRequest) return@withLock
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
        val request = stateRequest
        val currentId = selected?.optString("id") ?: return
        val profileId = editorProfileId
        if (currentProfileId() != profileId) return
        val currentRevision = revision
        val fresh = gateway.obj("get", "profile_id" to profileId, "id" to currentId)
        // Equal note IDs in different profiles do not identify the same note.
        // A permanent deletion bypasses the input revision barrier, but never this ownership check.
        if (request != stateRequest) return
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
            refreshOutline()
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
        if (request == listRequest) {
            notes = found
            selection = selection.intersect(found.map { it.getString("id") }.toSet())
        }
    }

    fun search(value: String) {
        if (busy) return
        cancelSelection()
        query = value
        viewModelScope.launch { runCatching { loadNotes() }.onFailure { report(it) } }
    }

    /** 全局搜索入口：固定范围（本机 + 当前账号，含两者普通回收站），防抖 150ms 后调用 core `search_notes`。 */
    fun searchNotes(value: String) {
        searchQuery = value
        searchJob?.cancel()
        if (value.isBlank()) {
            searchRequest++
            searchHits = emptyList()
            searchTruncated = false
            searchSources = emptyMap()
            searchError = false
            searchLoading = false
            searchJob = null
            return
        }
        searchLoading = true
        searchJob = viewModelScope.launch {
            delay(150)
            runSearch()
        }
    }

    /** 当前查询与固定范围失败后立即重试（跳过防抖）。 */
    fun retrySearch() {
        if (searchQuery.isBlank()) return
        searchJob?.cancel()
        searchLoading = true
        searchJob = viewModelScope.launch { runSearch() }
    }

    private suspend fun runSearch() {
        val request = ++searchRequest
        // 同一快照内取得 selected 与来源，保证请求锚点与来源展示一致；晚到回执按序号作废。
        val profileId = currentProfileId()
        val sources = sourceSnapshot()
        try {
            val scope = JSONObject().put("kind", "local_and_current_account").put("include_deleted", true)
            val result = gateway.obj(
                "search_notes",
                "query" to searchQuery,
                "profile_id" to profileId,
                "scope" to scope
            )
            if (request == searchRequest) {
                searchHits = result.optJSONArray("hits")?.objects().orEmpty()
                searchTruncated = result.optBoolean("truncated")
                searchSources = sources
                searchError = false
                searchLoading = false
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (request == searchRequest) {
                searchHits = emptyList()
                searchTruncated = false
                searchSources = emptyMap()
                searchError = true
                searchLoading = false
            }
        }
    }

    /** 从一次 registry 快照解析各 profile 的来源展示文案，供命中结果按 profile_id 取用。 */
    private fun sourceSnapshot(): Map<String, String> {
        val context = getApplication<Application>()
        return state.optJSONArray("profiles")?.objects().orEmpty().associate { profile ->
            profile.optString("id") to sourceLabel(profile, context)
        }
    }

    private fun sourceLabel(profile: JSONObject, context: Context): String = when (profile.optString("kind")) {
        "account" -> {
            val identity = accountSourceIdentity(profile.optString("username"), profile.optString("label"))
            val host = serverHostDisplay(profile.optString("server_url"))
            if (identity == null || host == null) context.getString(R.string.search_source_account)
            else "$identity · $host"
        }
        else -> context.getString(R.string.search_source_local)
    }

    /** 空间切换、登出或快照失效时作废旧回执与来源快照，避免旧结果覆盖新状态。 */
    private fun invalidateSearch() {
        searchRequest++
        searchJob?.cancel()
        searchJob = null
        searchHits = emptyList()
        searchSources = emptyMap()
        searchTruncated = false
        searchError = false
        searchLoading = false
    }

    /** 打开全局搜索命中：必要时先切空间，取全文后进入编辑器并注入查询供查找条定位首个命中。 */
    fun openSearchHit(hit: JSONObject) = action {
        if (!flush()) return@action
        val profileId = hit.optString("profile_id")
        if (profileId.isNotEmpty() && profileId != currentProfileId()) {
            gateway.call("switch", "profile_id" to profileId)
            state = gateway.obj("state")
        }
        editorProfileId = if (profileId.isEmpty()) currentProfileId() else profileId
        selected = gateway.obj("get", "profile_id" to editorProfileId, "id" to hit.getString("note_id"))
        content = selected!!.optString("content")
        editorOpen = true
        localStatus = UiText(R.string.save_success)
        revision++
        largeNoteNotified = false
        noteLargeNoteIfNeeded()
        refreshOutline()
        findQuery = searchQuery
        findOpen = true
        loadNotes()
    }

    fun openFind() {
        findOpen = true
    }

    fun closeFind() {
        findOpen = false
        findQuery = ""
    }

    fun updateFindQuery(value: String) {
        findQuery = value
    }

    fun showTrash(value: Boolean) = action {
        if (!flush()) return@action
        cancelSelection()
        trash = value
        editorOpen = false
        selected = null
        content = ""
        query = ""
        closeFind()
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
        closeFind()
        localStatus = UiText(R.string.save_blank)
        revision++
        largeNoteNotified = false
        refreshOutline()
    }

    fun open(note: JSONObject) = action {
        if (!flush()) return@action
        editorProfileId = currentProfileId()
        selected = gateway.obj("get", "profile_id" to editorProfileId, "id" to note.getString("id"))
        content = selected!!.optString("content")
        editorOpen = true
        closeFind()
        localStatus = UiText(R.string.save_success)
        revision++
        largeNoteNotified = false
        noteLargeNoteIfNeeded()
        refreshOutline()
    }

    fun backToList() = action { if (flush()) { editorOpen = false; closeFind() } }
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

    fun setDeleted(deleted: Boolean) {
        if (deleted) {
            selected?.let { trashNotes(listOf(it)) }
            return
        }
        restoreSelected()
    }
    private fun restoreSelected() = action {
        if (!flush()) return@action
        val current = selected ?: return@action
        gateway.obj(
            "set_deleted",
            "profile_id" to editorProfileId,
            "id" to current.getString("id"),
            "expected_generation" to current.getString("generation"),
            "deleted" to false,
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
        refreshOutline()
        loadNotes()
    }

    fun sync() = action { if (flush()) gateway.call("sync") }

    fun switchProfile(profile: String) = action {
        if (!flush()) return@action
        gateway.call("switch", "profile_id" to profile)
        clearSelection()
        refreshAll()
    }

    private val accounts =
        AccountActions(
            gateway,
            ::flush,
            ::refreshAll,
            ::clearSelection,
            { message = it },
            { state },
            ::currentProfileId
        )

    /** Completes only after the auth receipt and refreshed target agree; failures keep the form. */
    fun authenticate(
        register: Boolean,
        server: String,
        username: String,
        password: String,
        invite: String,
        device: String,
        onSuccess: () -> Unit = {}
    ) = action {
        accounts.authenticate(register, server, username, password, invite, device, onSuccess)
    }

    fun importLocal(sourceProfileId: String = "local") = action { accounts.importLocal(sourceProfileId) }

    fun logout() = action { accounts.logout() }

    /**
     * 设备列表读取独立于全局 action/busy：进入页面立即可用，请求状态只作用于设备区域。
     * 请求携带发起时的 profile 与 [devicesSession] 锚点；晚到回执跨账号/会话作废。
     * 已有缓存时后台刷新（不闪空），无缓存时进入加载态；失败保留旧数据并标记未刷新。
     */
    fun loadDevices() {
        if (devicesLoading || devicesRefreshing || revoking) return
        val profile = currentProfileId()
        if (devices.isEmpty()) {
            devicesLoading = true
            devicesError = false
        } else {
            devicesRefreshing = true
        }
        devicesStale = false
        val request = ++devicesSession
        devicesJob?.cancel()
        devicesJob = viewModelScope.launch {
            try {
                val found = gateway.array("devices").objects()
                if (request != devicesSession || profile != currentProfileId()) return@launch
                devices = found
                devicesLoading = false
                devicesRefreshing = false
                devicesError = false
                devicesStale = false
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request != devicesSession || profile != currentProfileId()) return@launch
                devicesLoading = false
                devicesRefreshing = false
                if (devices.isEmpty()) devicesError = true else devicesStale = true
            }
        }
    }

    /** 吊销保留确认对话框与局部互斥；收到服务端成功回执前不显示完成，失败行内提示。 */
    fun revokeDevice(id: String) {
        if (revoking) return
        val profile = currentProfileId()
        val session = devicesSession
        revoking = true
        revokeError = false
        viewModelScope.launch {
            try {
                gateway.call("revoke_device", "id" to id)
                if (session != devicesSession || profile != currentProfileId()) return@launch
                devices = runCatching { gateway.array("devices").objects() }.getOrDefault(emptyList())
                actionNotice = UiText(R.string.revoke_success)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (session != devicesSession || profile != currentProfileId()) return@launch
                revokeError = true
            } finally {
                if (session == devicesSession) revoking = false
            }
        }
    }

    /** 切空间、注销或同账号重新登录后作废旧回执与设备缓存。 */
    private fun invalidateDevices() {
        devicesSession++
        devicesJob?.cancel()
        devicesJob = null
        devices = emptyList()
        devicesLoading = false
        devicesRefreshing = false
        devicesError = false
        devicesStale = false
        revoking = false
        revokeError = false
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
        cancelSelection()
        trashUndo = null
        actionNotice = null
        markdownImport.cancel()
        editorOpen = false
        selected = null
        content = ""
        pending = false
        creationContent = null
        notes = emptyList()
        invalidateDevices()
        listRequest++
        searchRequest++
        searchJob?.cancel()
        searchJob = null
        searchQuery = ""
        searchHits = emptyList()
        searchTruncated = false
        searchSources = emptyMap()
        searchError = false
        searchLoading = false
        findOpen = false
        findQuery = ""
        compositionActive = false
        query = ""
        trash = false
        largeNoteNotified = false
        headings = emptyList()
        outlineTarget = null
        outlineJob?.cancel()
        outlineJob = null
        revision++
    }

    private suspend fun refreshAll() {
        lastEvent = ""
        refresh()
        loadNotes()
    }

    private fun action(block: suspend () -> Unit) {
        if (busy || updateLocked) return
        busy = true
        stateRequest++
        listRequest++
        viewModelScope.launch {
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
