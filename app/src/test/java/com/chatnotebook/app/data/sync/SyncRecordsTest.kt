package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.AiResultRecord
import com.chatnotebook.app.data.LlmConfigRecord
import com.chatnotebook.app.data.MessageAttachment
import com.chatnotebook.app.data.MessageRecord
import com.chatnotebook.app.data.RecordPayload
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.SessionDocumentRecord
import com.chatnotebook.app.data.SessionFileCodec
import com.chatnotebook.app.data.SessionOrganizationState
import com.chatnotebook.app.data.SessionRecord
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncRecordsTest {
    private val session = SessionRecord("note", "旅行", 1, 4)
    private val message = MessageRecord("first", session.id, "user", "原文", "现文", createdAt = 2)

    @Test fun deviceOnlyStateDoesNotEnterWireDataOrChangeDigest() {
        val document = SessionDocumentRecord(session.id, "已保存的整理文档", 2, 8)
        val baseline = RecordPayload(
            sessions = listOf(session),
            messages = listOf(message, message.copy(id = "second", rawContent = "第二条")),
            sessionDocuments = listOf(document),
        )
        val deviceState = baseline.copy(
            sessions = listOf(session.copy(lastOpenedAt = 100)),
            messages = baseline.messages.reversed().map {
                it.copy(processingStatus = "running", aiRequestId = "local-request")
            },
            organizationStates = listOf(SessionOrganizationState(session.id, 2, 1)),
            llmConfigs = listOf(LlmConfigRecord("model", "local-config", "test", baseUrl = "https://local",
                model = "test-model", createdAt = 1, updatedAt = 1)),
            aiResults = listOf(AiResultRecord("ai", message.id, 1, "local-ai", "", emptyList(), "", 3, "success")),
        )

        val projected = project(deviceState)
        val wire = syncJson.encodeToString(projected)

        assertEquals(project(baseline).digest(), projected.digest())
        assertEquals(document, projected.document)
        assertEquals("原文", projected.messages.first().rawContent)
        assertEquals("现文", projected.messages.first().currentContent)
        for (excluded in listOf("lastOpenedAt", "viewedVersion", "aiRequestId", "processingStatus",
            "local-config", "local-request", "local-ai", "apiKey", "draftAttachments", "llmConfigs")
        ) {
            assertFalse(excluded, wire.contains(excluded))
        }
        assertNotEquals(projected.digest(), projected.copy(title = "改名").digest())
    }

    @Test fun attachmentDigestUsesBytesAndLengthInsteadOfLocalPath() {
        val contents = byteArrayOf(1, 2, 3)
        val identity = attachmentContent(contents.inputStream())
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", identity.sha256)
        assertEquals(3L, identity.sizeBytes)
        val attachment = MessageAttachment("image-id", "image", "attachments/a.png", "image/png", 3, createdAt = 2)
        fun withPath(path: String) = RecordPayload(
            sessions = listOf(session),
            messages = listOf(message.copy(attachments = listOf(attachment.copy(relativePath = path)))),
        )

        val first = project(withPath("attachments/a.png")) { identity }
        val second = project(withPath("attachments/b.png")) { identity }

        assertEquals(first.digest(), second.digest())
        assertEquals(setOf(identity), first.attachments())
        assertFalse(syncJson.encodeToString(first).contains("attachments/a.png"))
        assertEquals("attachments/copied.png",
            first.toSession { "attachments/copied.png" }.messages.single().attachments.single().relativePath)
    }

    @Test fun remindersStayOffTheWireWhenEmptyAndOrderDoesNotChangeDigest() {
        val base = RecordPayload(sessions = listOf(session), messages = listOf(message))
        val empty = project(base)
        assertEquals(emptyList<ReminderRecord>(), empty.reminders)
        assertFalse(syncJson.encodeToString(empty).contains("reminders"))

        val first = ReminderRecord("r1", session.id, message.id, triggerAt = 10, createdAt = 1, updatedAt = 1)
        val second = ReminderRecord("r2", session.id, triggerAt = 20, createdAt = 2, updatedAt = 2)
        val ordered = project(base.copy(reminders = listOf(first, second)))
        val reversed = project(base.copy(reminders = listOf(second, first)))

        assertEquals(ordered.digest(), reversed.digest())
        assertEquals(listOf("r1", "r2"), ordered.reminders.map { it.id })
        assertEquals(ordered.reminders, reversed.reminders)
        assertEquals(ordered.reminders, ordered.toSession { error("no attachments") }.reminders)
        assertEquals(message.id, ordered.reminders.single { it.id == "r1" }.messageId)
        assertNull(ordered.reminders.single { it.id == "r2" }.messageId)
    }

    @Test fun syncProtocolRejectsLegacyVersion() {
        assertEquals(3, LAN_SYNC_VERSION)
        SyncEpoch(1).requireCompatible()
        assertThrows(IllegalStateException::class.java) {
            SyncEpoch(1, LAN_SYNC_VERSION - 1).requireCompatible()
        }
    }

    private fun project(
        records: RecordPayload,
        resolve: (String) -> AttachmentContent = { error("unexpected attachment") },
    ) = SessionFileCodec().split(records).getValue(session.id).toSyncConversation(resolve)
}
