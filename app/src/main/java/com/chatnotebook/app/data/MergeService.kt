package com.chatnotebook.app.data

import java.util.UUID

/**
 * Copies messages into a new session in a single snapshot write. The sources stay
 * untouched; derived results and reactions are intentionally not copied.
 */
class MergeService(
    private val records: RecordRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun merge(sourceIds: List<String>, title: String): String {
        require(sourceIds.size >= 2) { "至少选择两个对话" }
        require(sourceIds.distinct().size == sourceIds.size) { "对话不能重复" }
        require(title.isNotBlank()) { "标题不能为空" }
        val targetId = newId()

        records.update { before ->
            sourceIds.forEach { id ->
                require(before.sessions.any { it.id == id && !it.isDeleted }) { "对话不存在" }
            }
            val sourceMessages = before.messages
                .filter { it.sessionId in sourceIds && !it.isDeleted }
                .sortedWith(compareBy({ sourceIds.indexOf(it.sessionId) }, { it.createdAt }, { it.id }))
            val idMap = sourceMessages.associate { it.id to newId() }
            val time = now()

            before.copy(
                sessions = before.sessions + SessionRecord(
                    id = targetId,
                    title = title.trim(),
                    createdAt = time,
                    updatedAt = time,
                    lastOpenedAt = time,
                ),
                messages = before.messages + sourceMessages.map { message ->
                    message.copy(
                        id = idMap.getValue(message.id),
                        sessionId = targetId,
                        processingStatus = PROCESSING_PENDING,
                        aiRequestId = null,
                    )
                },
                editHistory = before.editHistory + before.editHistory
                    .filter { it.messageId in idMap }
                    .map { it.copy(messageId = idMap.getValue(it.messageId)) },
                quotes = before.quotes + before.quotes
                    .filter { it.messageId in idMap }
                    .map { quote ->
                        QuoteRecord(
                            messageId = idMap.getValue(quote.messageId),
                            quotedMessageId = idMap[quote.quotedMessageId] ?: quote.quotedMessageId,
                            preview = quote.preview,
                        )
                    },
                mergeRelations = before.mergeRelations + sourceMessages.map { message ->
                    val root = before.mergeRelations.firstOrNull { it.targetMessageId == message.id }
                    MergeRelationRecord(
                        id = newId(),
                        targetSessionId = targetId,
                        sourceSessionId = message.sessionId,
                        sourceMessageId = message.id,
                        targetMessageId = idMap.getValue(message.id),
                        rootSourceSessionId = root?.rootSourceSessionId
                            ?: root?.sourceSessionId
                            ?: message.sessionId,
                        rootSourceMessageId = root?.rootSourceMessageId
                            ?: root?.sourceMessageId
                            ?: message.id,
                    )
                },
            )
        }
        return targetId
    }
}
