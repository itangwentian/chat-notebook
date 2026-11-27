package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.EditRecord
import com.chatnotebook.app.data.MergeRelationRecord
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageFormatSegment
import com.chatnotebook.app.data.MessageMergeRecord
import com.chatnotebook.app.data.MessageRecord
import com.chatnotebook.app.data.QuoteRecord
import com.chatnotebook.app.data.ReactionRecord
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.SessionDocumentRecord
import com.chatnotebook.app.data.SessionFilePayload
import com.chatnotebook.app.data.SessionRecord
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val LAN_SYNC_VERSION = 3
internal val syncJson = Json { encodeDefaults = true }
private val sha256Pattern = Regex("[a-f0-9]{64}")

@Serializable
internal data class AttachmentContent(val sha256: String, val sizeBytes: Long) {
    init {
        require(sha256Pattern.matches(sha256) && sizeBytes > 0) { "附件摘要无效" }
    }
}

@Serializable
internal data class SyncAttachment(
    val id: String,
    val kind: String,
    val mimeType: String,
    val durationMs: Long,
    val createdAt: Long,
    val content: AttachmentContent,
) {
    fun toRecord(path: String) =
        MessageAttachment(id, kind, path, mimeType, content.sizeBytes, durationMs, createdAt)
}

@Serializable
internal data class SyncMessage(
    val id: String,
    val role: String,
    val rawContent: String,
    val currentContent: String,
    val contentVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val formatSegments: List<MessageFormatSegment>,
    val attachments: List<SyncAttachment>,
) {
    fun toRecord(sessionId: String, attachmentPath: (AttachmentContent) -> String) = MessageRecord(
        id = id,
        sessionId = sessionId,
        role = role,
        rawContent = rawContent,
        currentContent = currentContent,
        contentVersion = contentVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        formatSegments = formatSegments,
        attachments = attachments.map { it.toRecord(attachmentPath(it.content)) },
    )
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
internal data class SyncConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<SyncMessage>,
    val editHistory: List<EditRecord>,
    val quotes: List<QuoteRecord>,
    val reactions: List<ReactionRecord>,
    val mergeRelations: List<MergeRelationRecord>,
    val messageMerges: List<MessageMergeRecord>,
    val document: SessionDocumentRecord?,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reminders: List<ReminderRecord> = emptyList(),
) {
    fun canonical() = copy(
        messages = messages.sortedBy { it.id },
        editHistory = editHistory.sortedWith(compareBy({ it.messageId }, { it.fromVersion }, { it.toVersion })),
        quotes = quotes.sortedBy { it.messageId },
        reactions = reactions.sortedWith(compareBy({ it.messageId }, { it.emoji })),
        mergeRelations = mergeRelations.sortedWith(
            compareBy({ it.id }, { it.sourceSessionId }, { it.sourceMessageId }, { it.targetMessageId },
                { it.rootSourceSessionId }, { it.rootSourceMessageId }),
        ),
        messageMerges = messageMerges.sortedBy { it.messageId },
        reminders = reminders.sortedBy { it.id },
    )

    fun digest(): String = sha256(syncJson.encodeToString(canonical()).toByteArray(Charsets.UTF_8))

    fun attachments(): Set<AttachmentContent> =
        messages.flatMap { message -> message.attachments.map { it.content } }.toSet()

    fun toSession(attachmentPath: (AttachmentContent) -> String) = SessionFilePayload(
        session = SessionRecord(id, title, createdAt, updatedAt),
        messages = messages.map { it.toRecord(id, attachmentPath) },
        editHistory = editHistory,
        quotes = quotes,
        reactions = reactions,
        mergeRelations = mergeRelations,
        messageMerges = messageMerges,
        sessionDocument = document,
        reminders = reminders,
    )
}

internal fun SessionFilePayload.toSyncConversation(
    attachmentContent: (String) -> AttachmentContent,
): SyncConversation = SyncConversation(
    id = session.id,
    title = session.title,
    createdAt = session.createdAt,
    updatedAt = session.updatedAt,
    messages = messages.map { message ->
        SyncMessage(
            message.id, message.role, message.rawContent, message.currentContent, message.contentVersion,
            message.createdAt, message.updatedAt, message.isDeleted, message.formatSegments,
            message.attachments.map {
                SyncAttachment(it.id, it.kind, it.mimeType, it.durationMs, it.createdAt, attachmentContent(it.relativePath))
            },
        )
    },
    editHistory = editHistory,
    quotes = quotes,
    reactions = reactions,
    mergeRelations = mergeRelations,
    messageMerges = messageMerges,
    document = sessionDocument,
    reminders = reminders,
).canonical()

@Serializable
internal data class SyncManifest(
    val protocol: Int = LAN_SYNC_VERSION,
    val conversations: List<SyncManifestEntry>,
    val availableAttachments: Set<AttachmentContent> = emptySet(),
)

@Serializable
internal data class SyncManifestEntry(
    val id: String,
    val digest: String?,
    val versions: Map<String, Long>,
    val author: String,
) {
    init {
        require(id.isNotBlank() && (digest == null || sha256Pattern.matches(digest)))
        require(versions.isNotEmpty() && author in versions && versions.all { it.key.isNotBlank() && it.value > 0 })
    }
}

internal fun attachmentContent(input: InputStream): AttachmentContent {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var length = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        length += count
        digest.update(buffer, 0, count)
    }
    return AttachmentContent(digest.digest().toHex(), length)
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
