package com.chatnotebook.app.data

import androidx.room.withTransaction
import com.chatnotebook.app.data.sync.AttachmentContent
import com.chatnotebook.app.data.sync.DirectoryRevision
import com.chatnotebook.app.data.sync.SyncDirectoryJournal
import com.chatnotebook.app.data.sync.SyncDirectoryState
import com.chatnotebook.app.data.sync.compareVersions
import com.chatnotebook.app.data.sync.VersionOrder
import com.chatnotebook.app.data.sync.syncJournalName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.io.IOException

class RecordRepository(
    private val store: SnapshotStore,
    private val database: RecordDatabase,
    private val codec: SnapshotCodec = SnapshotCodec(),
    private val fileCodec: SessionFileCodec = SessionFileCodec(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var loaded: RecordPayload? = null
    private var sessionFiles = mutableMapOf<String, String>()
    private var settingsFile: String? = null
    private var generation = 0L
    private val _recordGeneration = MutableStateFlow(0L)
    internal val recordGeneration: StateFlow<Long> = _recordGeneration
    private val syncJournal = SyncDirectoryJournal(store)
    private var syncState: SyncDirectoryState? = null
    private val warning = MutableStateFlow(false)
    val recoveredFromOlderSnapshot: StateFlow<Boolean> = warning

    /** Rebuilds the disposable Room index from the newest valid file of every conversation. */
    suspend fun restore(): RecordPayload = withContext(io) {
        mutex.withLock {
            val names = store.list()
            val restoredSync = syncJournal.restore(names)
            val trackedSessions = restoredSync?.sessionFiles?.values?.toSet()
            val sessions = names.filter { trackedSessions == null || it in trackedSessions }.mapNotNull(::parseSessionFile)
            val settings = names.filter { restoredSync == null || it == restoredSync.settingsFile }
                .mapNotNull(::parseSettingsFile)
            val legacy = names.filter(::isLegacyFile)
            if (restoredSync == null && sessions.isEmpty() && settings.isEmpty() && legacy.isNotEmpty()) {
                return@withLock migrateLegacy(legacy)
            }

            var damaged = false
            val parts = mutableListOf<SessionFilePayload>()
            val files = mutableMapOf<String, String>()
            sessions.groupBy { it.sessionId }.forEach { (sessionId, candidates) ->
                for (candidate in candidates.sortedByDescending { it.generation }) {
                    val envelope = runCatching { fileCodec.decodeSession(store.read(candidate.name)) }.getOrNull()
                    if (envelope != null && envelope.sessionId == sessionId) {
                        parts += envelope.payload
                        files[sessionId] = candidate.name
                        break
                    }
                    damaged = true
                }
            }
            var settingsName: String? = null
            var configs = emptyList<LlmConfigRecord>()
            for (candidate in settings.sortedByDescending { it.generation }) {
                val envelope = runCatching { fileCodec.decodeSettings(store.read(candidate.name)) }.getOrNull()
                if (envelope != null) {
                    settingsName = candidate.name
                    configs = envelope.payload.llmConfigs
                    break
                }
                damaged = true
            }
            if (sessions.isNotEmpty() && parts.isEmpty()) {
                throw IOException("No valid session record; existing files were not modified")
            }
            if (restoredSync != null && (restoredSync.sessionFiles != files || restoredSync.settingsFile != settingsName)) {
                throw IOException("同步记录目录不完整")
            }

            val payload = fileCodec.combine(parts, configs)
            rebuildCache(payload)
            loaded = payload
            sessionFiles = files
            settingsFile = settingsName
            syncState = restoredSync
            generation = maxOf(
                names.mapNotNull(::parseSessionFile).maxOfOrNull { it.generation } ?: 0L,
                names.mapNotNull(::parseSettingsFile).maxOfOrNull { it.generation } ?: 0L,
                names.filter(syncJournalName::matches).maxOfOrNull { it.substring(5, 25).toLong() } ?: 0L,
            )
            warning.value = damaged
            _recordGeneration.value = generation
            payload
        }
    }

    /** Writes only the conversations and settings that actually changed. */
    suspend fun update(transform: (RecordPayload) -> RecordPayload): RecordPayload = withContext(io) {
        mutex.withLock {
            val before = requireLoaded()
            commitLocked(before, transform(before))
        }
    }

    internal suspend fun startSyncTracking(device: String): SyncDirectoryState = withContext(io) {
        mutex.withLock {
            require(device.isNotBlank())
            val records = requireLoaded()
            // Check the directory grant even when its snapshot is already in memory.
            val names = store.list()
            val current = syncState
            val directoryId = current?.directoryId ?: java.util.UUID.randomUUID().toString()
            val versionDevice = "$directoryId:$device"
            if (current != null && current.localDevice in setOf(device, versionDevice)) return@withLock current
            val nextGeneration = nextTrackedGeneration(names)
            val next = current?.copy(localDevice = versionDevice, generation = nextGeneration) ?: SyncDirectoryState(
                directoryId = directoryId,
                localDevice = versionDevice,
                generation = nextGeneration,
                sessionFiles = sessionFiles.toMap(),
                settingsFile = settingsFile,
                revisions = fileCodec.split(records).keys.associateWith {
                    DirectoryRevision(false, mapOf(versionDevice to 1L), versionDevice)
                },
            )
            syncJournal.commit(next)
            syncState = next
            generation = next.generation
            _recordGeneration.value = generation
            next
        }
    }

    private fun requireLoaded(): RecordPayload =
        loaded ?: throw IllegalStateException("Restore records before writing")

    internal suspend fun syncSnapshot(): Pair<SyncDirectoryState, RecordPayload> = withContext(io) {
        mutex.withLock {
            store.list()
            checkNotNull(syncState) to requireLoaded()
        }
    }

    internal suspend fun rememberSyncAttachments(
        expected: SyncDirectoryState,
        identities: Map<String, AttachmentContent>,
    ): SyncDirectoryState = withContext(io) {
        mutex.withLock {
            val current = checkNotNull(syncState)
            if (current != expected) throw IOException("本机记录已更新，请重新比较后同步")
            if (current.attachmentIndex == identities) return@withLock current
            val next = current.copy(generation = nextTrackedGeneration(store.list()), attachmentIndex = identities)
            syncJournal.commit(next)
            syncState = next
            generation = next.generation
            _recordGeneration.value = generation
            next
        }
    }

    internal suspend fun applySyncRevisions(
        directoryId: String,
        expected: Map<String, DirectoryRevision?>,
        revisions: Map<String, DirectoryRevision>,
        parts: Map<String, SessionFilePayload?>,
        attachmentIndex: Map<String, AttachmentContent> = emptyMap(),
        repairAttachments: Boolean = false,
        onCommitted: (Set<String>) -> Unit = {},
    ): Boolean = withContext(io) {
        mutex.withLock {
            val current = checkNotNull(syncState)
            check(current.directoryId == directoryId) { "记录目录已切换，请重新确认同步设备" }
            require(parts.keys == revisions.keys && revisions.keys.all { it in expected })
            if (!repairAttachments && revisions.all { (id, version) -> current.revisions[id] == version }) {
                // An earlier attempt can lose its acknowledgement after writing the files.
                rebuildCache(requireLoaded())
                return@withLock false
            }
            if (expected.any { (id, version) -> current.revisions[id] != version }) {
                throw IOException("本机记录已更新，请重新比较后同步")
            }
            for ((id, revision) in revisions) {
                val existing = current.revisions[id]
                val repairing = repairAttachments && existing == revision && parts[id] != null
                require(repairing || existing == null || compareVersions(existing.versions, revision.versions) == VersionOrder.Before) {
                    "收到的同步版本不是本机版本的后继"
                }
                require(revision.deleted == (parts[id] == null))
                require(parts[id]?.session?.let { it.id == id && !it.isDeleted } ?: true)
            }
            val before = requireLoaded()
            val existing = fileCodec.split(before)
            val reconciled = existing.filterKeys { it !in parts }.values + parts.mapNotNull { (id, part) ->
                part?.withDeviceState(existing[id])
            }
            val after = fileCodec.combine(reconciled, before.llmConfigs)
            val removedPaths = activeAttachmentPaths(before) - activeAttachmentPaths(after)
            commitLocked(before, after, revisions, attachmentIndex) { onCommitted(removedPaths) }
            true
        }
    }

    private fun activeAttachmentPaths(records: RecordPayload): Set<String> {
        val active = records.sessions.filterNot { it.isDeleted }.map { it.id }.toSet()
        return records.messages.asSequence().filter { it.sessionId in active }
            .flatMap { it.attachments.asSequence() }.map { it.relativePath }.toSet()
    }

    private suspend fun commitLocked(
        before: RecordPayload,
        after: RecordPayload,
        imported: Map<String, DirectoryRevision> = emptyMap(),
        attachmentIndex: Map<String, AttachmentContent> = emptyMap(),
        onCommitted: () -> Unit = {},
    ): RecordPayload {
        validateUnchangedOriginals(before, after)
        if (after == before && imported.isEmpty()) return after
        validatePayload(after)

        val previousSync = syncState
        val nextGeneration = if (previousSync == null) generation + 1 else nextTrackedGeneration(store.list())
        val createdAt = System.currentTimeMillis()
        val beforeParts = fileCodec.split(before)
        val afterParts = fileCodec.split(after)
        val revisions = previousSync?.revisions?.toMutableMap()
        if (revisions != null) {
            val device = previousSync.localDevice
            for (id in beforeParts.keys + afterParts.keys) {
                if (id in imported) continue
                if (beforeParts[id]?.syncContent() == afterParts[id]?.syncContent()) continue
                val deleted = id !in afterParts
                revisions[id] = revisions[id]?.advance(device, deleted)
                    ?: DirectoryRevision(deleted, mapOf(device to 1L), device)
            }
            revisions.putAll(imported)
        }
        val written = mutableListOf<String>()
        val nextFiles = sessionFiles.toMutableMap()
        var nextSettings = settingsFile

        try {
            for ((sessionId, part) in afterParts) {
                if (part == beforeParts[sessionId] && sessionFiles[sessionId] != null) continue
                val name = sessionFileName(sessionId, nextGeneration)
                val stored = store.write(name, fileCodec.encodeSession(nextGeneration, createdAt, part))
                written += stored
                nextFiles[sessionId] = stored
            }
            if (after.llmConfigs != before.llmConfigs || settingsFile == null) {
                val name = settingsFileName(nextGeneration)
                val stored = store.write(
                    name,
                    fileCodec.encodeSettings(nextGeneration, createdAt, after.llmConfigs),
                )
                nextSettings = stored
                written += stored
            }

            val removed = nextFiles.keys.filterNot { it in afterParts }
            removed.forEach { sessionId ->
                val old = nextFiles.getValue(sessionId)
                if (previousSync == null) store.delete(old)
                nextFiles.remove(sessionId)
            }
            if (previousSync != null) {
                val committed = previousSync.copy(
                    generation = nextGeneration,
                    sessionFiles = nextFiles.toMap(),
                    settingsFile = nextSettings,
                    revisions = checkNotNull(revisions),
                    attachmentIndex = (previousSync.attachmentIndex + attachmentIndex)
                        .filterKeys { it in activeAttachmentPaths(after) },
                )
                syncJournal.commit(committed)
                syncState = committed
            }
        } catch (error: Throwable) {
            written.forEach { name -> runCatching { store.delete(name) } }
            throw error
        }

        sessionFiles.forEach { (sessionId, old) ->
            val replacement = nextFiles[sessionId]
            if (replacement != old && (previousSync != null || replacement != null)) {
                runCatching { store.delete(old) }
            }
        }
        settingsFile?.let { old ->
            if (old != nextSettings) runCatching { store.delete(old) }
        }

        loaded = after
        sessionFiles = nextFiles
        settingsFile = nextSettings
        generation = nextGeneration
        onCommitted()
        withContext(NonCancellable) { rebuildCache(after) }
        _recordGeneration.value = generation
        return after
    }

    private fun nextTrackedGeneration(names: List<String>): Long = 1 + maxOf(
        generation,
        names.mapNotNull(::parseSessionFile).maxOfOrNull { it.generation } ?: 0L,
        names.mapNotNull(::parseSettingsFile).maxOfOrNull { it.generation } ?: 0L,
        names.filter(syncJournalName::matches).maxOfOrNull { it.substring(5, 25).toLongOrNull() ?: 0L } ?: 0L,
    )

    private fun SessionFilePayload.syncContent() = copy(
        session = session.copy(lastOpenedAt = session.createdAt),
        messages = messages.map { it.copy(processingStatus = "pending", aiRequestId = null) },
        aiResults = emptyList(),
        organizationState = null,
    )

    private fun SessionFilePayload.withDeviceState(old: SessionFilePayload?): SessionFilePayload {
        if (old == null) return this
        val oldMessages = old.messages.associateBy { it.id }
        val unchanged = messages.mapNotNull { remote ->
            val local = oldMessages[remote.id] ?: return@mapNotNull null
            local.takeIf {
                it.contentVersion == remote.contentVersion && it.currentContent == remote.currentContent &&
                    it.isDeleted == remote.isDeleted
            }
        }.associateBy { it.id }
        return copy(
            session = session.copy(lastOpenedAt = old.session.lastOpenedAt),
            messages = messages.map { message ->
                val local = unchanged[message.id]
                if (local == null) message else message.copy(
                    processingStatus = local.processingStatus, aiRequestId = local.aiRequestId,
                )
            },
            aiResults = old.aiResults.filter { it.messageId in unchanged },
            organizationState = old.organizationState.takeIf { old.sessionDocument == sessionDocument },
        )
    }

    /** Reads the current in-memory snapshot; used for settings that live in the record file. */
    suspend fun current(): RecordPayload = withContext(io) {
        mutex.withLock {
            (loaded ?: throw IllegalStateException("Restore records before reading"))
        }
    }

    /** First write to an empty, verified directory; used by first-session creation. */
    suspend fun initializeEmpty(): RecordPayload = withContext(io) {
        mutex.withLock {
            loaded ?: initializeLocked()
        }
    }

    /** The first write needs a verified file; an empty directory is initialized on demand. */
    suspend fun ensureWritable(): RecordPayload = initializeEmpty()

    private suspend fun initializeLocked(): RecordPayload {
        if (store.list().isNotEmpty()) throw IOException("Existing records must be restored first")
        val empty = RecordPayload()
        val name = store.write(
            settingsFileName(1),
            fileCodec.encodeSettings(1, System.currentTimeMillis(), empty.llmConfigs),
        )
        rebuildCache(empty)
        loaded = empty
        sessionFiles = mutableMapOf()
        settingsFile = name
        generation = 1
        return empty
    }

    /** Splits legacy whole-library snapshots into one file per conversation, then removes them. */
    private suspend fun migrateLegacy(names: List<String>): RecordPayload {
        // Legacy generations are encoded in the file name, so only the newest readable file is needed.
        val newest = names.groupBy(::legacyGeneration).entries
            .sortedByDescending { it.key }
            .firstNotNullOfOrNull { (_, group) ->
                group.mapNotNull { name -> runCatching { codec.decode(store.read(name)) }.getOrNull() }
                    .maxWithOrNull(compareBy<SnapshotEnvelope> { it.createdAt })
            }
            ?: throw IOException("No valid record snapshot; existing files were not modified")

        val parts = fileCodec.split(newest.payload)
        val written = mutableListOf<Pair<String, String>>()
        var settingsName: String? = null
        try {
            parts.forEach { (sessionId, part) ->
                val stored = store.write(
                    sessionFileName(sessionId, newest.generation),
                    fileCodec.encodeSession(newest.generation, newest.createdAt, part),
                )
                written += sessionId to stored
            }
            settingsName = store.write(
                settingsFileName(newest.generation),
                fileCodec.encodeSettings(newest.generation, newest.createdAt, newest.payload.llmConfigs),
            )
        } catch (error: Throwable) {
            written.forEach { (_, name) -> runCatching { store.delete(name) } }
            settingsName?.let { runCatching { store.delete(it) } }
            throw error
        }

        names.forEach { name -> runCatching { store.delete(name) } }
        val payload = fileCodec.combine(parts.values, newest.payload.llmConfigs)
        rebuildCache(payload)
        loaded = payload
        sessionFiles = written.associate { it.first to it.second }.toMutableMap()
        settingsFile = settingsName
        generation = newest.generation
        warning.value = false
        return payload
    }

    fun observeSessions() = database.records().observeSessions().map { rows -> rows.map { it.toRecord() } }
    fun observeMessages(sessionId: String) = database.records().observeMessages(sessionId).map { rows -> rows.map { it.toRecord() } }
    fun observeAiResults(sessionId: String) =
        database.records().observeAiResults(sessionId).map { rows -> rows.map { it.toRecord() } }
    fun observeQuotes(sessionId: String) =
        database.records().observeQuotes(sessionId).map { rows -> rows.map { it.toRecord() } }
    fun observeReactions(sessionId: String) =
        database.records().observeReactions(sessionId).map { rows -> rows.map { it.toRecord() } }
    suspend fun editHistory(messageId: String): List<EditRecord> = withContext(io) {
        database.records().editHistory(messageId).map { it.toRecord() }
    }
    fun observeMergeRoots(sessionId: String) =
        database.records().observeMergeRoots(sessionId).map { rows ->
            rows.mapNotNull { row ->
                val messageId = row.targetMessageId
                val title = row.sourceTitle
                if (messageId != null && title != null) messageId to title else null
            }.toMap()
        }
    fun observeSessionDocument(sessionId: String) =
        database.records().observeSessionDocument(sessionId).map { row -> row?.toRecord() }
    fun observeReminders(sessionId: String) =
        database.records().observeReminders(sessionId).map { rows -> rows.map { it.toRecord() } }
    fun observeScheduledReminders() =
        database.records().observeScheduledReminders().map { rows -> rows.map { it.toRecord() } }

    private fun validateUnchangedOriginals(before: RecordPayload, after: RecordPayload) {
        val existing = before.messages.associateBy { it.id }
        require(after.messages.all { message ->
            val old = existing[message.id]
            old == null || (old.rawContent == message.rawContent && old.createdAt == message.createdAt && old.sessionId == message.sessionId)
        }) { "Original message data cannot be changed" }
    }

    /** Rejects relations Room would refuse, without needing a successful write. */
    private fun validatePayload(payload: RecordPayload) {
        val sessionIds = payload.sessions.map { it.id }
        require(sessionIds.distinct().size == sessionIds.size) { "Duplicate session id" }
        val messageIds = payload.messages.map { it.id }
        require(messageIds.distinct().size == messageIds.size) { "Duplicate message id" }
        require(payload.messages.all { it.sessionId in sessionIds }) { "Message session missing" }
        require(payload.aiResults.all { it.messageId in messageIds }) { "AI result message missing" }
        require(payload.editHistory.all { it.messageId in messageIds }) { "Edit history message missing" }
        require(payload.quotes.all { it.messageId in messageIds }) { "Quote message missing" }
        require(payload.reactions.all { it.messageId in messageIds }) { "Reaction message missing" }
        require(payload.mergeRelations.all { it.targetSessionId in sessionIds }) { "Merge target missing" }
        require(payload.organizationStates.all { it.sessionId in sessionIds }) { "Organization session missing" }
        require(payload.sessionDocuments.all { it.sessionId in sessionIds }) { "Document session missing" }
        require(payload.messageMerges.all { it.sessionId in sessionIds }) { "Message merge session missing" }
        val reminderIds = payload.reminders.map { it.id }
        require(reminderIds.distinct().size == reminderIds.size) { "Duplicate reminder id" }
        val messagesBySession = payload.messages.associate { it.id to it.sessionId }
        require(payload.reminders.all { reminder ->
            reminder.sessionId in sessionIds &&
                reminder.triggerAt > 0 &&
                reminder.status in REMINDER_STATUSES &&
                (reminder.messageId == null || messagesBySession[reminder.messageId] == reminder.sessionId)
        }) { "Invalid reminder" }
    }

    private fun parseSessionFile(name: String): SessionFile? {
        if (!name.startsWith("session-") || !name.endsWith(".json")) return null
        val body = name.removePrefix("session-").removeSuffix(".json")
        val separator = body.lastIndexOf('-')
        if (separator <= 0) return null
        val suffix = body.substring(separator + 1)
        if (suffix.length != 20) return null
        val generation = suffix.toLongOrNull() ?: return null
        return SessionFile(body.substring(0, separator), generation, name)
    }

    private fun parseSettingsFile(name: String): SettingsFile? {
        if (!name.startsWith("settings-") || !name.endsWith(".json")) return null
        val suffix = name.removePrefix("settings-").removeSuffix(".json")
        if (suffix.length != 20) return null
        val generation = suffix.toLongOrNull() ?: return null
        return SettingsFile(generation, name)
    }

    private fun isLegacyFile(name: String): Boolean =
        name.matches(Regex("records-[0-9]{20}-[a-f0-9-]+\\.json"))

    private fun legacyGeneration(name: String): Long =
        name.removePrefix("records-").take(20).toLongOrNull() ?: 0L

    private suspend fun rebuildCache(payload: RecordPayload) {
        database.withTransaction {
            val dao = database.records()
            dao.clearEditHistory()
            dao.clearQuotes()
            dao.clearReactions()
            dao.clearLlmConfigs()
            dao.clearMergeRelations()
            dao.clearSessionDocuments()
            dao.clearReminders()
            dao.clearSessions()
            dao.insertSessions(payload.sessions.map { it.toEntity() })
            dao.insertMessages(payload.messages.map { it.toEntity() })
            dao.insertAiResults(payload.aiResults.map { it.toEntity() })
            dao.insertEditHistory(payload.editHistory.map { it.toEntity() })
            dao.insertQuotes(payload.quotes.map { it.toEntity() })
            dao.insertReactions(payload.reactions.map { it.toEntity() })
            dao.insertLlmConfigs(payload.llmConfigs.map { it.toEntity() })
            dao.insertMergeRelations(payload.mergeRelations.map { it.toEntity() })
            dao.insertSessionDocuments(payload.sessionDocuments.map { it.toEntity() })
            dao.insertReminders(payload.reminders.map { it.toEntity() })
        }
    }

    private data class SessionFile(val sessionId: String, val generation: Long, val name: String)
    private data class SettingsFile(val generation: Long, val name: String)
}
