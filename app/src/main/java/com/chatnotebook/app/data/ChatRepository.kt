package com.chatnotebook.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.room.Room
import com.chatnotebook.app.data.sync.LanSyncManager
import com.chatnotebook.app.reminder.LocalReminderParser
import com.chatnotebook.app.reminder.LocalReminderSuggestion
import com.chatnotebook.app.reminder.ReminderNotifications
import com.chatnotebook.app.reminder.ReminderScheduler
import com.chatnotebook.app.security.LegacyLlmCleanup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Domain-facing access used by the chat screen; all writes must reach the long-term file first. */
interface ChatRepository {
    val directoryReady: StateFlow<Boolean>
    val sessions: Flow<List<SessionRecord>>
    fun messages(sessionId: String): Flow<List<MessageRecord>>
    fun sessionDocument(sessionId: String): Flow<SessionDocumentRecord?>
    fun quotes(sessionId: String): Flow<Map<String, QuoteRecord>>
    fun reactions(sessionId: String): Flow<Map<String, List<ReactionRecord>>>
    fun messageSources(sessionId: String): Flow<Map<String, String>>
    val unreadOrganizationSessions: StateFlow<Set<String>>
    val organizingSessions: StateFlow<Set<String>>
    val organizationErrors: StateFlow<Map<String, String>>

    suspend fun selectDirectory(treeUri: String): Result<Unit>
    /** 是否保存过记录目录；用于启动时直接进入恢复中状态，避免闪现目录提示。 */
    fun hasSavedDirectory(): Boolean
    /** null: no directory was saved yet; false: a saved directory is no longer usable. */
    suspend fun restoreSavedDirectory(): Boolean?
    suspend fun createSession(title: String = "新对话"): String
    suspend fun renameSession(id: String, title: String)
    suspend fun deleteSession(id: String)
    suspend fun markSessionOpened(id: String)
    fun reminders(sessionId: String): Flow<List<ReminderRecord>>
    suspend fun saveReminder(
        sessionId: String,
        messageId: String?,
        triggerAt: Long,
    ): Result<ReminderSaveOutcome>
    suspend fun cancelReminder(id: String): Result<Unit>
    /** 全部待触发提醒的预览；会话已删除的不包含，绑定消息已删除时消息 id 为空。 */
    fun scheduledReminders(): Flow<List<ReminderPreview>>
    /** 幂等重排全部待触发提醒的闹钟；已过点未触发的提醒先标记为已取消。 */
    suspend fun rescheduleReminders()
    /** 到点且仍待触发时返回通知内容，否则 null。 */
    suspend fun reminderDelivery(id: String): ReminderDelivery?
    /** 把提醒记为已触发；文件写入失败时返回 false 并保持 scheduled。 */
    suspend fun markReminderFired(id: String): Boolean
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList(),
        quotedMessageId: String? = null,
    ): String?
    suspend fun importImage(uri: Uri): Result<MessageAttachment>
    suspend fun importVoiceRecording(file: File, durationMs: Long): Result<MessageAttachment>
    suspend fun deleteAttachment(relativePath: String)
    fun attachmentUri(relativePath: String): Uri?
    suspend fun editMessage(messageId: String, text: String)
    suspend fun deleteMessages(messageIds: Set<String>)
    suspend fun mergeMessages(sessionId: String, messageIds: Set<String>): String
    suspend fun forwardMessages(sourceSessionId: String, messageIds: Set<String>, targetSessionId: String): Int
    suspend fun setBubbleFormat(messageId: String, formatId: String?)
    suspend fun editHistory(messageId: String): List<EditRecord>
    /** 用本地规则从用户消息中识别日程；不访问网络，失败返回 null。 */
    suspend fun detectReminder(messageId: String): LocalReminderSuggestion?
    suspend fun organizeSession(sessionId: String): Result<Boolean>
    suspend fun markOrganizationViewed(sessionId: String)
}

class PersistentChatRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ChatRepository {
    private val appContext = context.applicationContext
    internal val lanSync by lazy { LanSyncManager(appContext, lock) }
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(appContext, RecordDatabase::class.java, DATABASE_NAME)
        .addMigrations(
            RecordDatabase.MIGRATION_1_2,
            RecordDatabase.MIGRATION_2_3,
            RecordDatabase.MIGRATION_3_4,
            RecordDatabase.MIGRATION_4_5,
            RecordDatabase.MIGRATION_5_6,
            RecordDatabase.MIGRATION_6_7,
            RecordDatabase.MIGRATION_7_8,
            RecordDatabase.MIGRATION_8_9,
        )
        .build()
    private val lock = Mutex()
    private val reminderScheduler by lazy { ReminderScheduler(appContext) }
    private val organizationLock = Mutex()
    private val organizationScope = CoroutineScope(SupervisorJob() + io)

