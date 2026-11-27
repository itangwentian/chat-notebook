package com.chatnotebook.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAttachmentsTest {
    @Test fun preservesPathsReferencedByAnyMessageInRemainingSessions() {
        val deletedMessages = listOf(
            message("source", "unique", "shared", "soft-deleted", "duplicate"),
            message("source", "duplicate", "deleted-session", deleted = true),
        )
        val remaining = RecordPayload(
            sessions = listOf(
                SessionRecord("source", "Source", 1, 1, isDeleted = true),
                SessionRecord("active", "Active", 1, 1),
                SessionRecord("deleted", "Deleted", 1, 1, isDeleted = true),
            ),
            messages = deletedMessages + listOf(
                message("active", "shared"),
                message("active", "soft-deleted", deleted = true),
                message("deleted", "deleted-session"),
            ),
        )

        assertEquals(
            setOf("attachments/unique.jpg", "attachments/duplicate.jpg", "attachments/deleted-session.jpg"),
            unusedSessionAttachmentPaths(deletedMessages, remaining),
        )
    }

    private fun message(sessionId: String, vararg names: String, deleted: Boolean = false) =
        MessageRecord(
            id = "$sessionId-${names.first()}",
            sessionId = sessionId,
            role = "user",
            rawContent = "",
            createdAt = 1,
            isDeleted = deleted,
            attachments = names.map { name ->
                MessageAttachment("$sessionId-$name", "image", "attachments/$name.jpg", "image/jpeg", 1, createdAt = 1)
            },
        )
}
