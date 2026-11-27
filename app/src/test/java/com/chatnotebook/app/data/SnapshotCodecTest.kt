package com.chatnotebook.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCodecTest {
    private val codec = SnapshotCodec()

    @Test fun roundTripAndRejectTampering() {
        val payload = RecordPayload(sessions = listOf(SessionRecord("s", "主题", 1, 1)))
        val bytes = codec.encode(7, 10, payload)
        assertEquals(payload, codec.decode(bytes).payload)
        val tampered = bytes.toString(Charsets.UTF_8).replace("主题", "被篡改")
        assertThrows(IllegalArgumentException::class.java) { codec.decode(tampered.toByteArray()) }
    }

    @Test fun oldSnapshotsWithoutOrganizationMetadataRemainReadable() {
        val payload = RecordPayload(sessions = listOf(SessionRecord("s", "主题", 1, 1)))
        val oldShape = codec.encode(1, 1, payload)
        assertEquals(false, oldShape.toString(Charsets.UTF_8).contains("organizationStates"))
        assertEquals(false, oldShape.toString(Charsets.UTF_8).contains("messageMerges"))
        assertEquals(false, oldShape.toString(Charsets.UTF_8).contains("sessionDocuments"))
        assertEquals(emptyList<SessionOrganizationState>(), codec.decode(oldShape).payload.organizationStates)
        assertEquals(emptyList<MessageMergeRecord>(), codec.decode(oldShape).payload.messageMerges)
        assertEquals(emptyList<SessionDocumentRecord>(), codec.decode(oldShape).payload.sessionDocuments)
        val updated = payload.copy(organizationStates = listOf(SessionOrganizationState("s", 2, 1)))
        assertEquals(updated, codec.decode(codec.encode(2, 2, updated)).payload)
    }

    @Test fun sessionDocumentRoundTripAndValidation() {
        val payload = RecordPayload(
            sessions = listOf(SessionRecord("s", "主题", 1, 1)),
            sessionDocuments = listOf(
                SessionDocumentRecord("s", "# 文档", 2, 10, "mock", isMock = true),
            ),
        )
        assertEquals(payload, codec.decode(codec.encode(5, 5, payload)).payload)
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(
                codec.encode(
                    6, 6,
                    payload.copy(sessionDocuments = listOf(SessionDocumentRecord("missing", "x", 1, 1))),
                ),
            )
        }
    }

    @Test fun emptyFormatSegmentsAreOmittedSoOldChecksumsStayValid() {
        val message = MessageRecord(
            id = "m", sessionId = "s", role = "user", rawContent = "内容", createdAt = 1,
        )
        val payload = RecordPayload(
            sessions = listOf(SessionRecord("s", "主题", 1, 1)),
            messages = listOf(message),
        )
        val bytes = codec.encode(3, 3, payload)
        assertEquals(false, bytes.toString(Charsets.UTF_8).contains("formatSegments"))
        assertEquals(payload, codec.decode(bytes).payload)

        val merged = message.copy(
            formatSegments = listOf(MessageFormatSegment("内容", BubbleFormat(color = BubbleColor.Red).id)),
        )
        val withSegments = codec.encode(4, 4, payload.copy(messages = listOf(merged)))
        assertEquals(true, withSegments.toString(Charsets.UTF_8).contains("formatSegments"))
        assertEquals(merged, codec.decode(withSegments).payload.messages.single())
    }

    @Test fun emptyAttachmentsAreOmittedAndOldSnapshotsDecodeEmpty() {
        val payload = RecordPayload(
            sessions = listOf(SessionRecord("s", "主题", 1, 1)),
            messages = listOf(
                MessageRecord(id = "m", sessionId = "s", role = "user", rawContent = "内容", createdAt = 1),
            ),
        )
        val bytes = codec.encode(9, 9, payload)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("attachments"))
        assertTrue(codec.decode(bytes).payload.messages.single().attachments.isEmpty())
    }

    @Test fun sessionFilesRoundTripAttachmentsAndRejectInvalidMetadata() {
        val fileCodec = SessionFileCodec()
        val attachment = MessageAttachment("a1", "image", "attachments/a1.jpg", "image/jpeg", 10, 0, 1)
        val message = MessageRecord(
            id = "m", sessionId = "s", role = "user", rawContent = "内容",
            createdAt = 1, attachments = listOf(attachment),
        )
        val part = SessionFilePayload(
            session = SessionRecord("s", "主题", 1, 1),
            messages = listOf(message),
        )
        val decoded = fileCodec.decodeSession(fileCodec.encodeSession(1, 1, part))
        assertEquals(attachment, decoded.payload.messages.single().attachments.single())

        assertThrows(IllegalArgumentException::class.java) {
            fileCodec.encodeSession(
                1, 1,
                part.copy(messages = listOf(message.copy(attachments = listOf(attachment.copy(kind = "video"))))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fileCodec.encodeSession(
                1, 1,
                part.copy(messages = listOf(message.copy(attachments = listOf(attachment.copy(relativePath = "/tmp/a.jpg"))))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fileCodec.encodeSession(
                1, 1,
                part.copy(messages = listOf(message.copy(attachments = listOf(attachment, attachment)))),
            )
        }
    }

    @Test fun remindersRoundTripAndOldSnapshotsKeepChecksum() {
        val session = SessionRecord("s", "主题", 1, 1)
        val message = MessageRecord("m", "s", "user", "内容", createdAt = 1)
        val base = RecordPayload(sessions = listOf(session), messages = listOf(message))
        val before = codec.encode(10, 10, base).toString(Charsets.UTF_8)
        assertFalse(before.contains("reminders"))

        val decoded = codec.decode(before.toByteArray())
        assertEquals(
            before,
            codec.encode(decoded.generation, decoded.createdAt, decoded.payload).toString(Charsets.UTF_8),
        )

        val reminder = ReminderRecord(
            id = "r1",
            sessionId = "s",
            messageId = "m",
            triggerAt = 2_000,
            createdAt = 1_000,
            updatedAt = 1_000,
        )
        val payload = base.copy(reminders = listOf(reminder))
        assertEquals(payload, codec.decode(codec.encode(11, 11, payload)).payload)
    }

    @Test fun remindersRejectInvalidOwnershipStatusAndDuplicates() {
        val session = SessionRecord("s", "主题", 1, 1)
        val other = SessionRecord("o", "另一个", 2, 2)
        val foreignMessage = MessageRecord("m", "o", "user", "内容", createdAt = 1)
        val base = RecordPayload(
            sessions = listOf(session, other),
            messages = listOf(foreignMessage),
        )
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(
                codec.encode(
                    12, 12,
                    base.copy(
                        reminders = listOf(
                            ReminderRecord("r1", "s", "m", triggerAt = 10, createdAt = 1),
                        ),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(
                codec.encode(
                    13, 13,
                    base.copy(
                        reminders = listOf(
                            ReminderRecord("r1", "s", triggerAt = 10, createdAt = 1, status = "paused"),
                        ),
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(
                codec.encode(
                    14, 14,
                    base.copy(
                        reminders = listOf(
                            ReminderRecord("r1", "s", triggerAt = 10, createdAt = 1),
                            ReminderRecord("r1", "o", triggerAt = 20, createdAt = 1),
                        ),
                    ),
                ),
            )
        }
    }

    @Test fun sessionFilesCarryRemindersPerSession() {
        val fileCodec = SessionFileCodec()
        val payload = RecordPayload(
            sessions = listOf(SessionRecord("s1", "A", 1, 1), SessionRecord("s2", "B", 2, 2)),
            reminders = listOf(
                ReminderRecord("r1", "s1", triggerAt = 10, createdAt = 1),
                ReminderRecord("r2", "s2", triggerAt = 20, createdAt = 1),
            ),
        )
        val parts = fileCodec.split(payload)
        assertEquals(listOf("r1"), parts.getValue("s1").reminders.map { it.id })
        assertEquals(listOf("r2"), parts.getValue("s2").reminders.map { it.id })
        assertEquals(payload.reminders, fileCodec.combine(parts.values, emptyList()).reminders)

        assertThrows(IllegalArgumentException::class.java) {
            fileCodec.encodeSession(1, 1, parts.getValue("s1").copy(reminders = listOf(payload.reminders[1])))
        }
    }
}
