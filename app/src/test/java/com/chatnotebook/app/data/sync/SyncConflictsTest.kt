package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.EditRecord
import com.chatnotebook.app.data.MergeRelationRecord
import com.chatnotebook.app.data.MessageMergeRecord
import com.chatnotebook.app.data.MessageRecord
import com.chatnotebook.app.data.QuoteRecord
import com.chatnotebook.app.data.REMINDER_CANCELLED
import com.chatnotebook.app.data.REMINDER_FIRED
import com.chatnotebook.app.data.ReactionRecord
import com.chatnotebook.app.data.ReminderRecord
import com.chatnotebook.app.data.SessionDocumentRecord
import com.chatnotebook.app.data.SessionFileCodec
import com.chatnotebook.app.data.SessionFilePayload
import com.chatnotebook.app.data.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConflictsTest {
    @Test fun bothPeersMergeConcurrentEditsIntoOneConversation() {
        val a = revision("a", "甲的编辑", 500, extraMessages = listOf(message("a-only", "甲新增", createdAt = 2)))
        val b = revision("b", "乙的编辑", 1, extraMessages = listOf(message("b-only", "乙新增", createdAt = 3)))

        val resolution = resolveConflict(a, b)
        assertEquals(resolution, resolveConflict(b, a))
        assertNull(resolution.copy)

        val merged = requireNotNull(resolution.primary.conversation)
        assertEquals("note", merged.id)
        assertEquals("笔记", merged.title)
        assertEquals(1, merged.createdAt)
        assertEquals(500, merged.updatedAt)
        assertEquals(
            listOf("a-only", "b-only", "old-1", "old-2", "visible"),
            merged.messages.map { it.id },
        )
        assertEquals("甲的编辑", merged.messages.single { it.id == "visible" }.currentContent)
        assertEquals(mapOf("a" to 1L, "b" to 1L), resolution.primary.entry.versions)
        assertEquals(merged.digest(), resolution.primary.entry.digest)
    }

    @Test fun unionKeepsInteractionsFromBothSides() {
        val a = revision("a", "甲的编辑", 2, extraReactions = listOf(ReactionRecord("visible", "👍")))
        val b = revision("b", "乙的编辑", 1, extraReactions = listOf(ReactionRecord("visible", "🎉")))

        val merged = requireNotNull(resolveConflict(a, b).primary.conversation)

        assertEquals(setOf("📝", "👍", "🎉"), merged.reactions.map { it.emoji }.toSet())
        assertEquals(1, merged.quotes.size)
        assertEquals(1, merged.mergeRelations.size)
        assertEquals(1, merged.messageMerges.size)
    }

    @Test fun newerMessageVersionWinsInEitherDirection() {
        val deleted = message("shared", "原文", createdAt = 5, updatedAt = 10, isDeleted = true)
        val edited = message("shared", "乙改", createdAt = 5, updatedAt = 8).copy(contentVersion = 2)
        val a = revision("a", "甲的编辑", 2, extraMessages = listOf(deleted))
        val b = revision("b", "乙的编辑", 1, extraMessages = listOf(edited))

        val merged = requireNotNull(resolveConflict(a, b).primary.conversation)
        assertEquals(resolveConflict(b, a).primary.conversation, merged)
        assertTrue(merged.messages.single { it.id == "shared" }.isDeleted)
    }

    @Test fun mergedConversationRoundTripsAndIsAcceptedByTheOtherSide() {
        val a = revision("a", "甲的编辑", 500, extraMessages = listOf(message("a-only", "甲新增", createdAt = 2)))
        val b = revision("b", "乙的编辑", 1)
        val resolution = resolveConflict(a, b)
        val merged = requireNotNull(resolution.primary.conversation)

        val imported = merged.toSession { error("no attachments") }
        val codec = SessionFileCodec()
        assertEquals(imported, codec.decodeSession(codec.encodeSession(5, 10, imported)).payload)

        val before = SyncManifest(conversations = listOf(b.entry))
        val resolved = SyncManifest(conversations = listOf(resolution.primary.entry))
        assertTrue(differences(before, resolved).all { it.decision == Difference.Accept })
        assertTrue(differences(resolved, resolved).all { it.decision == Difference.Keep })
    }

    @Test fun remindersMergeByNewestUpdateAndTerminalStatusWinsFromEitherDirection() {
        val a = revision(
            "a", "甲的编辑", 5,
            reminders = listOf(
                ReminderRecord("cancelled-newer", "note", triggerAt = 100, createdAt = 1, updatedAt = 9,
                    status = REMINDER_CANCELLED),
                ReminderRecord("terminal-tie", "note", triggerAt = 100, createdAt = 1, updatedAt = 3),
                ReminderRecord("fired-other", "note", triggerAt = 100, createdAt = 1, updatedAt = 1),
                ReminderRecord("a-only", "note", triggerAt = 150, createdAt = 1, updatedAt = 1),
            ),
        )
        val b = revision(
            "b", "乙的编辑", 5,
            reminders = listOf(
                ReminderRecord("cancelled-newer", "note", triggerAt = 100, createdAt = 1, updatedAt = 2),
                ReminderRecord("terminal-tie", "note", triggerAt = 100, createdAt = 1, updatedAt = 3,
                    status = REMINDER_FIRED),
                ReminderRecord("fired-other", "note", triggerAt = 100, createdAt = 1, updatedAt = 2,
                    status = REMINDER_FIRED),
                ReminderRecord("b-only", "note", triggerAt = 200, createdAt = 1, updatedAt = 1),
            ),
        )

        val merged = requireNotNull(resolveConflict(a, b).primary.conversation)

        assertEquals(merged, resolveConflict(b, a).primary.conversation)
        assertEquals(
            listOf("a-only", "b-only", "cancelled-newer", "fired-other", "terminal-tie"),
            merged.reminders.map { it.id },
        )
        assertEquals(REMINDER_CANCELLED, merged.reminders.single { it.id == "cancelled-newer" }.status)
        assertEquals(REMINDER_FIRED, merged.reminders.single { it.id == "terminal-tie" }.status)
        assertEquals(REMINDER_FIRED, merged.reminders.single { it.id == "fired-other" }.status)
    }

    @Test fun concurrentDeleteKeepsTheDeleteAndAnEditableCopyRegardlessOfClock() {
        val edited = revision("writer", "保留编辑", 2)
        val deleted = ConversationRevision(localRevision(null, edited.entry.id, null, "deleter"), null)

        val resolution = resolveConflict(deleted, edited)

        assertEquals(resolution, resolveConflict(edited, deleted))
        assertNull(resolution.primary.conversation)
        assertNull(resolution.primary.entry.digest)
        assertEquals(mapOf("deleter" to 1L, "writer" to 1L), resolution.primary.entry.versions)
        assertEquals("保留编辑", resolution.copy!!.conversation!!.messages.single { !it.isDeleted }.currentContent)
    }

    private fun message(
        id: String,
        text: String,
        createdAt: Long,
        updatedAt: Long = createdAt,
        isDeleted: Boolean = false,
    ) = MessageRecord(id, "note", "user", text, createdAt = createdAt, updatedAt = updatedAt, isDeleted = isDeleted)

    private fun revision(
        device: String,
        text: String,
        time: Long,
        extraMessages: List<MessageRecord> = emptyList(),
        extraReactions: List<ReactionRecord> = emptyList(),
        reminders: List<ReminderRecord> = emptyList(),
    ): ConversationRevision {
        val id = "note"
        val source = listOf("old-1", "old-2").map { MessageRecord(it, id, "user", it, createdAt = 1, isDeleted = true) }
        val visible = MessageRecord("visible", id, "user", "原文", text, contentVersion = 2, createdAt = 1, updatedAt = time)
        val note = SessionFilePayload(
            session = SessionRecord(id, "笔记", 1, time),
            messages = source + visible + extraMessages,
            editHistory = listOf(EditRecord(visible.id, 1, 2, "原文", text, time)),
            quotes = listOf(QuoteRecord(visible.id, source.first().id, "保留引用")),
            reactions = listOf(ReactionRecord(visible.id, "📝")) + extraReactions,
            mergeRelations = listOf(MergeRelationRecord("relation", id, "external", "external-message", visible.id)),
            messageMerges = listOf(MessageMergeRecord(visible.id, id, source.map { it.id }, 1)),
            sessionDocument = SessionDocumentRecord(id, "整理文档", 1, 1),
            reminders = reminders,
        ).toSyncConversation { error("no attachments") }
        return ConversationRevision(localRevision(null, id, note.digest(), device), note)
    }
}