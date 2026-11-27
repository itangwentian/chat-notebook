package com.chatnotebook.app.data

import androidx.room.Room
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MessageInteractionTest {
    private lateinit var database: RecordDatabase
    private lateinit var store: InteractionMemoryStore
    private lateinit var records: RecordRepository
    private lateinit var chat: ChatSessionService
    private lateinit var sessionId: String
    private lateinit var messageId: String

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = InteractionMemoryStore()
        records = RecordRepository(store, database)
        records.ensureWritable()
        chat = ChatSessionService(records)
        sessionId = chat.create("工作")
        messageId = chat.send(sessionId, "明天十点开会")!!
    }

    @After fun tearDown() = database.close()

    @Test fun editingKeepsOriginalAndHistoryAndExpiresOldResult() = runBlocking {
        chat.edit(messageId, "明天十点开会，带电脑")
        chat.edit(messageId, "明天十点开会，带电脑和资料")

        val message = message().single()
        assertEquals("明天十点开会", message.rawContent)
        assertEquals("明天十点开会，带电脑和资料", message.currentContent)
        assertEquals(3, message.contentVersion)
        assertEquals(listOf(1, 2), database.records().editHistory(messageId).map { it.fromVersion })
        assertTrue(database.records().observeAiResults(sessionId).first().all { it.status == AI_RESULT_EXPIRED })
    }

    @Test fun deletingQuotedSourceShowsPlaceholderAndKeepsReply() = runBlocking {
        val reply = chat.send(sessionId, "收到", quotedMessageId = messageId)!!

        chat.deleteMessages(setOf(messageId))

        assertEquals(listOf(reply), database.records().observeMessages(sessionId).first().map { it.id })
        assertEquals("", database.records().observeQuotes(sessionId).first().single().preview)
    }

    @Test fun bubbleFormatReplacesPreviousAndTogglesOff() = runBlocking {
        chat.setBubbleFormat(messageId, BubbleFormat(color = BubbleColor.Red).id)
        assertEquals(setOf(BubbleFormat(color = BubbleColor.Red).id), reactions())

        chat.setBubbleFormat(messageId, BubbleFormat(color = BubbleColor.Green).id)
        assertEquals(setOf(BubbleFormat(color = BubbleColor.Green).id), reactions())

        chat.setBubbleFormat(messageId, BubbleFormat(color = BubbleColor.Green).id)
        assertEquals(emptySet<String>(), reactions())
    }

    @Test fun bubbleFormatStacksStylesAndRejectsDuplicates() = runBlocking {
        chat.setBubbleFormat(messageId, "italic+bold+red")
        val format = BubbleFormat.fromId(reactions().single())!!
        assertEquals(setOf(BubbleTextStyle.Bold, BubbleTextStyle.Italic), format.styles)
        assertEquals(BubbleColor.Red, format.color)
        assertEquals("bold+italic+red", format.id)

        assertEquals(setOf(BubbleTextStyle.Bold), BubbleFormat.fromId("bold")!!.styles)
        assertEquals(
            setOf(BubbleTextStyle.Bold, BubbleTextStyle.Italic, BubbleTextStyle.StrikeThrough),
            BubbleFormat.fromId("bold+italic+strikethrough")!!.styles,
        )
        assertEquals(null, BubbleFormat.fromId("bold+bold"))
        assertEquals(null, BubbleFormat.fromId("red+green"))
        assertEquals(null, BubbleFormat.fromId("large_red"))
        assertEquals(null, BubbleFormat.fromId(""))
    }

    @Test fun bubbleStyleToggleAddsAndRemovesIndependently() {
        val both = BubbleFormat(styles = setOf(BubbleTextStyle.Bold))
            .toggleStyle(BubbleTextStyle.Italic)!!
        assertEquals(setOf(BubbleTextStyle.Bold, BubbleTextStyle.Italic), both.styles)

        val italicOnly = both.toggleStyle(BubbleTextStyle.Bold)!!
        assertEquals(setOf(BubbleTextStyle.Italic), italicOnly.styles)
        assertEquals("italic", italicOnly.id)

        assertEquals(null, italicOnly.toggleStyle(BubbleTextStyle.Italic))
        assertEquals(
            "bold+italic+strikethrough",
            BubbleFormat(styles = setOf(BubbleTextStyle.StrikeThrough, BubbleTextStyle.Bold, BubbleTextStyle.Italic)).id,
        )
    }

    @Test fun batchDeleteRemovesAllSelectedMessages() = runBlocking {
        val second = chat.send(sessionId, "第二条")!!
        val third = chat.send(sessionId, "第三条")!!

        chat.deleteMessages(setOf(messageId, third))

        assertEquals(listOf(second), database.records().observeMessages(sessionId).first().map { it.id })
    }

    @Test fun failedBatchDeleteKeepsEveryMessage() = runBlocking {
        val second = chat.send(sessionId, "第二条")!!

        store.failWrites = true
        assertThrows(IOException::class.java) {
            runBlocking { chat.deleteMessages(setOf(messageId, second)) }
        }

        assertEquals(2, database.records().observeMessages(sessionId).first().size)
    }

    @Test fun mergingSelectedMessagesReplacesBubblesAndPreservesSources() = runBlocking {
        val second = chat.send(sessionId, "第二条")!!
        val reply = chat.send(sessionId, "收到", quotedMessageId = messageId)!!
        chat.edit(messageId, "明天十一点开会")

        val mergedId = chat.mergeMessages(sessionId, setOf(second, messageId))
        val payload = records.current()
        val merged = payload.messages.single { it.id == mergedId }

        assertEquals("明天十一点开会\n第二条", merged.currentContent)
        assertEquals(merged.currentContent, merged.rawContent)
        assertEquals(payload.messages.single { it.id == messageId }.createdAt, merged.createdAt)
        assertEquals(listOf(messageId, second), payload.messageMerges.single().sourceMessageIds)
        assertTrue(payload.messages.filter { it.id in setOf(messageId, second) }.all { it.isDeleted })
        assertEquals("明天十点开会", payload.messages.single { it.id == messageId }.rawContent)
        assertTrue(payload.editHistory.any { it.messageId == messageId })
        assertTrue(payload.aiResults.filter { it.messageId == messageId }.all { it.status == AI_RESULT_EXPIRED })
        assertEquals("", payload.quotes.single { it.messageId == reply }.preview)
        assertEquals(setOf(mergedId, reply), database.records().observeMessages(sessionId).first().map { it.id }.toSet())

        val restoredDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val restored = RecordRepository(store, restoredDatabase).restore()
            assertEquals(payload.messageMerges, restored.messageMerges)
            assertEquals(merged.currentContent, restored.messages.single { it.id == mergedId }.currentContent)
        } finally {
            restoredDatabase.close()
        }
    }

    @Test fun failedMessageMergeDoesNotPartiallyDeleteSources() = runBlocking {
        val second = chat.send(sessionId, "第二条")!!
        store.failWrites = true
        assertThrows(IOException::class.java) {
            runBlocking { chat.mergeMessages(sessionId, setOf(messageId, second)) }
        }
        val payload = records.current()
        assertTrue(payload.messageMerges.isEmpty())
        assertTrue(payload.messages.all { !it.isDeleted })
        assertEquals(2, database.records().observeMessages(sessionId).first().size)
    }

    @Test fun messageMergeRejectsSelectionAcrossSessions() = runBlocking {
        val otherSession = chat.create("其他")
        val other = chat.send(otherSession, "另一条")!!
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { chat.mergeMessages(sessionId, setOf(messageId, other)) }
        }
        assertTrue(records.current().messageMerges.isEmpty())
    }

    @Test fun repeatedMessageMergeDoesNotCreateAnotherResult() = runBlocking {
        val second = chat.send(sessionId, "第二条")!!
        chat.mergeMessages(sessionId, setOf(messageId, second))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { chat.mergeMessages(sessionId, setOf(messageId, second)) }
        }
        assertEquals(1, records.current().messageMerges.size)
        assertEquals(1, database.records().observeMessages(sessionId).first().size)
    }

    @Test fun mergeUsesLatestEditWhenSelectionWasMadeEarlier() = runBlocking {
        val second = chat.send(sessionId, "第二条")!!
        val selectedIds = setOf(messageId, second)
        chat.edit(messageId, "最新修改的内容")

        val mergedId = chat.mergeMessages(sessionId, selectedIds)

        assertEquals("最新修改的内容\n第二条",
            records.current().messages.single { it.id == mergedId }.currentContent)
        assertEquals(2, records.current().messages.single { it.id == messageId }.contentVersion)
    }

    @Test fun mergingAssistantContentLabelsItsSourceRole() = runBlocking {
        records.update { payload ->
            payload.copy(messages = payload.messages + MessageRecord(
                id = "assistant-message", sessionId = sessionId, role = "assistant",
                rawContent = "这是助手回复", createdAt = 9_000_000_000_000,
            ))
        }
        val mergedId = chat.mergeMessages(sessionId, setOf(messageId, "assistant-message"))
        assertEquals("明天十点开会\n助手：这是助手回复",
            records.current().messages.single { it.id == mergedId }.currentContent)
    }

    @Test fun mergingRemovesBlankLinesBetweenBubbles() = runBlocking {
        val second = chat.send(sessionId, "\n第二段\n\n带空行\n")!!

        val mergedId = chat.mergeMessages(sessionId, setOf(messageId, second))

        assertEquals("明天十点开会\n第二段\n带空行",
            records.current().messages.single { it.id == mergedId }.currentContent)
    }

    @Test fun mergingKeepsEachBubbleFormat() = runBlocking {
        chat.setBubbleFormat(messageId, BubbleFormat(color = BubbleColor.Red).id)
        val second = chat.send(sessionId, "第二段")!!
        chat.setBubbleFormat(second, BubbleFormat(color = BubbleColor.Green).id)

        val mergedId = chat.mergeMessages(sessionId, setOf(messageId, second))

        val merged = records.current().messages.single { it.id == mergedId }
        assertEquals(
            listOf(
                MessageFormatSegment("明天十点开会", BubbleFormat(color = BubbleColor.Red).id),
                MessageFormatSegment("第二段", BubbleFormat(color = BubbleColor.Green).id),
            ),
            merged.formatSegments,
        )
    }

    @Test fun attachmentOnlyMessageIsSavedAndRestored() = runBlocking {
        val attachment = imageAttachment()
        val id = chat.send(sessionId, "  ", listOf(attachment))!!

        val saved = records.current().messages.single { it.id == id }
        assertEquals("", saved.currentContent)
        assertEquals(listOf(attachment), saved.attachments)

        val restoredDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val restored = RecordRepository(store, restoredDatabase).restore()
            assertEquals(listOf(attachment), restored.messages.single { it.id == id }.attachments)
        } finally {
            restoredDatabase.close()
        }
    }

    @Test fun attachmentWithTextIsSavedTogether() = runBlocking {
        val attachment = imageAttachment()
        val id = chat.send(sessionId, "看图", listOf(attachment), quotedMessageId = messageId)!!

        val saved = records.current().messages.single { it.id == id }
        assertEquals("看图", saved.currentContent)
        assertEquals(listOf(attachment), saved.attachments)
    }

    @Test fun emptyTextAndAttachmentsCreateNoMessage() = runBlocking {
        assertNull(chat.send(sessionId, "   ", emptyList()))
        assertEquals(1, records.current().messages.size)
    }

    @Test fun failedWriteLeavesNoAttachmentMessage() = runBlocking {
        store.failWrites = true
        assertThrows(IOException::class.java) {
            runBlocking { chat.send(sessionId, "带图", listOf(imageAttachment())) }
        }
        store.failWrites = false
        assertTrue(records.current().messages.none { it.attachments.isNotEmpty() })
    }

    @Test fun editingKeepsAttachments() = runBlocking {
        val id = chat.send(sessionId, "看图", listOf(imageAttachment()))!!

        chat.edit(id, "看图并回复")

        val saved = records.current().messages.single { it.id == id }
        assertEquals("看图并回复", saved.currentContent)
        assertEquals(1, saved.attachments.size)
    }

    @Test fun mergingKeepsAttachments() = runBlocking {
        val withImage = chat.send(sessionId, "带图", listOf(imageAttachment()))!!

        val mergedId = chat.mergeMessages(sessionId, setOf(messageId, withImage))

        val merged = records.current().messages.single { it.id == mergedId }
        assertEquals("明天十点开会\n带图", merged.currentContent)
        assertEquals(listOf("attachments/attachment-1.jpg"), merged.attachments.map { it.relativePath })
    }

    private fun imageAttachment() = MessageAttachment(
        id = "attachment-1",
        kind = "image",
        relativePath = "attachments/attachment-1.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 12,
        createdAt = 1,
    )

    private suspend fun message() = database.records().allMessages(sessionId)

    private suspend fun reactions() =
        database.records().observeReactions(sessionId).first().map { it.emoji }.toSet()
}

private class InteractionMemoryStore : SnapshotStore {
    private val files = linkedMapOf<String, ByteArray>()
    var failWrites = false

    override fun list(): List<String> = files.keys.toList()

    override fun read(name: String): ByteArray = files[name] ?: throw IOException("missing")

    override fun write(name: String, bytes: ByteArray): String {
        if (failWrites) throw IOException("permission lost")
        files[name] = bytes
        return name
    }

    override fun delete(name: String) {
        files.remove(name)
    }
}
