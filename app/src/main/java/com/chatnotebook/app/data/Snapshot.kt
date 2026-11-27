package com.chatnotebook.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

const val ATTACHMENT_DIRECTORY = "attachments/"
val ATTACHMENT_KINDS: Set<String> = setOf("image", "audio")

@Serializable
data class SessionRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val lastOpenedAt: Long = createdAt,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class MessageRecord(
    val id: String,
    val sessionId: String,
    val role: String,
    val rawContent: String,
    val currentContent: String = rawContent,
    val contentVersion: Int = 1,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val isDeleted: Boolean = false,
    val processingStatus: String = "pending",
    val aiRequestId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val formatSegments: List<MessageFormatSegment> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val attachments: List<MessageAttachment> = emptyList(),
)

@Serializable
data class MessageAttachment(
    val id: String,
    val kind: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long = 0,
    val createdAt: Long,
)

@Serializable
data class MessageFormatSegment(val text: String, val formatId: String? = null)

const val REMINDER_SCHEDULED = "scheduled"
const val REMINDER_FIRED = "fired"
const val REMINDER_CANCELLED = "cancelled"
val REMINDER_STATUSES: Set<String> = setOf(REMINDER_SCHEDULED, REMINDER_FIRED, REMINDER_CANCELLED)

/** 旧版本整理结果状态，仅用于读取既有记录时保持兼容。 */
const val AI_RESULT_VALID = "valid"
const val AI_RESULT_EXPIRED = "expired"

/** 旧版本处理状态，仅用于读取既有记录时保持兼容。 */
const val PROCESSING_PENDING = "pending"

@Serializable
data class ReminderRecord(
    val id: String,
    val sessionId: String,
    val messageId: String? = null,
    val triggerAt: Long,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val status: String = REMINDER_SCHEDULED,
)

@Serializable
data class AiResultRecord(
    val id: String,
    val messageId: String,
    val sourceContentVersion: Int,
    val cleanedContent: String,
    val summary: String,
    val keywords: List<String>,
    val structuredData: String,
    val processedAt: Long,
    val status: String,
    val requestId: String = "",
    val providerConfigId: String? = null,
    val modelSnapshot: String = "",
    val isMock: Boolean = false,
)

