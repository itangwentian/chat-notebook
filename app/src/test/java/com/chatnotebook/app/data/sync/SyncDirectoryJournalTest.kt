package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.SnapshotStore
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncDirectoryJournalTest {
    private val sessionName = "session-s1-00000000000000000001.json"
    private val journalName = "sync-00000000000000000001.json"
    private val store = FakeStore()

    private val state = SyncDirectoryState(
        directoryId = "dir-1",
        localDevice = "device-a",
        generation = 1,
        sessionFiles = mapOf("s1" to sessionName),
        settingsFile = null,
        revisions = mapOf("s1" to DirectoryRevision(false, mapOf("device-a" to 1L), "device-a")),
    )

    @Test
    fun olderJournalVersionIsStillReadable() {
        store.write(sessionName, ByteArray(0))
        store.write(journalName, envelope(1, state))

        val restored = SyncDirectoryJournal(store).restore(store.list())

        assertEquals(state.sessionFiles, restored?.sessionFiles)
        assertEquals(state.generation, restored?.generation)
    }

    @Test
    fun newerJournalVersionIsRejected() {
        store.write(sessionName, ByteArray(0))
        store.write(journalName, envelope(LAN_SYNC_VERSION + 1, state))

        assertThrows(IOException::class.java) {
            SyncDirectoryJournal(store).restore(store.list())
        }
    }

    @Test
    fun commitWritesCurrentVersionAndRoundTrips() {
        store.write(sessionName, ByteArray(0))
        val journal = SyncDirectoryJournal(store)
        journal.commit(state)

        val restored = SyncDirectoryJournal(store).restore(store.list())

        assertEquals(state, restored)
    }

    private fun envelope(version: Int, state: SyncDirectoryState): ByteArray {
        val payload = syncJson.encodeToString(state)
        return syncJson.encodeToString(Envelope(version, payload, sha256(payload.toByteArray())))
            .toByteArray()
    }

    @Serializable
    private data class Envelope(val version: Int, val payload: String, val sha256: String)

    private class FakeStore : SnapshotStore {
        private val files = mutableMapOf<String, ByteArray>()

        override fun list(): List<String> = files.keys.sorted()

        override fun read(name: String): ByteArray = files[name] ?: throw IOException("缺少文件 $name")

        override fun write(name: String, bytes: ByteArray): String {
            files[name] = bytes
            return name
        }

        override fun delete(name: String) {
            files.remove(name)
        }
    }
}