package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.EditRecord
import com.chatnotebook.app.data.MergeRelationRecord
import com.chatnotebook.app.data.MessageMergeRecord
import com.chatnotebook.app.data.QuoteRecord
import com.chatnotebook.app.data.ReactionRecord
import com.chatnotebook.app.data.REMINDER_CANCELLED
import com.chatnotebook.app.data.REMINDER_FIRED
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.SessionDocumentRecord
import java.util.UUID
import kotlinx.serialization.encodeToString

internal data class ConversationRevision(val entry: SyncManifestEntry, val conversation: SyncConversation?) {
    init {
        require(conversation == null || conversation.id == entry.id) { "同步对话标识不一致" }
        require(conversation?.digest() == entry.digest) { "同步对话摘要不一致" }
    }
}

/** 冲突解法：[primary] 必填；仅在“删除与编辑并发”时保留一份不丢失内容的 [copy]。 */
internal data class ConflictResolution(val primary: ConversationRevision, val copy: ConversationRevision? = null)

internal fun resolveConflict(first: ConversationRevision, second: ConversationRevision): ConflictResolution {
    require(first.entry.id == second.entry.id && first.entry.digest != second.entry.digest)
    require(compareVersions(first.entry.versions, second.entry.versions) == VersionOrder.Concurrent)

    val firstNote = first.conversation
    val secondNote = second.conversation
    if (firstNote == null || secondNote == null) return resolveDeleteConflict(first, second)

    val merged = mergeConversations(firstNote, secondNote)
    val joined = mergeVersions(first.entry.versions, second.entry.versions)
    val entry = SyncManifestEntry(
        merged.id,
        merged.digest(),
        joined,
        minOf(first.entry.author, second.entry.author),
    )
    return ConflictResolution(ConversationRevision(entry, merged))
}

/** 并集合并：两端各自计算必须得到同一结果，因此所有规则都与入参顺序无关。 */
private fun mergeConversations(first: SyncConversation, second: SyncConversation): SyncConversation {
    val (primary, secondary) = if (preferredFirst(first, second)) first to second else second to first
    return SyncConversation(
        id = primary.id,
        title = primary.title,
        createdAt = minOf(first.createdAt, second.createdAt),
        updatedAt = maxOf(first.updatedAt, second.updatedAt),
        messages = mergeUnion(primary.messages, secondary.messages, SyncMessage::id, ::newerMessage),
        editHistory = mergeUnion(
            primary.editHistory,
            secondary.editHistory,
            { Triple(it.messageId, it.fromVersion, it.toVersion) },
            ::newerEdit,
        ),
        quotes = mergeUnion(primary.quotes, secondary.quotes, QuoteRecord::messageId) { mine, _ -> mine },
        reactions = mergeUnion(primary.reactions, secondary.reactions, { it.messageId to it.emoji }) { mine, _ -> mine },
        mergeRelations = mergeUnion(
            primary.mergeRelations,
            secondary.mergeRelations,
            { syncJson.encodeToString(it) },
        ) { mine, _ -> mine },
        messageMerges = mergeUnion(primary.messageMerges, secondary.messageMerges, MessageMergeRecord::messageId) { mine, _ -> mine },
        document = newerDocument(primary.document, secondary.document),
        reminders = mergeUnion(primary.reminders, secondary.reminders, ReminderRecord::id, ::newerReminder),
    ).canonical()
}

/** “首选会话”用于没有独立时间戳的字段：更新时间较新者，相同时比较内容摘要。 */
private fun preferredFirst(first: SyncConversation, second: SyncConversation): Boolean = when {
    first.updatedAt != second.updatedAt -> first.updatedAt > second.updatedAt
    else -> first.digest() <= second.digest()
}

private fun <T, K> mergeUnion(primary: List<T>, secondary: List<T>, key: (T) -> K, prefer: (T, T) -> T): List<T> {
    val merged = LinkedHashMap<K, T>()
    for (item in secondary) merged[key(item)] = item
    for (item in primary) {
        val existing = merged[key(item)]
        merged[key(item)] = if (existing == null) item else prefer(item, existing)
    }
    return merged.values.toList()
}

private fun newerMessage(mine: SyncMessage, other: SyncMessage): SyncMessage = when {
    mine.updatedAt != other.updatedAt -> if (mine.updatedAt > other.updatedAt) mine else other
    mine.contentVersion != other.contentVersion -> if (mine.contentVersion > other.contentVersion) mine else other
    syncJson.encodeToString(mine) >= syncJson.encodeToString(other) -> mine
    else -> other
}

