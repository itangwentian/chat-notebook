package com.chatnotebook.app.data

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ChatSessionServiceTest {
    @Test fun restartRestoresTwoIndependentSessions() = runBlocking {
        val store = MemorySnapshotStore()
        val database = newDatabase()
        val repository = RecordRepository(store, database)
        repository.ensureWritable()
        val service = ChatSessionService(repository)
        val work = service.create("工作")
        val life = service.create("生活")
        service.send(work, "记下会议")
        service.send(life, "买牛奶")
        database.close()

        val restored = RecordRepository(store, newDatabase()).restore()

        assertEquals(listOf("工作", "生活"), restored.sessions.map { it.title })
        assertEquals(listOf("记下会议"), restored.messages.filter { it.sessionId == work }.map { it.rawContent })
        assertEquals(listOf("买牛奶"), restored.messages.filter { it.sessionId == life }.map { it.rawContent })
    }

    @Test fun forwardingCopiesMessagesInOrderAndKeepsSourceUntouched() = runBlocking {
        val store = MemorySnapshotStore()
        val database = newDatabase()
        val repository = RecordRepository(store, database)
        repository.ensureWritable()
        var counter = 0
        val service = ChatSessionService(repository, now = { 100L }, newId = { "id${++counter}" })
        val source = service.create("源对话")
        val target = service.create("目标对话")
        service.send(source, "第一条")
        service.send(source, "第二条")
        val sourceMessages = repository.current().messages.filter { it.sessionId == source }

        val count = service.forwardMessages(source, sourceMessages.map { it.id }.toSet(), target)

        val current = repository.current()
        assertEquals(2, count)
        assertEquals(
            listOf("第一条", "第二条"),
            current.messages.filter { it.sessionId == target }.sortedBy { it.createdAt }.map { it.rawContent },
        )
        assertEquals(
            sourceMessages.map { it.id },
            current.messages.filter { it.sessionId == source }.map { it.id },
        )
        assertEquals(102L, current.sessions.first { it.id == target }.updatedAt)
        database.close()
    }

    @Test fun forwardingKeepsImageAndAudioAttachments() = runBlocking {
        val store = MemorySnapshotStore()
        val database = newDatabase()
        val repository = RecordRepository(store, database)
        repository.ensureWritable()
        var counter = 0
        val service = ChatSessionService(repository, now = { 100L }, newId = { "id${++counter}" })
        val source = service.create("源对话")
        val target = service.create("目标对话")
        val attachments = listOf(
            MessageAttachment("a1", "image", "attachments/a1.jpg", "image/jpeg", 10, 0, 1),
            MessageAttachment("a2", "audio", "attachments/a2.m4a", "audio/mp4", 20, 5_000, 1),
        )
        service.send(source, "带附件", attachments)
        val sourceMessage = repository.current().messages.single { it.sessionId == source }

        service.forwardMessages(source, setOf(sourceMessage.id), target)

        val forwarded = repository.current().messages.single { it.sessionId == target }
        assertEquals("带附件", forwarded.currentContent)
        assertEquals(attachments, forwarded.attachments)
        database.close()
    }

    @Test fun deletionCleansOnlyUnusedAttachmentsAfterRemovingSessionFile() = runBlocking {
        val store = MemorySnapshotStore()
        withDatabase { database ->
            val records = RecordRepository(store, database)
            records.ensureWritable()
            val service = ChatSessionService(records)
            val source = service.create("源对话")
            val target = service.create("目标对话")
            val shared = service.send(source, "", listOf(image("shared")))!!
            val unique = service.send(source, "", listOf(image("unique")))!!
            service.forwardMessages(source, setOf(shared), target)
            service.deleteMessages(setOf(unique))
            val attachmentFiles = mutableSetOf("shared.jpg", "unique.jpg")

            service.delete(source) { name ->
                assertTrue(store.list().none { it.startsWith("session-$source-") })
                assertTrue(attachmentFiles.remove(name))
            }

            assertEquals(setOf("shared.jpg"), attachmentFiles)
            val restored = RecordRepository(store, database).restore()
            assertEquals(listOf(target), restored.sessions.map { it.id })
            assertEquals(listOf(image("shared")), restored.messages.single().attachments)
        }
    }

    @Test fun failedSessionPersistenceLeavesAttachmentFilesUntouched() = runBlocking {
        val store = MemorySnapshotStore()
        withDatabase { database ->
            val records = RecordRepository(store, database)
            records.ensureWritable()
            val service = ChatSessionService(records)
            val source = service.create("源对话")
            service.send(source, "", listOf(image("unique")))
            store.failSessionDeletion = true
            val attachmentFiles = mutableSetOf("unique.jpg")

            val error = runCatching {
                service.delete(source) { name -> attachmentFiles.remove(name) }
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(setOf("unique.jpg"), attachmentFiles)
            val restored = RecordRepository(store, database).restore()
            assertEquals(listOf(source), restored.sessions.map { it.id })
            assertEquals(listOf(image("unique")), restored.messages.single().attachments)
        }
    }

    @Test fun attachmentDeletionFailureIsReportedAfterTryingTheRemainingFiles() = runBlocking {
        val store = MemorySnapshotStore()
        withDatabase { database ->
            val records = RecordRepository(store, database)
            records.ensureWritable()
            val service = ChatSessionService(records)
            val source = service.create("源对话")
            service.send(source, "", listOf(image("locked"), image("removable")))
            val attempted = mutableListOf<String>()
            val attachmentFiles = mutableSetOf("locked.jpg", "removable.jpg")
            val refused = IOException("Attachment file is read-only")

            val error = runCatching {
                service.delete(source) { name ->
                    attempted += name
                    if (name == "locked.jpg") throw refused
                    attachmentFiles.remove(name)
                }
            }.exceptionOrNull() as SessionAttachmentCleanupException

            assertEquals(mapOf("attachments/locked.jpg" to refused), error.failures)
            assertEquals(listOf("locked.jpg", "removable.jpg"), attempted)
            assertEquals(setOf("locked.jpg"), attachmentFiles)
            assertTrue(RecordRepository(store, database).restore().sessions.isEmpty())
        }
    }

    @Test fun seedingCreatesImportantAndDraftWithDraftOpened() = runBlocking {
        val repository = RecordRepository(MemorySnapshotStore(), newDatabase())
        repository.ensureWritable()
        var counter = 0
        val service = ChatSessionService(repository, now = { 100L }, newId = { "id${++counter}" })

        val draftId = service.seedDefaultConversations()

        val sessions = repository.current().sessions.associateBy { it.title }
        assertEquals(setOf("极其重要", "草稿"), sessions.keys)
        assertEquals(draftId, sessions.getValue("草稿").id)
        assertTrue(sessions.getValue("草稿").lastOpenedAt > sessions.getValue("极其重要").lastOpenedAt)
    }

    @Test fun seedingSkipsWhenConversationExists() = runBlocking {
        val repository = RecordRepository(MemorySnapshotStore(), newDatabase())
        repository.ensureWritable()
        val service = ChatSessionService(repository)
        service.create("已有对话")

        assertEquals(null, service.seedDefaultConversations())
        assertEquals(listOf("已有对话"), repository.current().sessions.map { it.title })
    }

    private fun image(name: String) =
        MessageAttachment(name, "image", "attachments/$name.jpg", "image/jpeg", 1, createdAt = 1)

    private inline fun withDatabase(block: (RecordDatabase) -> Unit) {
        val database = newDatabase()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun newDatabase(): RecordDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries()
            .build()
}

private class MemorySnapshotStore : SnapshotStore {
    private val files = linkedMapOf<String, ByteArray>()
    var failSessionDeletion = false

    override fun list(): List<String> = files.keys.toList()

    override fun read(name: String): ByteArray = files[name] ?: throw IOException("missing")

    override fun write(name: String, bytes: ByteArray): String {
        files[name] = bytes
        return name
    }

    override fun delete(name: String) {
        if (failSessionDeletion && name.startsWith("session-")) throw IOException("Session file is read-only")
        files.remove(name)
    }
}
