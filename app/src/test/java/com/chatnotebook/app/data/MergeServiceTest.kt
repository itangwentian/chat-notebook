package com.chatnotebook.app.data

import androidx.room.Room
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class MergeServiceTest {
    private lateinit var database: RecordDatabase
    private lateinit var store: MergeMemoryStore
    private lateinit var records: RecordRepository
    private lateinit var chat: ChatSessionService
    private lateinit var work: String
    private lateinit var life: String

    @Before fun setup() {
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            store = MergeMemoryStore()
            records = RecordRepository(store, database)
            records.ensureWritable()
            chat = ChatSessionService(records)
            work = chat.create("工作")
            life = chat.create("生活")
            chat.send(work, "工作一")
            chat.send(life, "生活一")
        }
    }

    @After fun tearDown() = database.close()

    @Test fun mergeCopiesMessagesInSourceOrderAndKeepsSources() = runBlocking {
        val workMessage = database.records().observeMessages(work).first().single()
        chat.edit(workMessage.id, "工作一改")

        val target = MergeService(records).merge(listOf(work, life), "合并")

        assertEquals(
            listOf("工作一改", "生活一"),
            database.records().observeMessages(target).first().map { it.currentContent },
        )
        assertEquals(1, database.records().editHistory(database.records().observeMessages(target).first().first().id).size)
        assertEquals(1, database.records().observeMessages(work).first().size)
        assertEquals(1, database.records().observeMessages(life).first().size)
    }

    @Test fun mergeRecordsRootSourceForEveryCopiedMessage() = runBlocking {
        val target = MergeService(records).merge(listOf(work, life), "合并")

        val roots = database.records().observeMergeRoots(target).first()

        assertEquals(2, roots.size)
        assertEquals(setOf("工作", "生活"), roots.map { it.sourceTitle }.toSet())
        assertTrue(roots.all { it.rootSourceSessionId == work || it.rootSourceSessionId == life })
    }

    @Test fun mergingAMergedSessionKeepsOriginalRoot() = runBlocking {
        val firstMerge = MergeService(records).merge(listOf(work, life), "第一次合并")
        val third = chat.create("第三个")
        chat.send(third, "第三条")

        val secondMerge = MergeService(records).merge(listOf(firstMerge, third), "第二次合并")

        val roots = database.records().observeMergeRoots(secondMerge).first()
        assertEquals(3, roots.size)
        assertTrue(roots.map { it.rootSourceSessionId }.toSet().containsAll(setOf(work, life, third)))
    }

    @Test fun mergeDoesNotCopyDerivedResultsOrReactions() = runBlocking {
        val messageId = database.records().observeMessages(work).first().single().id
        chat.setBubbleFormat(messageId, BubbleFormat(color = BubbleColor.Red).id)

        val target = MergeService(records).merge(listOf(work, life), "合并")

        assertTrue(database.records().observeAiResults(target).first().isEmpty())
        assertTrue(database.records().observeReactions(target).first().isEmpty())
    }

    @Test fun mergeRequiresTwoDistinctSessions() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { MergeService(records).merge(listOf(work), "一个") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { MergeService(records).merge(listOf(work, work), "重复") }
        }
    }

    @Test fun failedWriteLeavesNoPartialMerge() {
        val before = runBlocking { database.records().observeMessages(work).first().size }
        store.failWrites = true

        assertThrows(IOException::class.java) {
            runBlocking { MergeService(records).merge(listOf(work, life), "合并") }
        }

        runBlocking {
            assertEquals(2, database.records().observeSessions().first().size)
            assertEquals(before, database.records().observeMessages(work).first().size)
        }
    }
}

private class MergeMemoryStore : SnapshotStore {
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
