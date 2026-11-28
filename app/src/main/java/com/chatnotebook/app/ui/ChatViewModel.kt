package com.chatnotebook.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.chatnotebook.app.data.ChatRepository
import com.chatnotebook.app.data.EditRecord
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageRecord
import com.chatnotebook.app.data.QuoteRecord
import com.chatnotebook.app.data.ReactionRecord
import com.chatnotebook.app.data.REMINDER_EXPIRY_GRACE_MS
import com.chatnotebook.app.data.REMINDER_SCHEDULED
import com.chatnotebook.app.data.ReminderPreview
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.ReminderSaveOutcome
import com.chatnotebook.app.data.SessionAttachmentCleanupException
import com.chatnotebook.app.data.SessionDocumentRecord
import com.chatnotebook.app.data.SessionRecord
import com.chatnotebook.app.reminder.LocalReminderSuggestion
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val DEFAULT_CHAT_TITLE = "聊天笔记"
private const val MAX_IMAGE_ATTACHMENTS = 9

/** 通知定位消息后高亮背景的持续时间。 */
private const val HIGHLIGHT_DURATION_MS = 5_000L

enum class SessionSortOrder { Created, Modified }

/** 提醒对话框的目标：对话，或对话中的某条消息。 */
data class ReminderTarget(
    val sessionId: String,
    val sessionTitle: String,
    val messageId: String? = null,
    val messagePreview: String? = null,
    /** 本地识别的触发时间，作为创建面板的预填值；用户可在面板中修改。 */
    val suggestedAt: Long? = null,
)

/** 保存提醒后需要向系统请求的权限。 */
enum class ReminderPermission { Notifications, ExactAlarm }

data class ChatUiState(
    val directoryReady: Boolean = false,
    /** 启动时正在恢复已保存的记录目录；此时不展示目录提示，避免闪现。 */
    val restoringDirectory: Boolean = false,
    val sessions: List<SessionRecord> = emptyList(),
    val sessionSortOrder: SessionSortOrder = SessionSortOrder.Created,
    val activeSessionId: String? = null,
    val activeTitle: String = DEFAULT_CHAT_TITLE,
    val messages: List<MessageRecord> = emptyList(),
    /** 本地识别的日程建议，键为消息 id；只存在于内存，不写入会话文件、快照或同步载荷。 */
    val reminderSuggestions: Map<String, LocalReminderSuggestion> = emptyMap(),
    val resultSessionId: String? = null,
    val resultDocument: SessionDocumentRecord? = null,
    val resultMessages: List<MessageRecord> = emptyList(),
    val unreadOrganizationSessions: Set<String> = emptySet(),
    val organizingSessions: Set<String> = emptySet(),
    val resultError: String? = null,
    val organizationErrors: Map<String, String> = emptyMap(),
    val quotes: Map<String, QuoteRecord> = emptyMap(),
    val reactions: Map<String, List<ReactionRecord>> = emptyMap(),
    val messageSources: Map<String, String> = emptyMap(),
    val input: String = "",
    val draftAttachments: List<MessageAttachment> = emptyList(),
    val revealedMessageId: String? = null,
    val renaming: SessionRecord? = null,
    val pendingDelete: SessionRecord? = null,
    val editing: MessageRecord? = null,
    val quoteTarget: MessageRecord? = null,
    val pendingDeleteMessages: Set<String>? = null,
    val selectedMessages: Set<String> = emptySet(),
    val multiSelect: Boolean = false,
    val history: List<EditRecord>? = null,
    val pendingMergeMessages: Set<String>? = null,
    val forwardPickerVisible: Boolean = false,
    val busy: Boolean = false,
    val settingsOpen: Boolean = false,
    val scrollSignal: Int = 0,
    val scrollTargetId: String? = null,
    /** 需要短暂闪烁背景的消息 id，到时自动清空。 */
    val highlightMessageId: String? = null,
    val notice: String? = null,
    val reminders: List<ReminderRecord> = emptyList(),
    /** 当前对话中仍有待触发提醒的消息 id，用于在气泡上显示闹钟标识。 */
    val reminderMessageIds: Set<String> = emptySet(),
    /** 全部待触发提醒的预览，驱动主界面闹钟入口与列表。 */
    val scheduledReminders: List<ReminderPreview> = emptyList(),
    val reminderListVisible: Boolean = false,
    val reminderTarget: ReminderTarget? = null,
    val reminderPermission: ReminderPermission? = null,
)