    private val _directoryReady = MutableStateFlow(false)
    override val directoryReady: StateFlow<Boolean> = _directoryReady.asStateFlow()

    private val _unreadOrganizationSessions = MutableStateFlow<Set<String>>(emptySet())
    override val unreadOrganizationSessions: StateFlow<Set<String>> = _unreadOrganizationSessions.asStateFlow()
    private val _organizingSessions = MutableStateFlow<Set<String>>(emptySet())
    override val organizingSessions: StateFlow<Set<String>> = _organizingSessions.asStateFlow()
    private val _organizationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    override val organizationErrors: StateFlow<Map<String, String>> = _organizationErrors.asStateFlow()

    private var records: RecordRepository? = null
    private var chat: ChatSessionService? = null
    private var attachmentStore: AttachmentStore? = null

    override val sessions: Flow<List<SessionRecord>> =
        database.records().observeSessions().map { rows -> rows.map { it.toRecord() } }

    override fun messages(sessionId: String): Flow<List<MessageRecord>> =
        database.records().observeMessages(sessionId).map { rows -> rows.map { it.toRecord() } }

    override fun scheduledReminders(): Flow<List<ReminderPreview>> =
        database.records().observeScheduledReminderPreviews().map { rows ->
            rows.map { row ->
                ReminderPreview(
                    id = row.id,
                    sessionId = row.sessionId,
                    messageId = row.messageId,
                    triggerAt = row.triggerAt,
                    text = reminderPreviewText(row.content, row.sessionTitle),
                    sessionTitle = row.sessionTitle,
                )
            }
        }

    override fun hasSavedDirectory(): Boolean = prefs.getString(KEY_TREE_URI, null) != null

    override fun sessionDocument(sessionId: String): Flow<SessionDocumentRecord?> =
        database.records().observeSessionDocument(sessionId).map { row -> row?.toRecord() }

    override fun quotes(sessionId: String): Flow<Map<String, QuoteRecord>> =
        database.records().observeQuotes(sessionId).map { rows -> rows.associate { it.messageId to it.toRecord() } }

    override fun reactions(sessionId: String): Flow<Map<String, List<ReactionRecord>>> =
        database.records().observeReactions(sessionId).map { rows ->
            rows.map { it.toRecord() }.groupBy { it.messageId }
        }

    override fun messageSources(sessionId: String): Flow<Map<String, String>> =
        database.records().observeMergeRoots(sessionId).map { rows ->
            rows.mapNotNull { row ->
                val messageId = row.targetMessageId
                val title = row.sourceTitle
                if (messageId != null && title != null) messageId to title else null
            }.toMap()
        }

    override suspend fun selectDirectory(treeUri: String): Result<Unit> = withContext(io) {
        lock.withLock {
            try {
                val uri = Uri.parse(treeUri)
                ensurePersistedPermission(uri)
                open(uri)
                prefs.edit().putString(KEY_TREE_URI, treeUri).apply()
                Result.success(Unit)
            } catch (error: Exception) {
                Log.w("ChatNotebook", "打开记录目录失败：$treeUri", error)
                _directoryReady.value = false
                attachmentStore = null
                lanSync.directory(null, null)
                Result.failure(error)
            }
        }
    }

    override suspend fun restoreSavedDirectory(): Boolean? = withContext(io) {
        lock.withLock {
            val saved = prefs.getString(KEY_TREE_URI, null) ?: return@withLock null
            val uri = Uri.parse(saved)
            if (!hasPersistedPermission(uri)) {
                _directoryReady.value = false
                attachmentStore = null
                lanSync.directory(null, null)
                return@withLock false
            }
            try {
                open(uri)
                true
            } catch (error: Exception) {
                Log.w("ChatNotebook", "恢复记录目录失败：$saved", error)
                _directoryReady.value = false
                attachmentStore = null
                lanSync.directory(null, null)
                false
            }
        }
    }