private fun newerEdit(mine: EditRecord, other: EditRecord): EditRecord = when {
    mine.editedAt != other.editedAt -> if (mine.editedAt > other.editedAt) mine else other
    syncJson.encodeToString(mine) >= syncJson.encodeToString(other) -> mine
    else -> other
}

private fun newerReminder(mine: ReminderRecord, other: ReminderRecord): ReminderRecord {
    val mineTerminal = mine.status == REMINDER_FIRED || mine.status == REMINDER_CANCELLED
    val otherTerminal = other.status == REMINDER_FIRED || other.status == REMINDER_CANCELLED
    return when {
        mine.updatedAt != other.updatedAt -> if (mine.updatedAt > other.updatedAt) mine else other
        mineTerminal != otherTerminal -> if (mineTerminal) mine else other
        syncJson.encodeToString(mine) >= syncJson.encodeToString(other) -> mine
        else -> other
    }
}

private fun newerDocument(first: SessionDocumentRecord?, second: SessionDocumentRecord?): SessionDocumentRecord? = when {
    first == null -> second
    second == null -> first
    first.generatedAt != second.generatedAt -> if (first.generatedAt > second.generatedAt) first else second
    syncJson.encodeToString(first) >= syncJson.encodeToString(second) -> first
    else -> second
}

private fun resolveDeleteConflict(first: ConversationRevision, second: ConversationRevision): ConflictResolution {
    val (tombstone, edited) = if (first.conversation == null) first to second else second to first
    val note = checkNotNull(edited.conversation)
    val firstId = revisionIdentity(first.entry)
    val secondId = revisionIdentity(second.entry)
    val copyId = stableId("conflict", tombstone.entry.id, minOf(firstId, secondId), maxOf(firstId, secondId))
    val copy = note.asConflictCopy(copyId, edited.entry.author)
    val joined = mergeVersions(first.entry.versions, second.entry.versions)
    return ConflictResolution(
        primary = ConversationRevision(tombstone.entry.copy(versions = joined), null),
        copy = ConversationRevision(SyncManifestEntry(copyId, copy.digest(), joined, edited.entry.author), copy),
    )
}

private fun revisionIdentity(entry: SyncManifestEntry): String =
    sha256(syncJson.encodeToString(entry.copy(versions = entry.versions.toSortedMap())).toByteArray())

private fun stableId(vararg parts: String): String =
    UUID.nameUUIDFromBytes(syncJson.encodeToString(parts.toList()).toByteArray()).toString()

private fun SyncConversation.asConflictCopy(copyId: String, deviceId: String): SyncConversation {
    val messageIds = messages.associate { it.id to stableId("message", copyId, it.id) }
    return copy(
        id = copyId,
        title = "$title（同步冲突 · 设备 ${deviceId.takeLast(8)}）",
        messages = messages.map { it.copy(id = messageIds.getValue(it.id)) },
        editHistory = editHistory.map { it.copy(messageId = messageIds.getValue(it.messageId)) },
        quotes = quotes.map {
            it.copy(
                messageId = messageIds.getValue(it.messageId),
                quotedMessageId = messageIds[it.quotedMessageId] ?: it.quotedMessageId,
            )
        },
        reactions = reactions.map { it.copy(messageId = messageIds.getValue(it.messageId)) },
        mergeRelations = mergeRelations.map {
            it.copy(
                id = stableId("merge-relation", copyId, syncJson.encodeToString(it)),
                targetSessionId = copyId,
                targetMessageId = it.targetMessageId?.let(messageIds::getValue),
                sourceSessionId = if (it.sourceSessionId == id) copyId else it.sourceSessionId,
                sourceMessageId = if (it.sourceSessionId == id) {
                    messageIds[it.sourceMessageId] ?: it.sourceMessageId
                } else it.sourceMessageId,
                rootSourceSessionId = if (it.rootSourceSessionId == id) copyId else it.rootSourceSessionId,
                rootSourceMessageId = if (it.rootSourceSessionId == id) {
                    messageIds[it.rootSourceMessageId] ?: it.rootSourceMessageId
                } else it.rootSourceMessageId,
            )
        },
        messageMerges = messageMerges.map {
            it.copy(
                sessionId = copyId,
                messageId = messageIds.getValue(it.messageId),
                sourceMessageIds = it.sourceMessageIds.map(messageIds::getValue),
            )
        },
        document = document?.copy(sessionId = copyId),
        reminders = reminders.map {
            it.copy(
                id = stableId("reminder", copyId, it.id),
                sessionId = copyId,
                messageId = it.messageId?.let(messageIds::getValue),
            )
        },
    ).canonical()
}