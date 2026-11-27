package com.chatnotebook.app.data

import kotlinx.coroutines.CancellationException
import java.util.UUID

private val blankLineRun = Regex("\\n\\s*\\n+")

/** Splits selected bubbles into merged segments, keeping each bubble's bubble format. */
fun buildMergedSegments(
    selected: List<MessageRecord>,
    formatOf: (String) -> BubbleFormat? = { null },
): List<MessageFormatSegment> = selected.map { message ->
    val content = message.currentContent.replace(blankLineRun, "\n").trim()
    val text = if (message.role == "user") content
    else "${if (message.role == "assistant") "AI" else message.role}：$content"
    MessageFormatSegment(text, formatOf(message.id)?.id)
}

/** Joins selected bubbles with one newline each, dropping blank lines at the edges or between them. */
fun buildMergedContent(selected: List<MessageRecord>): String =
    buildMergedSegments(selected).joinToString("\n") { it.text }

class ChatSessionService(
    private val records: RecordRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun create(title: String = "新对话"): String {
        val normalized = title.trim().ifEmpty { "新对话" }
        val id = newId()
        records.update { before ->
            val time = now()
            before.copy(sessions = before.sessions + SessionRecord(id, normalized, time, time))
        }
        return id
    }

    /** 记录为空时创建两个默认对话并选中“草稿”；已有对话时返回 null。 */
    suspend fun seedDefaultConversations(): String? {
        if (records.current().sessions.any { !it.isDeleted }) return null
        val importantId = newId()
        val draftId = newId()
        val time = now()
        records.update { before ->
            before.copy(
                sessions = before.sessions + listOf(
                    SessionRecord(importantId, "极其重要", time, time, lastOpenedAt = 0),
                    SessionRecord(draftId, "草稿", time, time, lastOpenedAt = time),
                ),
            )
        }
        return draftId
    }

    suspend fun rename(id: String, title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "对话标题不能为空" }
        records.update { before ->
            require(before.sessions.any { it.id == id && !it.isDeleted }) { "对话不存在" }
            before.copy(sessions = before.sessions.map {
                if (it.id == id) it.copy(title = normalized, updatedAt = now()) else it
            })
        }
    }

    suspend fun delete(id: String, deleteAttachment: (String) -> Unit) {
        var deletedMessages = emptyList<MessageRecord>()
        val remaining = records.update { before ->
            require(before.sessions.any { it.id == id && !it.isDeleted }) { "对话不存在" }
            deletedMessages = before.messages.filter { it.sessionId == id }
            before.copy(
                sessions = before.sessions.map {
                    if (it.id == id) it.copy(isDeleted = true, updatedAt = now()) else it
                },
                organizationStates = before.organizationStates.filterNot { it.sessionId == id },
                sessionDocuments = before.sessionDocuments.filterNot { it.sessionId == id },
                reminders = before.reminders.filterNot { it.sessionId == id },
            )
        }
        val failures = mutableMapOf<String, Exception>()
        unusedSessionAttachmentPaths(deletedMessages, remaining).forEach { path ->
            try {
                deleteAttachment(attachmentFileName(path))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures[path] = error
            }
        }
        if (failures.isNotEmpty()) throw SessionAttachmentCleanupException(failures)
    }

    suspend fun markOpened(id: String) {
        records.update { before ->
            require(before.sessions.any { it.id == id && !it.isDeleted }) { "对话不存在" }
            before.copy(sessions = before.sessions.map {
                if (it.id == id) it.copy(lastOpenedAt = now()) else it
            })
        }
    }

    suspend fun send(
        sessionId: String,
        text: String,
        attachments: List<MessageAttachment> = emptyList(),
        quotedMessageId: String? = null,
    ): String? {
        val content = text.trim()
        if (content.isEmpty() && attachments.isEmpty()) return null
        val id = newId()
        records.update { before ->
            require(before.sessions.any { it.id == sessionId && !it.isDeleted }) { "对话不存在" }
            val time = now()
            var next = before.copy(
                messages = before.messages + MessageRecord(
                    id = id,
                    sessionId = sessionId,
                    role = "user",
                    rawContent = content,
                    createdAt = time,
                    attachments = attachments,
                ),
                sessions = before.sessions.map {
                    if (it.id == sessionId) it.copy(updatedAt = time) else it
                },
            )
            val quoted = quotedMessageId?.let { before.messages.firstOrNull { message -> message.id == it } }
            if (quoted != null) {
                next = next.copy(
                    quotes = next.quotes + QuoteRecord(id, quoted.id, quoted.currentContent),
                )
            }
            next
        }
        return id
    }

    suspend fun edit(messageId: String, newContent: String) {
        val content = newContent.trim()
        require(content.isNotEmpty()) { "内容不能为空" }
        records.update { before ->
            val message = before.messages.firstOrNull { it.id == messageId } ?: error("消息不存在")
            require(message.role == "user") { "AI 消息不可编辑" }
            require(!message.isDeleted) { "消息已删除" }
            if (message.currentContent == content) return@update before
            val toVersion = message.contentVersion + 1
            val time = now()
            before.copy(
                sessions = before.sessions.map {
                    if (it.id == message.sessionId) it.copy(updatedAt = time) else it
                },
                messages = before.messages.map {
                    if (it.id == messageId) {
                        it.copy(
                            currentContent = content,
                            contentVersion = toVersion,
                            updatedAt = now(),
                            processingStatus = "pending",
                            aiRequestId = null,
                            formatSegments = emptyList(),
                        )
                    } else {
                        it
                    }
                },
                editHistory = before.editHistory + EditRecord(
                    messageId = messageId,
                    fromVersion = message.contentVersion,
                    toVersion = toVersion,
                    oldContent = message.currentContent,
                    newContent = content,
                    editedAt = now(),
                ),
                aiResults = before.aiResults.map {
                    if (it.messageId == messageId && it.status == AI_RESULT_VALID) {
                        it.copy(status = AI_RESULT_EXPIRED)
                    } else {
                        it
                    }
                },
            )
        }
    }

    suspend fun deleteMessages(messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        records.update { before ->
            val deleted = messageIds.filter { id ->
                before.messages.any { it.id == id && !it.isDeleted }
            }.toSet()
            if (deleted.isEmpty()) return@update before
            val time = now()
            val touchedSessions = before.messages.filter { it.id in deleted }.map { it.sessionId }.toSet()
            before.copy(
                sessions = before.sessions.map {
                    if (it.id in touchedSessions) it.copy(updatedAt = time) else it
                },
                messages = before.messages.map {
                    if (it.id in deleted) {
                        it.copy(isDeleted = true, updatedAt = time, aiRequestId = null)
                    } else {
                        it
                    }
                },
                quotes = before.quotes.map {
                    if (it.quotedMessageId in deleted) it.copy(preview = "") else it
                },
                aiResults = before.aiResults.map {
                    if (it.messageId in deleted && it.status == AI_RESULT_VALID) {
                        it.copy(status = AI_RESULT_EXPIRED)
                    } else {
                        it
                    }
                },
            )
        }
    }

    suspend fun mergeMessages(sessionId: String, messageIds: Set<String>): String {
        require(messageIds.size >= 2) { "至少选择两条消息" }
        val mergedId = newId()
        records.update { before ->
            require(before.sessions.any { it.id == sessionId && !it.isDeleted }) { "对话不存在" }
            val selected = before.messages.filter { it.id in messageIds && it.sessionId == sessionId && !it.isDeleted }
                .sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })
            require(selected.size == messageIds.size) { "所选消息已变化，请重新选择" }
            val segments = buildMergedSegments(selected) { id ->
                before.reactions.firstOrNull { it.messageId == id }?.let { BubbleFormat.fromId(it.emoji) }
            }
            val content = segments.joinToString("\n") { it.text }
            val time = now()
            before.copy(
                sessions = before.sessions.map {
                    if (it.id == sessionId) it.copy(updatedAt = time) else it
                },
                messages = before.messages.map { message ->
                    if (message.id in messageIds) {
                        message.copy(isDeleted = true, updatedAt = time, aiRequestId = null)
                    } else message
                } + MessageRecord(
                    id = mergedId,
                    sessionId = sessionId,
                    role = "user",
                    rawContent = content,
                    createdAt = selected.first().createdAt,
                    updatedAt = time,
                    formatSegments = segments,
                    attachments = selected.flatMap { it.attachments }.distinctBy { it.relativePath },
                ),
                messageMerges = before.messageMerges + MessageMergeRecord(
                    messageId = mergedId,
                    sessionId = sessionId,
                    sourceMessageIds = selected.map { it.id },
                    mergedAt = time,
                ),
                quotes = before.quotes.map { quote ->
                    if (quote.quotedMessageId in messageIds) quote.copy(preview = "") else quote
                },
                aiResults = before.aiResults.map { result ->
                    if (result.messageId in messageIds && result.status == AI_RESULT_VALID) {
                        result.copy(status = AI_RESULT_EXPIRED)
                    } else result
                },
            )
        }
        return mergedId
    }

    suspend fun forwardMessages(
        sourceSessionId: String,
        messageIds: Set<String>,
        targetSessionId: String,
    ): Int {
        require(messageIds.isNotEmpty()) { "请选择要转发的消息" }
        require(sourceSessionId != targetSessionId) { "不能转发到当前对话" }
        records.update { before ->
            require(before.sessions.any { it.id == sourceSessionId && !it.isDeleted }) { "对话不存在" }
            require(before.sessions.any { it.id == targetSessionId && !it.isDeleted }) { "目标对话不存在" }
            val selected = before.messages
                .filter { it.id in messageIds && it.sessionId == sourceSessionId && !it.isDeleted }
                .sortedWith(compareBy<MessageRecord> { it.createdAt }.thenBy { it.id })
            require(selected.size == messageIds.size) { "所选消息已变化，请重新选择" }
            val time = now()
            val copies = selected.mapIndexed { index, message ->
                MessageRecord(
                    id = newId(),
                    sessionId = targetSessionId,
                    role = message.role,
                    rawContent = message.currentContent,
                    createdAt = time + index,
                    updatedAt = time + index,
                    attachments = message.attachments,
                )
            }
            before.copy(
                sessions = before.sessions.map {
                    if (it.id == targetSessionId) it.copy(updatedAt = time + copies.size) else it
                },
                messages = before.messages + copies,
            )
        }
        return messageIds.size
    }

    suspend fun setBubbleFormat(messageId: String, formatId: String?) {
        require(formatId == null || BubbleFormat.fromId(formatId) != null) { "未知的气泡格式" }
        records.update { before ->
            val message = before.messages.firstOrNull { it.id == messageId && !it.isDeleted }
                ?: error("消息不存在")
            val existing = before.reactions.filter { it.messageId == messageId }
            val cleared = formatId == null || (existing.size == 1 && existing.single().emoji == formatId)
            before.copy(
                sessions = before.sessions.map {
                    if (it.id == message.sessionId) it.copy(updatedAt = now()) else it
                },
                reactions = before.reactions.filterNot { it.messageId == messageId } +
                    if (cleared) emptyList() else listOf(ReactionRecord(messageId, formatId!!)),
            )
        }
    }
}