@Serializable
data class LlmConfigRecord(
    val id: String,
    val name: String,
    val providerType: String,
    val protocol: String = "openai-compatible",
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = true,
    val timeoutSeconds: Int = 30,
    val createdAt: Long,
    val updatedAt: Long,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class RecordPayload(
    val sessions: List<SessionRecord> = emptyList(),
    val messages: List<MessageRecord> = emptyList(),
    val aiResults: List<AiResultRecord> = emptyList(),
    val editHistory: List<EditRecord> = emptyList(),
    val quotes: List<QuoteRecord> = emptyList(),
    val reactions: List<ReactionRecord> = emptyList(),
    val mergeRelations: List<MergeRelationRecord> = emptyList(),
    val llmConfigs: List<LlmConfigRecord> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val organizationStates: List<SessionOrganizationState> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val messageMerges: List<MessageMergeRecord> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sessionDocuments: List<SessionDocumentRecord> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reminders: List<ReminderRecord> = emptyList(),
)

@Serializable
data class SessionDocumentRecord(
    val sessionId: String,
    val content: String,
    val messageCount: Int,
    val generatedAt: Long,
    val modelSnapshot: String = "",
    val isMock: Boolean = false,
)

@Serializable
data class MessageMergeRecord(
    val messageId: String,
    val sessionId: String,
    val sourceMessageIds: List<String>,
    val mergedAt: Long,
)

@Serializable
data class SessionOrganizationState(
    val sessionId: String,
    val completedVersion: Long = 0,
    val viewedVersion: Long = 0,
)

@Serializable data class EditRecord(val messageId: String, val fromVersion: Int, val toVersion: Int, val oldContent: String, val newContent: String, val editedAt: Long)
@Serializable data class QuoteRecord(val messageId: String, val quotedMessageId: String, val preview: String)
@Serializable data class ReactionRecord(val messageId: String, val emoji: String)
@Serializable
data class MergeRelationRecord(
    val id: String = "",
    val targetSessionId: String,
    val sourceSessionId: String,
    val sourceMessageId: String? = null,
    val targetMessageId: String? = null,
    val rootSourceSessionId: String? = null,
    val rootSourceMessageId: String? = null,
)

@Serializable
data class SnapshotEnvelope(
    val schemaVersion: Int,
    val generation: Long,
    val createdAt: Long,
    val payload: RecordPayload,
    val sha256: String,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class SessionFilePayload(
    val session: SessionRecord,
    val messages: List<MessageRecord> = emptyList(),
    val aiResults: List<AiResultRecord> = emptyList(),
    val editHistory: List<EditRecord> = emptyList(),
    val quotes: List<QuoteRecord> = emptyList(),
    val reactions: List<ReactionRecord> = emptyList(),
    val mergeRelations: List<MergeRelationRecord> = emptyList(),
    val organizationState: SessionOrganizationState? = null,
    val messageMerges: List<MessageMergeRecord> = emptyList(),
    val sessionDocument: SessionDocumentRecord? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reminders: List<ReminderRecord> = emptyList(),
)

@Serializable
data class SessionFileEnvelope(
    val schemaVersion: Int,
    val generation: Long,
    val createdAt: Long,
    val sessionId: String,
    val payload: SessionFilePayload,
    val sha256: String,
)

@Serializable
data class SettingsFilePayload(val llmConfigs: List<LlmConfigRecord> = emptyList())

@Serializable
data class SettingsFileEnvelope(
    val schemaVersion: Int,
    val generation: Long,
    val createdAt: Long,
    val payload: SettingsFilePayload,
    val sha256: String,
)

fun sessionFileName(sessionId: String, generation: Long): String {
    require(sessionId.isNotBlank() && generation > 0)
    return "session-$sessionId-${generation.toString().padStart(20, '0')}.json"
}

fun settingsFileName(generation: Long): String {
    require(generation > 0)
    return "settings-${generation.toString().padStart(20, '0')}.json"
}

/** Encodes one conversation and the global settings as separate, independently verified files. */
class SessionFileCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun split(payload: RecordPayload): Map<String, SessionFilePayload> =
        payload.sessions.filterNot { it.isDeleted }.associate { session ->
            val messages = payload.messages.filter { it.sessionId == session.id }
            val messageIds = messages.mapTo(HashSet()) { it.id }
            session.id to SessionFilePayload(
                session = session,
                messages = messages,
                aiResults = payload.aiResults.filter { it.messageId in messageIds },
                editHistory = payload.editHistory.filter { it.messageId in messageIds },
                quotes = payload.quotes.filter { it.messageId in messageIds },
                reactions = payload.reactions.filter { it.messageId in messageIds },
                mergeRelations = payload.mergeRelations.filter { it.targetSessionId == session.id },
                organizationState = payload.organizationStates.firstOrNull { it.sessionId == session.id },
                messageMerges = payload.messageMerges.filter { it.sessionId == session.id },
                sessionDocument = payload.sessionDocuments.firstOrNull { it.sessionId == session.id },
                reminders = payload.reminders.filter { it.sessionId == session.id },
            )
        }

    fun combine(parts: Collection<SessionFilePayload>, llmConfigs: List<LlmConfigRecord>): RecordPayload {
        val sorted = parts.sortedWith(compareBy({ it.session.createdAt }, { it.session.id }))
        return RecordPayload(
            sessions = sorted.map { it.session },
            messages = sorted.flatMap { it.messages },
            aiResults = sorted.flatMap { it.aiResults },
            editHistory = sorted.flatMap { it.editHistory },
            quotes = sorted.flatMap { it.quotes },
            reactions = sorted.flatMap { it.reactions },
            mergeRelations = sorted.flatMap { it.mergeRelations },
            llmConfigs = llmConfigs,
            organizationStates = sorted.mapNotNull { it.organizationState },
            messageMerges = sorted.flatMap { it.messageMerges },
            sessionDocuments = sorted.mapNotNull { it.sessionDocument },
            reminders = sorted.flatMap { it.reminders },
        )
    }

    fun encodeSession(generation: Long, createdAt: Long, payload: SessionFilePayload): ByteArray {
        require(generation > 0)
        validateSession(payload)
        val digest = checksum("session", generation, createdAt, json.encodeToString(payload))
        return json.encodeToString(
            SessionFileEnvelope(1, generation, createdAt, payload.session.id, payload, digest),
        ).toByteArray(Charsets.UTF_8)
    }

    fun decodeSession(bytes: ByteArray): SessionFileEnvelope {
        val envelope = json.decodeFromString<SessionFileEnvelope>(bytes.toString(Charsets.UTF_8))
        require(envelope.schemaVersion == 1) { "Unsupported session file schema" }
        require(envelope.generation > 0)
        require(envelope.sessionId == envelope.payload.session.id)
        require(!envelope.payload.session.isDeleted)
        require(envelope.sha256 == checksum("session", envelope.generation, envelope.createdAt, json.encodeToString(envelope.payload))) {
            "Session file checksum mismatch"
        }
        validateSession(envelope.payload)
        return envelope
    }

    fun encodeSettings(generation: Long, createdAt: Long, configs: List<LlmConfigRecord>): ByteArray {
        require(generation > 0)
        validateSettings(configs)
        val payload = SettingsFilePayload(configs)
        val digest = checksum("settings", generation, createdAt, json.encodeToString(payload))
        return json.encodeToString(SettingsFileEnvelope(1, generation, createdAt, payload, digest))
            .toByteArray(Charsets.UTF_8)
    }

    fun decodeSettings(bytes: ByteArray): SettingsFileEnvelope {
        val envelope = json.decodeFromString<SettingsFileEnvelope>(bytes.toString(Charsets.UTF_8))
        require(envelope.schemaVersion == 1) { "Unsupported settings file schema" }
        require(envelope.generation > 0)
        val serialized = json.encodeToString(envelope.payload)
        require(envelope.sha256 == checksum("settings", envelope.generation, envelope.createdAt, serialized)) {
            "Settings file checksum mismatch"
        }
        validateSettings(envelope.payload.llmConfigs)
        return envelope
    }

    private fun validateSettings(configs: List<LlmConfigRecord>) {
        require(configs.map { it.id }.distinct().size == configs.size)
        require(configs.all {
            it.name.isNotBlank() && it.baseUrl.isNotBlank() && it.model.isNotBlank()
        })
    }

    private fun validateSession(payload: SessionFilePayload) {
        val sessionId = payload.session.id
        val messageIds = payload.messages.map { it.id }
        require(messageIds.distinct().size == messageIds.size)
        require(payload.messages.all { it.sessionId == sessionId && it.contentVersion > 0 })
        validateAttachments(payload.messages)
        require(payload.aiResults.map { it.id }.distinct().size == payload.aiResults.size)
        require(payload.aiResults.all { it.messageId in messageIds })
        require(payload.editHistory.all { it.messageId in messageIds })
        require(payload.quotes.all { it.messageId in messageIds })
        require(payload.reactions.all { it.messageId in messageIds })
        require(payload.mergeRelations.all { it.targetSessionId == sessionId })
        require(payload.organizationState == null || payload.organizationState.sessionId == sessionId)
        require(payload.organizationState?.let {
            it.viewedVersion >= 0 && it.completedVersion >= it.viewedVersion
        } ?: true)
        require(payload.messageMerges.map { it.messageId }.distinct().size == payload.messageMerges.size)
        val messagesById = payload.messages.associateBy { it.id }
        require(payload.messageMerges.all { merge ->
            merge.sessionId == sessionId &&
                merge.sourceMessageIds.size >= 2 &&
                merge.sourceMessageIds.distinct().size == merge.sourceMessageIds.size &&
                messagesById[merge.messageId]?.sessionId == sessionId &&
                merge.sourceMessageIds.all { sourceId ->
                    messagesById[sourceId]?.sessionId == sessionId && messagesById[sourceId]?.isDeleted == true
                }
        })
        require(payload.sessionDocument == null || (
            payload.sessionDocument.sessionId == sessionId &&
                payload.sessionDocument.content.isNotBlank() &&
                payload.sessionDocument.messageCount > 0
            ))
        require(payload.reminders.map { it.id }.distinct().size == payload.reminders.size) {
            "Duplicate reminder id"
        }
        require(payload.reminders.all { reminder ->
            reminder.sessionId == sessionId &&
                reminder.triggerAt > 0 &&
                reminder.status in REMINDER_STATUSES &&
                (reminder.messageId == null || messagesById[reminder.messageId]?.sessionId == sessionId)
        }) { "Invalid reminder" }
    }

    private fun validateAttachments(messages: List<MessageRecord>) {
        messages.forEach { message ->
            require(message.attachments.map { it.id }.distinct().size == message.attachments.size) {
                "Duplicate attachment id"
            }
            require(message.attachments.all {
                it.id.isNotBlank() &&
                    it.kind in ATTACHMENT_KINDS &&
                    it.relativePath.startsWith(ATTACHMENT_DIRECTORY) &&
                    it.relativePath.length > ATTACHMENT_DIRECTORY.length &&
                    it.mimeType.isNotBlank() &&
                    it.sizeBytes > 0 &&
                    it.durationMs >= 0
            }) { "Invalid attachment metadata" }
        }
    }

    private fun checksum(kind: String, generation: Long, createdAt: Long, serialized: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$kind:1:$generation:$createdAt:$serialized".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class SnapshotCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(generation: Long, createdAt: Long, payload: RecordPayload): ByteArray {
        require(generation > 0)
        val digest = checksum(generation, createdAt, payload)
        return json.encodeToString(SnapshotEnvelope(1, generation, createdAt, payload, digest))
            .toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): SnapshotEnvelope {
        val envelope = json.decodeFromString<SnapshotEnvelope>(bytes.toString(Charsets.UTF_8))
        require(envelope.schemaVersion == 1) { "Unsupported record schema" }
        require(envelope.generation > 0)
        require(envelope.sha256 == checksum(envelope.generation, envelope.createdAt, envelope.payload)) {
            "Record checksum mismatch"
        }
        val sessionIds = envelope.payload.sessions.map { it.id }
        require(sessionIds.distinct().size == sessionIds.size)
        val messageIds = envelope.payload.messages.map { it.id }
        require(messageIds.distinct().size == messageIds.size)
        require(envelope.payload.messages.all { it.sessionId in sessionIds && it.contentVersion > 0 })
        require(envelope.payload.aiResults.map { it.id }.distinct().size == envelope.payload.aiResults.size)
        require(envelope.payload.aiResults.all { it.messageId in messageIds })
        require(envelope.payload.organizationStates.map { it.sessionId }.distinct().size == envelope.payload.organizationStates.size)
        require(envelope.payload.organizationStates.all {
            it.sessionId in sessionIds && it.viewedVersion >= 0 && it.completedVersion >= it.viewedVersion
        })
        require(envelope.payload.messageMerges.map { it.messageId }.distinct().size == envelope.payload.messageMerges.size)
        val messagesById = envelope.payload.messages.associateBy { it.id }
        require(envelope.payload.messageMerges.all { merge ->
            merge.sourceMessageIds.size >= 2 &&
                merge.sourceMessageIds.distinct().size == merge.sourceMessageIds.size &&
                messagesById[merge.messageId]?.sessionId == merge.sessionId &&
                merge.sourceMessageIds.all { sourceId ->
                    messagesById[sourceId]?.sessionId == merge.sessionId && messagesById[sourceId]?.isDeleted == true
                }
        })
        require(envelope.payload.sessionDocuments.map { it.sessionId }.distinct().size ==
            envelope.payload.sessionDocuments.size)
        require(envelope.payload.sessionDocuments.all {
            it.sessionId in sessionIds && it.content.isNotBlank() && it.messageCount > 0
        })
        require(envelope.payload.reminders.map { it.id }.distinct().size ==
            envelope.payload.reminders.size)
        require(envelope.payload.reminders.all { reminder ->
            reminder.sessionId in sessionIds &&
                reminder.triggerAt > 0 &&
                reminder.status in REMINDER_STATUSES &&
                (reminder.messageId == null || messagesById[reminder.messageId]?.sessionId == reminder.sessionId)
        }) { "Invalid reminder" }
        val configIds = envelope.payload.llmConfigs.map { it.id }
        require(configIds.distinct().size == configIds.size)
        require(envelope.payload.llmConfigs.all {
            it.name.isNotBlank() && it.baseUrl.isNotBlank() && it.model.isNotBlank()
        })
        return envelope
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun checksum(generation: Long, createdAt: Long, payload: RecordPayload): String =
        digest("1:$generation:$createdAt:${json.encodeToString(payload)}".toByteArray(Charsets.UTF_8))
}