internal fun ChatUiState.selectedForwardMessages(): List<MessageRecord> =
    messages.filter {
        it.id in selectedMessages && it.sessionId == activeSessionId && !it.isDeleted
    }.sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })

private fun ChatUiState.switchToSession(id: String?, title: String = DEFAULT_CHAT_TITLE): ChatUiState {
    if (activeSessionId == id) return copy(activeTitle = title)
    return copy(
        activeSessionId = id,
        activeTitle = title,
        input = "",
        draftAttachments = emptyList(),
        editing = null,
        quoteTarget = null,
        revealedMessageId = null,
        selectedMessages = emptySet(),
        multiSelect = false,
        pendingMergeMessages = null,
        forwardPickerVisible = false,
        reminderTarget = null,
        messages = emptyList(),
        reminderSuggestions = emptyMap(),
        quotes = emptyMap(),
        reactions = emptyMap(),
        messageSources = emptyMap(),
        scrollSignal = scrollSignal + 1,
        scrollTargetId = null,
        highlightMessageId = null,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repository: ChatRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ChatUiState(
            directoryReady = repository.directoryReady.value,
            restoringDirectory = repository.hasSavedDirectory(),
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Session, minimum message count and optional target message to scroll to. */
    private class PendingScroll(
        val sessionId: String,
        val minCount: Int?,
        var targetMessageId: String? = null,
        /** 滚动到位后是否闪烁目标消息背景。 */
        var highlight: Boolean = false,
    )

    private var pendingScroll: PendingScroll? = null

    /** 通知点击带来的会话 id，等会话列表加载后再选中。 */
    private var pendingNotificationSession: String? = null

    /** 通知点击带来的消息 id，随会话一起等待加载后再定位。 */
    private var pendingNotificationMessage: String? = null

    init {
        viewModelScope.launch {
            repository.directoryReady.collect { ready ->
                _uiState.update { it.copy(directoryReady = ready) }
            }
        }
        viewModelScope.launch {
            repository.unreadOrganizationSessions.collect { unread ->
                _uiState.update { it.copy(unreadOrganizationSessions = unread) }
            }
        }
        viewModelScope.launch {
            repository.organizingSessions.collect { running ->
                _uiState.update { it.copy(organizingSessions = running) }
            }
        }
        viewModelScope.launch {
            _uiState.map { it.resultSessionId to it.unreadOrganizationSessions }
                .distinctUntilChanged()
                .collect { (resultId, unread) ->
                    if (resultId != null && resultId in unread) {
                        runCatching { repository.markOrganizationViewed(resultId) }
                    }
                }
        }
        viewModelScope.launch {
            repository.organizationErrors.collect { errors ->
                _uiState.update { it.copy(organizationErrors = errors) }
            }
        }
        viewModelScope.launch {
            repository.sessions.collect { sessions ->
                val requested = pendingNotificationSession?.takeIf { pending ->
                    sessions.any { it.id == pending }
                }
                val requestedMessage = pendingNotificationMessage
                if (requested != null) {
                    pendingNotificationSession = null
                    pendingNotificationMessage = null
                }
                // 会话已在当前页时消息流不会重新发射，需要直接定位；否则登记待滚动目标等消息加载。
                val alreadyActive = requested != null && _uiState.value.activeSessionId == requested
                if (requested != null) {
                    pendingScroll = if (requestedMessage != null && !alreadyActive) {
                        PendingScroll(requested, null, requestedMessage, highlight = true)
                    } else {
                        null
                    }
                }
                _uiState.update { current ->
                    val activeId = requested
                        ?: current.activeSessionId?.takeIf { id -> sessions.any { it.id == id } }
                        ?: sessions.maxByOrNull { it.lastOpenedAt }?.id
                    if (requested == null && activeId != current.activeSessionId) pendingScroll = null
                    current.switchToSession(
                        activeId,
                        sessions.firstOrNull { it.id == activeId }?.title ?: DEFAULT_CHAT_TITLE,
                    ).copy(sessions = sortSessions(sessions, current.sessionSortOrder))
                }
                if (requested != null) {
                    if (alreadyActive && requestedMessage != null) focusMessage(requestedMessage)
                    viewModelScope.launch { runCatching { repository.markSessionOpened(requested) } }
                }
            }
        }
        viewModelScope.launch {
            _uiState.map { it.activeSessionId }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.messages(id) }
                .collect { messages ->
                    val pending = pendingScroll
                    val current = _uiState.value
                    val ready = pending != null && pending.sessionId == current.activeSessionId &&
                        (pending.minCount == null || messages.size >= pending.minCount)
                    val focused = pending?.takeIf { ready && it.highlight }?.targetMessageId
                    if (ready) pendingScroll = null
                    _uiState.update { state ->
                        if (ready) {
                            state.copy(
                                messages = messages,
                                scrollSignal = state.scrollSignal + 1,
                                scrollTargetId = pending?.targetMessageId,
                                highlightMessageId = focused ?: state.highlightMessageId,
                            )
                        } else {
                            state.copy(messages = messages)
                        }
                    }
                    focused?.let { scheduleHighlightClear(it) }
                }
        }
        viewModelScope.launch {
            _uiState.map { it.resultSessionId }.distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) flowOf(null) else repository.sessionDocument(id)
                }
                .collect { document -> _uiState.update { it.copy(resultDocument = document) } }
        }
        viewModelScope.launch {
            _uiState.map { it.resultSessionId }.distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else repository.messages(id)
                }
                .collect { messages -> _uiState.update { it.copy(resultMessages = messages) } }
        }
        viewModelScope.launch {
            _uiState.map { it.activeSessionId }.distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyMap<String, QuoteRecord>()) else repository.quotes(id)
                }
                .collect { quotes -> _uiState.update { it.copy(quotes = quotes) } }
        }
        viewModelScope.launch {
            _uiState.map { it.activeSessionId }.distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(emptyMap<String, List<ReactionRecord>>())
                    } else {
                        repository.reactions(id)
                    }
                }
                .collect { reactions -> _uiState.update { it.copy(reactions = reactions) } }
        }
        viewModelScope.launch {
            _uiState.map { it.activeSessionId }.distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyMap<String, String>()) else repository.messageSources(id)
                }
                .collect { sources -> _uiState.update { it.copy(messageSources = sources) } }
        }
        viewModelScope.launch {
            _uiState.map { it.activeSessionId }.distinctUntilChanged()
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList<ReminderRecord>()) else repository.reminders(id)
                }
                .collect { reminders ->
                    _uiState.update {
                        it.copy(
                            reminders = reminders,
                            reminderMessageIds = reminders
                                .asSequence()
                                .filter { reminder -> reminder.status == REMINDER_SCHEDULED }
                                .mapNotNull { reminder -> reminder.messageId }
                                .toSet(),
                        )
                    }
                }
        }
        viewModelScope.launch {
            repository.scheduledReminders().collectLatest { previews ->
                val now = System.currentTimeMillis()
                val upcoming = previews.filter { it.triggerAt > now }
                _uiState.update {
                    it.copy(
                        scheduledReminders = upcoming,
                        // 最后一条提醒被取消或触发后收起列表并隐藏闹钟入口。
                        reminderListVisible = it.reminderListVisible && upcoming.isNotEmpty(),
                    )
                }
                // 到点后闹钟可能因权限或系统限制没有派发，应用保持打开时也要按宽限期把提醒置为过期，
                // 否则消息气泡会一直保留提醒底色。
                val nextExpiry = previews
                    .filter { it.triggerAt > now - REMINDER_EXPIRY_GRACE_MS }
                    .minOfOrNull { it.triggerAt + REMINDER_EXPIRY_GRACE_MS }
                if (nextExpiry != null) {
                    delay((nextExpiry - now).coerceAtLeast(0L))
                    repository.rescheduleReminders()
                }
            }
        }
        viewModelScope.launch {
            try {
                if (repository.restoreSavedDirectory() == false) {
                    _uiState.update { it.copy(notice = "记录目录不可用，请重新选择") }
                }
            } finally {
                _uiState.update { it.copy(restoringDirectory = false) }
            }
        }
    }

    fun selectDirectory(treeUri: String) = runAction {
        repository.selectDirectory(treeUri)
            .onSuccess { _uiState.update { it.copy(notice = "记录目录已就绪") } }
            .onFailure { error ->
                _uiState.update {
                    it.copy(notice = "无法使用所选目录：${error.message ?: error::class.simpleName}")
                }
            }
    }

    fun selectSession(id: String) = selectSession(id, focusMessageId = null)

    /** [focusMessageId] 非空时，消息加载完成后滚动到该消息并闪烁。 */
    private fun selectSession(id: String, focusMessageId: String?) {
        pendingScroll = focusMessageId?.let { PendingScroll(id, null, it, highlight = true) }
        _uiState.update { state ->
            state.switchToSession(id, state.sessions.firstOrNull { it.id == id }?.title ?: DEFAULT_CHAT_TITLE)
        }
        viewModelScope.launch {
            runCatching { repository.markSessionOpened(id) }
                .onFailure { error -> failWith(error) }
        }
    }

    /** 通知点击进入指定对话；带上消息 id 时滚动到该消息并闪烁背景。 */
    fun openSessionFromNotification(id: String, messageId: String? = null) {
        if (_uiState.value.sessions.none { it.id == id }) {
            pendingNotificationSession = id
            pendingNotificationMessage = messageId
            return
        }
        if (_uiState.value.activeSessionId == id) {
            if (messageId != null) focusMessage(messageId)
            return
        }
        selectSession(id, focusMessageId = messageId)
    }

    /** 引用预览跳转：原文仍在当前对话时滚动并高亮，已删除时给出行内提示。 */
    fun goToMessage(messageId: String) {
        if (_uiState.value.messages.none { it.id == messageId }) {
            showNotice("原消息已删除")
            return
        }
        focusMessage(messageId)
    }

    /** 已加载的对话内直接滚动到指定消息并闪烁背景。 */
    private fun focusMessage(messageId: String) {
        _uiState.update {
            it.copy(
                scrollSignal = it.scrollSignal + 1,
                scrollTargetId = messageId,
                highlightMessageId = messageId,
            )
        }
        scheduleHighlightClear(messageId)
    }

    /** 到时清除高亮，避免滚动离开再回来时重复闪烁。 */
    private fun scheduleHighlightClear(messageId: String) {
        viewModelScope.launch {
            delay(HIGHLIGHT_DURATION_MS)
            _uiState.update {
                if (it.highlightMessageId == messageId) it.copy(highlightMessageId = null) else it
            }
        }
    }

    fun requestMessageReminder(message: MessageRecord) {
        val title = _uiState.value.sessions.firstOrNull { it.id == message.sessionId }?.title
            ?: _uiState.value.activeTitle
        _uiState.update {
            it.copy(
                reminderTarget = ReminderTarget(
                    sessionId = message.sessionId,
                    sessionTitle = title,
                    messageId = message.id,
                    messagePreview = message.currentContent.trim().take(60),
                ),
            )
        }
    }

    fun dismissReminder() {
        _uiState.update { it.copy(reminderTarget = null) }
    }

    fun saveReminder(triggerAt: Long) {
        val target = _uiState.value.reminderTarget ?: return
        if (triggerAt <= System.currentTimeMillis()) {
            _uiState.update { it.copy(notice = "提醒时间必须晚于当前时间") }
            return
        }
        runAction {
            repository.saveReminder(target.sessionId, target.messageId, triggerAt)
                .onSuccess { outcome ->
                    _uiState.update {
                        it.copy(
                            reminderTarget = null,
                            notice = reminderNotice(outcome),
                            reminderPermission = permissionFor(outcome),
                        )
                    }
                    target.messageId?.let { removeReminderSuggestion(it) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(notice = error.message ?: "提醒保存失败，请重试") }
                }
        }
    }

    fun cancelReminder(id: String) {
        runAction {
            repository.cancelReminder(id)
                .onSuccess { _uiState.update { it.copy(notice = "提醒已取消") } }
                .onFailure { error ->
                    _uiState.update { it.copy(notice = error.message ?: "取消提醒失败，请重试") }
                }
        }
    }

    /** 打开全部提醒预览；打开时按当前时间过滤，避免已过点但尚未清理的提醒出现。 */
    fun openReminderList() {
        val now = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                scheduledReminders = it.scheduledReminders.filter { preview -> preview.triggerAt > now },
                reminderListVisible = true,
            )
        }
    }

    fun closeReminderList() {
        _uiState.update { it.copy(reminderListVisible = false) }
    }

    /** 点击提醒预览跳转到对应对话与气泡；消息已删除时只打开会话。 */
    fun openReminder(reminderId: String) {
        val preview = _uiState.value.scheduledReminders.firstOrNull { it.id == reminderId } ?: return
        _uiState.update { it.copy(reminderListVisible = false) }
        openSessionFromNotification(preview.sessionId, preview.messageId)
    }

    fun consumeReminderPermission() {
        _uiState.update { it.copy(reminderPermission = null) }
    }

    fun notifyPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                notice = if (granted) "已允许通知" else "未授予通知权限，提醒仍会保存但到点不会显示通知",
            )
        }
    }

    /** 打开提醒创建面板并预填本地识别的时间，由用户确认或修改后再创建。 */
    fun openSuggestedReminder(messageId: String) {
        val suggestion = _uiState.value.reminderSuggestions[messageId] ?: return
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && !it.isDeleted }
        if (message == null || message.contentVersion != suggestion.contentVersion ||
            suggestion.triggerAt <= System.currentTimeMillis()
        ) {
            removeReminderSuggestion(messageId)
            return
        }
        val title = _uiState.value.sessions.firstOrNull { it.id == message.sessionId }?.title
            ?: _uiState.value.activeTitle
        _uiState.update {
            it.copy(
                reminderTarget = ReminderTarget(
                    sessionId = message.sessionId,
                    sessionTitle = title,
                    messageId = message.id,
                    messagePreview = message.currentContent.trim().take(60),
                    suggestedAt = suggestion.triggerAt,
                ),
            )
        }
    }

    fun dismissReminderSuggestion(messageId: String) {
        removeReminderSuggestion(messageId)
    }

    private fun requestReminderSuggestion(messageId: String) {
        viewModelScope.launch {
            val suggestion = runCatching { repository.detectReminder(messageId) }.getOrNull()
                ?: return@launch
            _uiState.update { state ->
                state.copy(
                    reminderSuggestions = state.reminderSuggestions + (messageId to suggestion),
                )
            }
        }
    }

    private fun removeReminderSuggestion(messageId: String) {
        _uiState.update {
            it.copy(reminderSuggestions = it.reminderSuggestions - messageId)
        }
    }

    private fun reminderNotice(outcome: ReminderSaveOutcome): String = when {
        !outcome.notificationsAllowed -> "提醒已保存，但通知权限未开启，到点可能看不到通知"
        !outcome.exact -> "提醒已保存，但系统可能延迟通知；可开启“精确闹钟”权限提高准时率"
        else -> "提醒已设置"
    }

    private fun permissionFor(outcome: ReminderSaveOutcome): ReminderPermission? = when {
        !outcome.notificationsAllowed -> ReminderPermission.Notifications
        !outcome.exact -> ReminderPermission.ExactAlarm
        else -> null
    }

    fun rescheduleReminders() {
        viewModelScope.launch { runCatching { repository.rescheduleReminders() } }
    }

    fun organizeSession(id: String) {
        if (id in _uiState.value.organizingSessions) return
        viewModelScope.launch {
            repository.organizeSession(id)
                .onFailure { error -> _uiState.update { it.copy(notice = error.message ?: "无法开始整理") } }
        }
    }

    fun openOrganizationResults(id: String) {
        // 导出入口进入后立即重新整理一次，保证查看与导出的是最新内容。
        organizeSession(id)
        _uiState.update { it.copy(resultSessionId = id, resultError = null) }
        viewModelScope.launch {
            runCatching { repository.markOrganizationViewed(id) }
                .onFailure { error -> _uiState.update { it.copy(resultError = error.message ?: "无法标记已读") } }
        }
    }

    fun closeOrganizationResults() {
        _uiState.update { it.copy(resultSessionId = null, resultDocument = null, resultError = null) }
    }

    fun regenerateOrganization() {
        val id = _uiState.value.resultSessionId ?: return
        _uiState.update { it.copy(resultError = null) }
        organizeSession(id)
    }

    fun toggleSessionSort() {
        _uiState.update { state ->
            val next = when (state.sessionSortOrder) {
                SessionSortOrder.Created -> SessionSortOrder.Modified
                SessionSortOrder.Modified -> SessionSortOrder.Created
            }
            state.copy(
                sessionSortOrder = next,
                sessions = sortSessions(state.sessions, next),
            )
        }
    }

    fun createSession() = runAction {
        pendingScroll = null
        val id = repository.createSession()
        _uiState.update { state ->
            state.switchToSession(id, state.sessions.firstOrNull { it.id == id }?.title ?: "新对话")
        }
    }

    fun requestRename(session: SessionRecord) {
        _uiState.update { it.copy(renaming = session) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(renaming = null) }
    }

    fun confirmRename(title: String) {
        val target = _uiState.value.renaming ?: return
        val normalized = title.trim()
        if (normalized.isEmpty()) return
        runAction {
            repository.renameSession(target.id, normalized)
            _uiState.update { it.copy(renaming = null) }
        }
    }

    fun requestDelete(session: SessionRecord) {
        _uiState.update { it.copy(pendingDelete = session) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.pendingDelete ?: return
        runAction {
            try {
                repository.deleteSession(target.id)
            } catch (error: SessionAttachmentCleanupException) {
                _uiState.update { it.copy(pendingDelete = null) }
                throw error
            }
            _uiState.update { it.copy(pendingDelete = null) }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun send() {
        val current = _uiState.value
        if ((current.input.isBlank() && current.draftAttachments.isEmpty()) || current.busy) return
        if (!current.directoryReady) {
            _uiState.update { it.copy(notice = "请先选择记录目录") }
            return
        }
        val text = current.input
        val editing = current.editing
        val quoteTarget = current.quoteTarget
        val attachments = current.draftAttachments
        runAction {
            if (_uiState.value.activeSessionId != current.activeSessionId) return@runAction
            if (editing != null) {
                repository.editMessage(editing.id, text)
                removeReminderSuggestion(editing.id)
                _uiState.update { state ->
                    if (state.activeSessionId == current.activeSessionId && state.editing?.id == editing.id && state.input == text) {
                        state.copy(input = "", editing = null, quoteTarget = null, revealedMessageId = null)
                    } else {
                        state
                    }
                }
            } else {
                val sessionId = current.activeSessionId ?: repository.createSession()
                if (current.activeSessionId == null) {
                    _uiState.update { state ->
                        if (state.activeSessionId == null) {
                            state.switchToSession(sessionId, state.sessions.firstOrNull { it.id == sessionId }?.title ?: "新对话")
                                .copy(input = text, draftAttachments = attachments)
                        } else {
                            state
                        }
                    }
                }
                if (_uiState.value.activeSessionId != sessionId) return@runAction
                if (current.activeSessionId != null) {
                    pendingScroll = PendingScroll(sessionId, _uiState.value.messages.size + 1)
                }
                val sentId = try {
                    repository.sendMessage(sessionId, text, attachments, quoteTarget?.id)
                } catch (error: Exception) {
                    pendingScroll = null
                    throw error
                }
                _uiState.update { state ->
                    if (state.activeSessionId == sessionId && state.input == text &&
                        state.draftAttachments == attachments
                    ) {
                        state.copy(
                            input = "",
                            draftAttachments = emptyList(),
                            quoteTarget = null,
                            revealedMessageId = null,
                        )
                    } else {
                        state
                    }
                }
                if (sentId != null) requestReminderSuggestion(sentId)
            }
        }
    }

    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = MAX_IMAGE_ATTACHMENTS - _uiState.value.draftAttachments.count { it.kind == "image" }
        if (room <= 0) {
            showNotice("每条消息最多添加 $MAX_IMAGE_ATTACHMENTS 张图片")
            return
        }
        runAction {
            val added = mutableListOf<MessageAttachment>()
            var failure: String? = null
            uris.take(room).forEach { uri ->
                repository.importImage(uri)
                    .onSuccess { added += it }
                    .onFailure { failure = it.message ?: "无法添加图片" }
            }
            if (added.isNotEmpty()) {
                _uiState.update { it.copy(draftAttachments = it.draftAttachments + added) }
            }
            failure?.let { message -> _uiState.update { it.copy(notice = message) } }
        }
    }

    fun addVoiceRecording(file: File, durationMs: Long) {
        runAction {
            val previous = _uiState.value.draftAttachments.firstOrNull { it.kind == "audio" }
            repository.importVoiceRecording(file, durationMs)
                .onSuccess { attachment ->
                    if (previous != null) {
                        repository.deleteAttachment(previous.relativePath)
                    }
                    _uiState.update { state ->
                        state.copy(
                            draftAttachments = state.draftAttachments.filterNot { it.kind == "audio" } + attachment,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(notice = error.message ?: "无法保存录音") }
                }
        }
    }

    fun removeDraftAttachment(attachment: MessageAttachment) {
        _uiState.update { state ->
            state.copy(draftAttachments = state.draftAttachments.filterNot { it.id == attachment.id })
        }
        viewModelScope.launch {
            runCatching { repository.deleteAttachment(attachment.relativePath) }
        }
    }

    fun attachmentUri(relativePath: String): Uri? = repository.attachmentUri(relativePath)

    fun requestEdit(message: MessageRecord) {
        if (message.role != "user") return
        _uiState.update { it.copy(editing = message, input = message.currentContent, quoteTarget = null) }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(editing = null, input = "", quoteTarget = null) }
    }

    fun requestQuote(message: MessageRecord) {
        _uiState.update { it.copy(quoteTarget = message) }
    }

    fun cancelQuote() {
        _uiState.update { it.copy(quoteTarget = null) }
    }

    fun applyBubbleFormat(messageId: String, formatId: String?) {
        runAction {
            repository.setBubbleFormat(messageId, formatId)
        }
    }

    fun requestDeleteMessages(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        _uiState.update { it.copy(pendingDeleteMessages = messageIds) }
    }

    fun cancelDeleteMessages() {
        _uiState.update { it.copy(pendingDeleteMessages = null) }
    }

    fun confirmDeleteMessages() {
        val ids = _uiState.value.pendingDeleteMessages ?: return
        runAction {
            repository.deleteMessages(ids)
            _uiState.update {
                it.copy(
                    reminderSuggestions = it.reminderSuggestions - ids,
                    pendingDeleteMessages = null,
                    selectedMessages = emptySet(),
                    multiSelect = false,
                    editing = null,
                )
            }
        }
    }

    fun startMultiSelect(message: MessageRecord) {
        _uiState.update {
            it.copy(
                multiSelect = true,
                selectedMessages = setOf(message.id),
                revealedMessageId = null,
            )
        }
    }

    fun toggleSelected(messageId: String) {
        _uiState.update {
            val selected = if (messageId in it.selectedMessages) {
                it.selectedMessages - messageId
            } else {
                it.selectedMessages + messageId
            }
            it.copy(selectedMessages = selected)
        }
    }

    fun cancelMultiSelect() {
        _uiState.update {
            it.copy(
                multiSelect = false,
                selectedMessages = emptySet(),
                pendingMergeMessages = null,
                forwardPickerVisible = false,
            )
        }
    }

    fun requestForwardMessages() {
        val current = _uiState.value
        if (!current.multiSelect || current.selectedMessages.isEmpty()) return
        _uiState.update { it.copy(forwardPickerVisible = true) }
    }

    fun cancelForwardMessages() {
        _uiState.update {
            it.copy(
                forwardPickerVisible = false,
                selectedMessages = if (it.multiSelect) it.selectedMessages else emptySet(),
            )
        }
    }

    fun forwardMessage(message: MessageRecord) {
        _uiState.update {
            it.copy(
                selectedMessages = setOf(message.id),
                forwardPickerVisible = true,
                revealedMessageId = null,
            )
        }
    }

    fun shareForwardMessages(launchShare: suspend (List<MessageRecord>) -> Unit) {
        val current = _uiState.value
        if (current.activeSessionId == null || !current.forwardPickerVisible || current.selectedMessages.isEmpty()) return
        runAction {
            try {
                val selected = current.selectedForwardMessages()
                require(selected.size == current.selectedMessages.size) { "所选消息已变化，请重新选择" }
                launchShare(selected)
            } finally {
                cancelForwardMessages()
            }
        }
    }

    fun confirmForwardMessages(targetSessionId: String) {
        val current = _uiState.value
        val sourceSessionId = current.activeSessionId ?: return
        if (targetSessionId == sourceSessionId) return
        val ids = current.selectedMessages
        val targetTitle = current.sessions.firstOrNull { it.id == targetSessionId }?.title ?: "所选对话"
        runAction {
            val count = repository.forwardMessages(sourceSessionId, ids, targetSessionId)
            _uiState.update {
                it.copy(
                    forwardPickerVisible = false,
                    multiSelect = false,
                    selectedMessages = emptySet(),
                    pendingMergeMessages = null,
                    notice = "已转发 $count 条消息到《$targetTitle》",
                )
            }
        }
    }

    fun onMessageClick(messageId: String) {
        if (_uiState.value.multiSelect) toggleSelected(messageId) else toggleMessageTime(messageId)
    }

    fun showHistory(message: MessageRecord) {
        viewModelScope.launch {
            runCatching { repository.editHistory(message.id) }
                .onSuccess { list -> _uiState.update { it.copy(history = list) } }
                .onFailure { error -> failWith(error) }
        }
    }

    fun dismissHistory() {
        _uiState.update { it.copy(history = null) }
    }

    fun requestMergeMessages() {
        val current = _uiState.value
        if (!current.multiSelect || current.selectedMessages.size < 2) return
        _uiState.update { it.copy(pendingMergeMessages = current.selectedMessages) }
    }

    fun cancelMergeMessages() {
        _uiState.update { it.copy(pendingMergeMessages = null) }
    }

    fun confirmMergeMessages() {
        val current = _uiState.value
        val ids = current.pendingMergeMessages ?: return
        val sessionId = current.activeSessionId ?: return
        runAction {
            val pending = PendingScroll(sessionId, null)
            pendingScroll = pending
            val mergedId = repository.mergeMessages(sessionId, ids)
            pending.targetMessageId = mergedId
            _uiState.update { state ->
                if (state.activeSessionId == sessionId) {
                    state.copy(pendingMergeMessages = null, multiSelect = false,
                        selectedMessages = emptySet(), revealedMessageId = null,
                        reminderSuggestions = state.reminderSuggestions - ids)
                } else state
            }
        }
    }

    fun toggleMessageTime(messageId: String) {
        _uiState.update {
            it.copy(revealedMessageId = if (it.revealedMessageId == messageId) null else messageId)
        }
    }

    fun openSettings() {
        _uiState.update { it.copy(settingsOpen = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(settingsOpen = false) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun showNotice(text: String) {
        _uiState.update { it.copy(notice = text) }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                failWith(error)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun failWith(error: Throwable) {
        _uiState.update { it.copy(notice = error.message ?: "操作失败，请重试") }
    }

    private fun sortSessions(sessions: List<SessionRecord>, order: SessionSortOrder): List<SessionRecord> =
        when (order) {
            SessionSortOrder.Created -> sessions.sortedWith(
                compareByDescending<SessionRecord> { it.createdAt }.thenByDescending { it.id },
            )
            SessionSortOrder.Modified -> sessions.sortedWith(
                compareByDescending<SessionRecord> { it.updatedAt }.thenByDescending { it.id },
            )
        }

    companion object {
        fun factory(repository: ChatRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(repository) as T
        }
    }
}
