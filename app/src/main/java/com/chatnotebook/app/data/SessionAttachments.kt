package com.chatnotebook.app.data

import java.io.IOException

internal class SessionAttachmentCleanupException(val failures: Map<String, Exception>) :
    IOException("对话已删除，${failures.size} 个附件清理失败") {
    init {
        failures.values.forEach(::addSuppressed)
    }
}

internal fun unusedSessionAttachmentPaths(
    deletedMessages: List<MessageRecord>,
    remainingRecords: RecordPayload,
): Set<String> {
    val activeSessionIds = remainingRecords.sessions.asSequence()
        .filterNot { it.isDeleted }
        .map { it.id }
        .toSet()
    val remainingPaths = remainingRecords.messages.asSequence()
        .filter { it.sessionId in activeSessionIds }
        .flatMap { it.attachments.asSequence() }
        .map { it.relativePath }
        .toSet()
    return deletedMessages.asSequence()
        .flatMap { it.attachments.asSequence() }
        .map { it.relativePath }
        .filterNot { it in remainingPaths }
        .toSet()
}
