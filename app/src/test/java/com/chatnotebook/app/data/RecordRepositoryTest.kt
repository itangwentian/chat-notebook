package com.chatnotebook.app.data

import android.net.Uri
import androidx.room.Room
import com.chatnotebook.app.data.sync.Difference
import com.chatnotebook.app.data.sync.DirectoryRevision
import com.chatnotebook.app.data.sync.SyncManifest
import com.chatnotebook.app.data.sync.differences
import com.chatnotebook.app.data.sync.sha256
import com.chatnotebook.app.data.sync.LanFrames
import com.chatnotebook.app.data.sync.LanSyncProtocol
import com.chatnotebook.app.data.sync.SyncAttachments
import com.chatnotebook.app.data.sync.SyncLibrary
import com.chatnotebook.app.data.sync.SyncTransfer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RecordRepositoryTest {
    private lateinit var db: RecordDatabase
    private lateinit var store: MemoryStore
    private lateinit var repository: RecordRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        store = MemoryStore()
        repository = RecordRepository(store, db)
    }

    @After fun close() = db.close()

    @Test fun twoSessionsStaySeparateAndSameTimeMessagesAreStable(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update {
            it.copy(
                sessions = listOf(session("a"), session("b")),
                messages = listOf(message("z", "a"), message("a", "a"), message("b", "b")),
            )
        }
        assertEquals(listOf("a", "z"), db.records().allMessages("a").map { it.id })
        assertEquals(listOf("b"), db.records().allMessages("b").map { it.id })
        assertEquals(2, repository.observeSessions().first().size)
    }

    @Test fun restartRebuildsCacheAndFallsBackFromDamagedLatestFile(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("one", "a"))) }
        repository.update { it.copy(sessions = listOf(session("a"), session("b"))) }
        store.files[store.files.keys.last()] = "broken".toByteArray()
        db.close()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        val restored = RecordRepository(store, db).restore()
        assertEquals(listOf("a"), restored.sessions.map { it.id })
        assertEquals(listOf("one"), db.records().allMessages("a").map { it.id })
    }

    @Test fun failedFileWriteDoesNotChangeCacheOrOriginalMessage(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("one", "a"))) }
        store.failWrites = true
        try {
            repository.update { it.copy(messages = it.messages + message("two", "a")) }
            throw AssertionError("Expected write failure")
        } catch (_: IOException) { }
        assertEquals(listOf("one"), db.records().allMessages("a").map { it.id })
        store.failWrites = false
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.update { it.copy(messages = listOf(message("one", "a").copy(rawContent = "changed"))) } }
        }
    }

    @Test fun remindersStayIndexedFromFilesAndFailedWritesLeaveTheIndexUntouched(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("one", "a"))) }
        val reminder = ReminderRecord("r1", "a", "one", triggerAt = 9_999, createdAt = 1, updatedAt = 1)

        repository.update { it.copy(reminders = listOf(reminder)) }
        assertEquals(listOf("r1"), repository.observeReminders("a").first().map { it.id })
        assertEquals(listOf("r1"), repository.observeScheduledReminders().first().map { it.id })

        val cancelled = reminder.copy(status = REMINDER_CANCELLED, updatedAt = 2)
        repository.update { it.copy(reminders = listOf(cancelled)) }
        assertEquals(REMINDER_CANCELLED, repository.observeReminders("a").first().single().status)

        store.failWrites = true
        try {
            repository.update { it.copy(reminders = listOf(cancelled.copy(status = REMINDER_FIRED))) }
            throw AssertionError("Expected write failure")
        } catch (_: IOException) { }
        store.failWrites = false
        assertEquals(REMINDER_CANCELLED, repository.observeReminders("a").first().single().status)

        db.close()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        val restored = RecordRepository(store, db).restore()
        assertEquals(listOf("r1"), restored.reminders.map { it.id })
        assertEquals(REMINDER_CANCELLED, restored.reminders.single().status)
        assertEquals(REMINDER_CANCELLED, db.records().observeReminders("a").first().single().status)
    }

    @Test fun noValidSnapshotDoesNotEraseExistingFiles(): Unit = runBlocking {
        store.files["records-00000000000000000001-bad.json"] = "broken".toByteArray()
        try { repository.restore(); throw AssertionError("Expected corrupt-store error") }
        catch (_: IOException) { }
        assertTrue(store.files.isNotEmpty())
    }

    @Test fun foreignKeyRejectsOrphanMessage(): Unit = runBlocking {
        repository.initializeEmpty()
        try {
            repository.update { it.copy(messages = listOf(message("orphan", "missing"))) }
            throw AssertionError("Expected invalid relation")
        } catch (_: IllegalArgumentException) { }
        assertTrue(store.files.size == 1)
        assertTrue(db.records().allMessages("missing").isEmpty())
        assertTrue(runCatching { db.records().insertMessages(listOf(message("orphan", "missing").toEntity())) }.isFailure)
    }

    @Test fun eachSessionLivesInItsOwnFile(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update {
            it.copy(
                sessions = listOf(session("a"), session("b")),
                messages = listOf(message("one", "a"), message("two", "b")),
            )
        }
        val sessionFiles = store.files.keys.filter { it.startsWith("session-") }
        assertEquals(2, sessionFiles.size)
        assertTrue(sessionFiles.any { it.startsWith("session-a-") })
        assertTrue(sessionFiles.any { it.startsWith("session-b-") })
        assertEquals(1, store.files.keys.count { it.startsWith("settings-") })
        assertTrue(store.files.keys.none { it.startsWith("records-") })
    }

    @Test fun changingOneSessionKeepsTheOtherFileUntouched(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update {
            it.copy(
                sessions = listOf(session("a"), session("b")),
                messages = listOf(message("one", "a"), message("two", "b")),
            )
        }
        val otherFile = store.files.keys.single { it.startsWith("session-b-") }
        val before = store.files.getValue(otherFile).copyOf()
        repository.update {
            it.copy(messages = it.messages.map { message ->
                if (message.id == "one") message.copy(currentContent = "changed") else message
            })
        }
        assertTrue(store.files.getValue(otherFile).contentEquals(before))
    }

    @Test fun deletedSessionFileIsRemovedAndNotRestored(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update {
            it.copy(
                sessions = listOf(session("a"), session("b")),
                messages = listOf(message("one", "a"), message("two", "b")),
            )
        }
        repository.update {
            it.copy(sessions = it.sessions.map { session ->
                if (session.id == "b") session.copy(isDeleted = true) else session
            })
        }
        assertTrue(store.files.keys.none { it.startsWith("session-b-") })
        db.close()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        val restored = RecordRepository(store, db).restore()
        assertEquals(listOf("a"), restored.sessions.map { it.id })
        assertEquals(listOf("one"), restored.messages.map { it.id })
    }

    @Test fun legacySnapshotIsSplitIntoSeparateFiles(): Unit = runBlocking {
        val legacy = RecordPayload(
            sessions = listOf(session("a"), session("b")),
            messages = listOf(message("one", "a"), message("two", "b")),
            llmConfigs = listOf(
                LlmConfigRecord("c", "本地配置", "local", baseUrl = "https://example.com", model = "m", createdAt = 1, updatedAt = 1),
            ),
        )
        store.files["records-00000000000000000005-abc.json"] = SnapshotCodec().encode(5, 10, legacy)

        val restored = repository.restore()

        assertEquals(setOf("a", "b"), restored.sessions.map { it.id }.toSet())
        assertEquals(1, restored.llmConfigs.size)
        assertTrue(store.files.keys.none { it.startsWith("records-") })
        assertEquals(2, store.files.keys.count { it.startsWith("session-") })
        assertEquals(1, store.files.keys.count { it.startsWith("settings-") })
    }

    @Test fun failedMigrationKeepsLegacySnapshot(): Unit = runBlocking {
        store.files["records-00000000000000000005-abc.json"] =
            SnapshotCodec().encode(5, 10, RecordPayload(sessions = listOf(session("a"))))
        store.failWrites = true

        try {
            repository.restore()
            throw AssertionError("Expected migration failure")
        } catch (_: IOException) { }

        assertEquals(listOf("records-00000000000000000005-abc.json"), store.files.keys.toList())
    }

    @Test fun damagedSessionFileDoesNotEraseExistingFiles(): Unit = runBlocking {
        store.files["session-a-00000000000000000001.json"] = "broken".toByteArray()

        try {
            repository.restore()
            throw AssertionError("Expected corrupt session error")
        } catch (_: IOException) { }

        assertEquals(listOf("session-a-00000000000000000001.json"), store.files.keys.toList())
    }

    @Test fun syncTrackingUpgradesExistingDirectoryAndPersistsExplicitDeletes(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("one", "a"))) }
        val first = repository.startSyncTracking("phone-a")
        val service = ChatSessionService(repository)
        service.markOpened("a")
        assertEquals(first.revisions, repository.startSyncTracking("phone-a").revisions)
        service.deleteMessages(setOf("one"))
        val softDeleted = repository.startSyncTracking("phone-a").revisions.getValue("a")
        assertEquals(mapOf(first.localDevice to 2L), softDeleted.versions)
        service.delete("a") { error("no attachments") }

        val restarted = RecordRepository(store, db)
        assertTrue(restarted.restore().sessions.isEmpty())
        val deleted = restarted.startSyncTracking("phone-a")
        val entry = deleted.revisions.getValue("a").entry("a", null)
        val stale = first.revisions.getValue("a").entry("a", sha256("old-note".toByteArray()))
        assertEquals(first.directoryId, deleted.directoryId)
        assertEquals(mapOf(first.localDevice to 3L), entry.versions)
        assertEquals(Difference.Keep, differences(SyncManifest(conversations = listOf(entry)),
            SyncManifest(conversations = listOf(stale))).single().decision)
    }

    @Test fun syncTrackingDetectsDirectoryChangeAndLostPermission(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update { it.copy(sessions = listOf(session("shared").copy(title = "第一目录"))) }
        val original = repository.startSyncTracking("phone-a")
        val second = RecordRepository(MemoryStore(), db)
        second.initializeEmpty()
        second.update { it.copy(sessions = listOf(session("shared").copy(title = "另一目录中的备份"))) }
        val newDirectory = second.startSyncTracking("phone-a")
        assertNotEquals(original.directoryId, newDirectory.directoryId)
        assertNotEquals(original.localDevice, newDirectory.localDevice)
        val first = original.revisions.getValue("shared").entry("shared", sha256("1".toByteArray()))
        val secondVersion = newDirectory.revisions.getValue("shared").entry("shared", sha256("2".toByteArray()))
        assertEquals(Difference.ResolveConflict, differences(
            SyncManifest(conversations = listOf(first)),
            SyncManifest(conversations = listOf(secondVersion)),
        ).single().decision)

        store.failLists = true
        assertTrue(runCatching { repository.startSyncTracking("phone-a") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { repository.update { it.copy(sessions = listOf(session("a"))) } }
            .exceptionOrNull() is IOException)
        store.failLists = false
        assertEquals(original, RecordRepository(store, db).run { restore(); startSyncTracking("phone-a") })
    }

    @Test fun importedConflictGroupPublishesTogetherAndAcknowledgementRetryDoesNotRepublishTitle(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("first", "a"))) }
        val original = repository.startSyncTracking("phone-a")
        val version = DirectoryRevision(false, mapOf(original.localDevice to 1L, "phone-b" to 1L), "phone-b")
        val parts = mapOf(
            "a" to SessionFilePayload(session("a").copy(title = "远端主版"), listOf(message("first", "a"))),
            "copy" to SessionFilePayload(session("copy").copy(title = "同步冲突"), listOf(message("copy-1", "copy"))),
        )
        val expected = mapOf("a" to original.revisions["a"], "copy" to null)
        val versions = parts.keys.associateWith { version }
        store.failJournal = true
        assertTrue(runCatching { repository.applySyncRevisions(original.directoryId, expected, versions, parts) }
            .exceptionOrNull() is IOException)
        assertEquals(listOf("a"), repository.observeSessions().first().map { it.id })

        store.failJournal = false
        assertTrue(repository.applySyncRevisions(original.directoryId, expected, versions, parts))
        assertEquals(setOf("远端主版", "同步冲突"), repository.observeSessions().first().map { it.title }.toSet())
        val saved = repository.syncSnapshot().first
        assertTrue(!repository.applySyncRevisions(original.directoryId, expected, versions, parts))
        assertEquals(saved, RecordRepository(store, db).run { restore(); syncSnapshot().first })
    }

    @Test fun recoveryIgnoresStagedFilesAndImportRejectsLocalEditDuringDownload(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("first", "a"))) }
        val before = repository.startSyncTracking("phone-a")
        val incoming = SessionFilePayload(session("a").copy(title = "对端标题"), listOf(message("first", "a")))
        val uncommittedGeneration = before.generation + 1
        store.files[sessionFileName("a", uncommittedGeneration)] = SessionFileCodec().encodeSession(
            uncommittedGeneration, 100, incoming,
        )
        store.files["sync-${uncommittedGeneration.toString().padStart(20, '0')}.json"] = "incomplete".toByteArray()
        val restarted = RecordRepository(store, db)
        assertEquals("a", restarted.restore().sessions.single().title)
        restarted.update { payload -> payload.copy(sessions = listOf(session("a").copy(title = "本机新编辑"))) }
        val versions = mapOf("a" to DirectoryRevision(false, mapOf(before.localDevice to 1L, "phone-b" to 1L), "phone-b"))
        val failure = runCatching {
            restarted.applySyncRevisions(before.directoryId, before.revisions, versions, mapOf("a" to incoming))
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("本机新编辑", restarted.observeSessions().first().single().title)
    }

    @Test fun twoLocalPeersExchangeOnlyDifferencesAndReuseAlreadyVerifiedAttachments(): Unit = runBlocking {
        val secondDb = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val second = RecordRepository(MemoryStore(), secondDb)
            repository.initializeEmpty()
            second.initializeEmpty()
            val aFiles = SyncMemoryAttachments()
            val bFiles = SyncMemoryAttachments()
            val content = byteArrayOf(1, 2, 3, 4)
            aFiles.write("shared.png", content)
            val image = MessageAttachment("image", "image", "attachments/shared.png", "image/png", 4, createdAt = 100)
            repository.update {
                it.copy(sessions = listOf(session("a")), messages = listOf(message("one", "a").copy(attachments = listOf(image))))
            }
            second.update { it.copy(sessions = listOf(session("b")), messages = listOf(message("two", "b"))) }
            repository.startSyncTracking("phone-a")
            second.startSyncTracking("phone-b")
            val cache = RuntimeEnvironment.getApplication().cacheDir
            val a = LanSyncProtocol(SyncLibrary(repository, SyncAttachments(aFiles, cache)))
            val b = LanSyncProtocol(SyncLibrary(second, SyncAttachments(bFiles, cache)))
            val address = InetAddress.getLoopbackAddress()

            suspend fun round(): Pair<SyncTransfer, SyncTransfer> = ServerSocket(0, 1, address).use { server ->
                val accept = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        b.exchange(LanFrames(socket), initiator = false)
                    }
                }
                val result = Socket(address, server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    a.exchange(LanFrames(socket), initiator = true)
                }
                result to accept.await()
            }
            assertEquals(SyncTransfer(1, 0) to SyncTransfer(1, 4), round())
            assertEquals(setOf("a", "b"), repository.current().sessions.map { it.id }.toSet())
            assertEquals(setOf("a", "b"), second.current().sessions.map { it.id }.toSet())
            assertEquals(SyncTransfer() to SyncTransfer(), round())
            val damagedName = bFiles.files.keys.single()
            bFiles.files[damagedName] = byteArrayOf(9, 9, 9, 9)
            assertEquals(SyncTransfer() to SyncTransfer(0, 4), round())
            assertEquals(content.toList(), bFiles.files.values.single().toList())
            assertTrue(damagedName !in bFiles.files)

            val service = ChatSessionService(repository)
            service.edit("one", "本机新编辑")
            assertEquals("本机新编辑", repository.current().messages.single { it.id == "one" }.currentContent)
            assertEquals(SyncTransfer() to SyncTransfer(1, 0), round())
            assertEquals("本机新编辑", second.current().messages.single { it.id == "one" }.currentContent)
            assertEquals(1, bFiles.files.size)
            service.delete("a") { aFiles.delete(it) }
            assertEquals(SyncTransfer() to SyncTransfer(), round())
            assertEquals(listOf("b"), second.current().sessions.map { it.id })
        } finally {
            secondDb.close()
        }
    }

    @Test fun renamedSessionTitleReachesPeerWithoutMessageChanges(): Unit = runBlocking {
        val secondDb = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), RecordDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val second = RecordRepository(MemoryStore(), secondDb)
            repository.initializeEmpty()
            second.initializeEmpty()
            val aFiles = SyncMemoryAttachments()
            val bFiles = SyncMemoryAttachments()
            repository.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("one", "a"))) }
            second.update { it.copy(sessions = listOf(session("a")), messages = listOf(message("one", "a"))) }
            repository.startSyncTracking("phone-a")
            second.startSyncTracking("phone-b")
            val cache = RuntimeEnvironment.getApplication().cacheDir
            val a = LanSyncProtocol(SyncLibrary(repository, SyncAttachments(aFiles, cache)))
            val b = LanSyncProtocol(SyncLibrary(second, SyncAttachments(bFiles, cache)))
            val address = InetAddress.getLoopbackAddress()

            suspend fun round(): Pair<SyncTransfer, SyncTransfer> = ServerSocket(0, 1, address).use { server ->
                val accept = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        b.exchange(LanFrames(socket), initiator = false)
                    }
                }
                val result = Socket(address, server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    a.exchange(LanFrames(socket), initiator = true)
                }
                result to accept.await()
            }
            round()
            ChatSessionService(repository).rename("a", "新名称")
            assertEquals(SyncTransfer() to SyncTransfer(1, 0), round())
            assertEquals("新名称", second.current().sessions.single { it.id == "a" }.title)
            ChatSessionService(second).rename("a", "对端改名")
            assertEquals(SyncTransfer(1, 0) to SyncTransfer(), round())
            assertEquals("对端改名", repository.current().sessions.single { it.id == "a" }.title)
        } finally {
            secondDb.close()
        }
    }

    @Test fun reminderPreviewTextTrimsAndFallsBackToSessionTitle() {
        assertEquals("工作", reminderPreviewText(null, "工作"))
        assertEquals("工作", reminderPreviewText("   ", "工作"))
        assertEquals("a b", reminderPreviewText("a\n b", "工作"))
        assertEquals(REMINDER_PREVIEW_LIMIT, reminderPreviewText("x".repeat(100), "工作").length)
    }

    @Test fun scheduledReminderPreviewSkipsCancelledAndDeletedTargets(): Unit = runBlocking {
        repository.initializeEmpty()
        repository.update {
            it.copy(
                sessions = listOf(session("a"), session("b")),
                messages = listOf(message("m1", "a")),
                reminders = listOf(
                    ReminderRecord("r1", "a", "m1", triggerAt = 200_000, createdAt = 1),
                    ReminderRecord(
                        "r2", "a", "m1", triggerAt = 300_000, createdAt = 1,
                        status = REMINDER_CANCELLED,
                    ),
                    ReminderRecord("r3", "b", null, triggerAt = 400_000, createdAt = 1),
                ),
            )
        }

        val before = db.records().observeScheduledReminderPreviews().first()
        assertEquals(listOf("r1", "r3"), before.map { it.id })
        assertEquals("m1", before.first().messageId)
        assertEquals("m1", before.first().content)
        assertEquals("b", before.last().sessionTitle)
        assertNull(before.last().content)

        // 绑定消息删除后提醒退化为只打开会话，投影仍返回该提醒。
        repository.update {
            it.copy(
                messages = emptyList(),
                reminders = it.reminders.map { reminder ->
                    if (reminder.messageId == "m1") reminder.copy(messageId = null) else reminder
                },
            )
        }

        val after = db.records().observeScheduledReminderPreviews().first()
        val r1 = after.first { it.id == "r1" }
        assertNull(r1.messageId)
        assertNull(r1.content)
        assertEquals("a", r1.sessionTitle)
    }

    private fun session(id: String) = SessionRecord(id, id, 100, 100)
    private fun message(id: String, session: String) = MessageRecord(id, session, "user", id, createdAt = 100)
}

private class SyncMemoryAttachments : AttachmentStore {
    val files = mutableMapOf<String, ByteArray>()
    override fun write(name: String, bytes: ByteArray): String {
        files[name] = bytes
        return name
    }
    override fun read(name: String): ByteArray = files[name] ?: throw IOException("missing")
    override fun exists(name: String): Boolean = name in files
    override fun delete(name: String) { files.remove(name) }
    override fun uri(name: String): Uri? = null
}

private class MemoryStore : SnapshotStore {
    val files = linkedMapOf<String, ByteArray>()
    var failWrites = false
    var failLists = false
    var failJournal = false
    override fun list(): List<String> {
        if (failLists) throw IOException("permission lost")
        return files.keys.toList()
    }
    override fun read(name: String) = files[name] ?: throw IOException("missing")
    override fun write(name: String, bytes: ByteArray): String {
        if (failWrites || (failJournal && name.startsWith("sync-"))) throw IOException("permission lost")
        files[name] = bytes
        return name
    }
    override fun delete(name: String) {
        files.remove(name)
    }
}