    override suspend fun createSession(title: String): String = withContext(io) {
        chat().create(title)
    }

    override suspend fun renameSession(id: String, title: String) = withContext(io) {
        chat().rename(id, title)
    }

    override suspend fun deleteSession(id: String) = withContext(io) {
        lock.withLock {
            organizationLock.withLock {
                val service = chat()
                val reminderIds = try {
                    records?.current()?.reminders
                        ?.filter { it.sessionId == id }
                        ?.map { it.id }
                        .orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
                try {
                    service.delete(id) { name ->
                        val store = attachmentStore ?: throw IOException("记录目录不可用")
                        store.delete(name)
                    }
                } finally {
                    reminderIds.forEach(reminderScheduler::cancel)
                    records?.current()?.let(::publishUnread)
                }
            }
        }
    }

    override suspend fun markSessionOpened(id: String) = withContext(io) {
        chat().markOpened(id)
    }

    override fun reminders(sessionId: String): Flow<List<ReminderRecord>> =
        database.records().observeReminders(sessionId).map { rows -> rows.map { it.toRecord() } }

    override suspend fun saveReminder(
        sessionId: String,
        messageId: String?,
        triggerAt: Long,
    ): Result<ReminderSaveOutcome> = withContext(io) {
        lock.withLock {
            val repository = records ?: return@withLock Result.failure(
                IllegalStateException("请先选择记录目录"),
            )
            val now = System.currentTimeMillis()
            if (triggerAt <= now) {
                return@withLock Result.failure(IllegalArgumentException("提醒时间必须晚于当前时间"))
            }
            try {
                var saved: ReminderRecord? = null
                repository.update { before ->
                    require(before.sessions.any { it.id == sessionId && !it.isDeleted }) { "对话不存在" }
                    require(
                        messageId == null || before.messages.any {
                            it.id == messageId && it.sessionId == sessionId && !it.isDeleted
                        },
                    ) { "消息不存在" }
                    val timestamp = System.currentTimeMillis()
                    val record = ReminderRecord(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        messageId = messageId,
                        triggerAt = triggerAt,
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    )
                    saved = record
                    before.copy(reminders = before.reminders + record)
                }
                val record = checkNotNull(saved)
                val exact = reminderScheduler.schedule(record)
                Result.success(
                    ReminderSaveOutcome(
                        reminder = record,
                        exact = exact,
                        notificationsAllowed = ReminderNotifications.allowed(appContext),
                    ),
                )
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    override suspend fun cancelReminder(id: String): Result<Unit> = withContext(io) {
        lock.withLock {
            val repository = records ?: return@withLock Result.failure(
                IllegalStateException("请先选择记录目录"),
            )
            try {
                repository.update { before ->
                    if (before.reminders.none { it.id == id && it.status == REMINDER_SCHEDULED }) {
                        return@update before
                    }
                    before.copy(
                        reminders = before.reminders.map {
                            if (it.id == id) {
                                it.copy(
                                    status = REMINDER_CANCELLED,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            } else {
                                it
                            }
                        },
                    )
                }
                reminderScheduler.cancel(id)
                Result.success(Unit)
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    override suspend fun rescheduleReminders() = withContext(io) {
        lock.withLock {
            val repository = records ?: return@withLock
            try {
                repository.update { before ->
                    val expiry = expirePastDueReminders(before.reminders, System.currentTimeMillis())
                    if (expiry.expiredIds.isEmpty()) before else before.copy(reminders = expiry.reminders)
                }
                reminderScheduler.rescheduleAll(repository.current().reminders)
            } catch (_: Exception) {
                // 文件暂不可读时保持现状，等待下次启动重排。
            }
        }
    }

    override suspend fun reminderDelivery(id: String): ReminderDelivery? = withContext(io) {
        val repository = records ?: return@withContext null
        reminderDeliveryOf(repository.current(), id, System.currentTimeMillis())
    }

    override suspend fun markReminderFired(id: String): Boolean = withContext(io) {
        val repository = records ?: return@withContext false
        var updated = false
        try {
            repository.update { before ->
                if (before.reminders.none { it.id == id && it.status == REMINDER_SCHEDULED }) {
                    return@update before
                }
                updated = true
                before.copy(
                    reminders = before.reminders.map {
                        if (it.id == id) {
                            it.copy(status = REMINDER_FIRED, updatedAt = System.currentTimeMillis())
                        } else {
                            it
                        }
                    },
                )
            }
        } catch (_: Exception) {
            return@withContext false
        }
        updated
    }

    override suspend fun sendMessage(
        sessionId: String,
        text: String,
        attachments: List<MessageAttachment>,
        quotedMessageId: String?,
    ): String? = withContext(io) {
        lock.withLock {
            chat().send(sessionId, text, attachments, quotedMessageId)
        }
    }

    override suspend fun importImage(uri: Uri): Result<MessageAttachment> = withContext(io) {
        val store = attachmentStore ?: return@withContext Result.failure(
            IllegalStateException("请先选择记录目录"),
        )
        try {
            val mime = appContext.contentResolver.getType(uri)
                ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?.takeIf { it.isNotBlank() } ?: "jpg"
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("无法读取所选图片")
            require(bytes.isNotEmpty()) { "所选图片为空" }
            val id = UUID.randomUUID().toString()
            val name = store.write("$id.$extension", bytes)
            Result.success(
                MessageAttachment(
                    id = id,
                    kind = "image",
                    relativePath = attachmentRelativePath(name),
                    mimeType = mime,
                    sizeBytes = bytes.size.toLong(),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun importVoiceRecording(file: File, durationMs: Long): Result<MessageAttachment> =
        withContext(io) {
            val store = attachmentStore ?: return@withContext Result.failure(
                IllegalStateException("请先选择记录目录"),
            )
            try {
                val bytes = file.readBytes()
                require(bytes.isNotEmpty()) { "录音为空" }
                val id = UUID.randomUUID().toString()
                val name = store.write("$id.m4a", bytes)
                Result.success(
                    MessageAttachment(
                        id = id,
                        kind = "audio",
                        relativePath = attachmentRelativePath(name),
                        mimeType = "audio/mp4",
                        sizeBytes = bytes.size.toLong(),
                        durationMs = durationMs,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                file.delete()
            }
        }

    override suspend fun deleteAttachment(relativePath: String) = withContext(io) {
        val store = attachmentStore ?: return@withContext
        runCatching { store.delete(attachmentFileName(relativePath)) }
        Unit
    }

    override fun attachmentUri(relativePath: String): Uri? =
        attachmentStore?.uri(attachmentFileName(relativePath))

    override suspend fun editMessage(messageId: String, text: String) = withContext(io) {
        chat().edit(messageId, text)
    }

    override suspend fun deleteMessages(messageIds: Set<String>) = withContext(io) {
        chat().deleteMessages(messageIds)
    }

    override suspend fun mergeMessages(sessionId: String, messageIds: Set<String>): String = withContext(io) {
        chat().mergeMessages(sessionId, messageIds)
    }

    override suspend fun forwardMessages(
        sourceSessionId: String,
        messageIds: Set<String>,
        targetSessionId: String,
    ): Int = withContext(io) {
        lock.withLock {
            chat().forwardMessages(sourceSessionId, messageIds, targetSessionId)
        }
    }

    override suspend fun setBubbleFormat(messageId: String, formatId: String?) = withContext(io) {
        chat().setBubbleFormat(messageId, formatId)
    }

    override suspend fun editHistory(messageId: String): List<EditRecord> = withContext(io) {
        database.records().editHistory(messageId).map { it.toRecord() }
    }

    override suspend fun detectReminder(messageId: String): LocalReminderSuggestion? = withContext(io) {
        val repository = records ?: return@withContext null
        val message = runCatching {
            repository.current().messages.firstOrNull { it.id == messageId && !it.isDeleted }
        }.getOrNull() ?: return@withContext null
        if (message.role != "user" || message.currentContent.isBlank()) return@withContext null
        if (message.attachments.any { it.kind == "audio" }) return@withContext null
        val detected = LocalReminderParser.detect(message.currentContent, message.createdAt)
            ?: return@withContext null
        LocalReminderSuggestion(
            messageId = message.id,
            contentVersion = message.contentVersion,
            triggerAt = detected.triggerAt,
            phrase = detected.phrase,
        )
    }

    override suspend fun organizeSession(sessionId: String): Result<Boolean> = withContext(io) {
        organizationLock.withLock {
            val recordStore = records ?: return@withLock Result.failure(IllegalStateException("请先选择记录目录"))
            val snapshot = recordStore.current()
            val session = snapshot.sessions.firstOrNull { it.id == sessionId && !it.isDeleted }
                ?: return@withLock Result.failure(IllegalArgumentException("对话不存在"))
            val messages = snapshot.messages
                .filter { it.sessionId == sessionId && !it.isDeleted }
                .sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })
            val formats = snapshot.reactions.mapNotNull { reaction ->
                BubbleFormat.fromId(reaction.emoji)?.let { reaction.messageId to it }
            }.toMap()
            if (messages.isEmpty()) {
                _organizationErrors.value = _organizationErrors.value + (sessionId to "暂无可整理的消息")
                return@withLock Result.failure(IllegalStateException("暂无可整理的消息"))
            }
            if (sessionId in _organizingSessions.value) return@withLock Result.success(false)
            _organizationErrors.value = _organizationErrors.value - sessionId
            _organizingSessions.value = _organizingSessions.value + sessionId
            organizationScope.launch {
                try {
                    val document = SessionDocumentService().generateLocal(session, messages, formats)
                    organizationLock.withLock {
                        if (recordStore.current().sessions.none { it.id == sessionId && !it.isDeleted }) {
                            return@launch
                        }
                        val updated = recordStore.update { payload ->
                            val previous = payload.organizationStates.firstOrNull { it.sessionId == sessionId }
                            payload.copy(
                                sessionDocuments = payload.sessionDocuments.filterNot {
                                    it.sessionId == sessionId
                                } + document,
                                organizationStates = payload.organizationStates.filterNot {
                                    it.sessionId == sessionId
                                } + SessionOrganizationState(
                                    sessionId,
                                    (previous?.completedVersion ?: 0) + 1,
                                    previous?.viewedVersion ?: 0,
                                ),
                            )
                        }
                        publishUnread(updated)
                    }
                } catch (error: Exception) {
                    _organizationErrors.value = _organizationErrors.value +
                        (sessionId to (error.message ?: "整理失败，请稍后重试"))
                } finally {
                    organizationLock.withLock {
                        _organizingSessions.value = _organizingSessions.value - sessionId
                    }
                }
            }
            Result.success(true)
        }
    }

    override suspend fun markOrganizationViewed(sessionId: String) = withContext(io) {
        val recordStore = records ?: return@withContext
        organizationLock.withLock {
            val updated = recordStore.update { payload ->
                payload.copy(organizationStates = payload.organizationStates.map {
                    if (it.sessionId == sessionId) it.copy(viewedVersion = it.completedVersion) else it
                })
            }
            publishUnread(updated)
        }
    }

    private fun publishUnread(payload: RecordPayload) {
        _unreadOrganizationSessions.value = payload.organizationStates.filter {
            it.completedVersion > it.viewedVersion
        }.map { it.sessionId }.toSet()
    }

    private suspend fun open(uri: Uri) {
        val previous = prefs.getString(KEY_TREE_URI, null)
        if (previous != null && previous != uri.toString()) lanSync.switchDirectory() else lanSync.detachDirectory()
        val store = SafSnapshotStore(appContext, uri)
        val repository = RecordRepository(store, database)
        repository.restore()
        val hasExistingRecords = repository.hasExistingRecordFiles
        repository.ensureWritable()
        LegacyLlmCleanup.run(appContext)
        if (repository.current().llmConfigs.isNotEmpty()) {
            repository.update { it.copy(llmConfigs = emptyList()) }
        }
        val service = ChatSessionService(repository)
        chat = service
        records = repository
        attachmentStore = SafAttachmentStore(appContext, uri).also { it.protectExistingMedia() }
        if (!hasExistingRecords) service.seedDefaultConversations()
        publishUnread(repository.current())
        _directoryReady.value = true
        lanSync.directory(repository, attachmentStore)
    }

    private fun chat(): ChatSessionService =
        chat ?: throw IllegalStateException("请先选择记录目录")

    private fun hasPersistedPermission(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    private fun ensurePersistedPermission(uri: Uri) {
        if (hasPersistedPermission(uri)) return
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    companion object {
        private const val PREFS = "record_environment"
        private const val KEY_TREE_URI = "tree_uri"
        private const val DATABASE_NAME = "record-index.db"

        @Volatile
        private var shared: PersistentChatRepository? = null

        fun shared(context: Context): PersistentChatRepository = shared ?: synchronized(this) {
            shared ?: PersistentChatRepository(context.applicationContext).also { shared = it }
        }
    }
}
