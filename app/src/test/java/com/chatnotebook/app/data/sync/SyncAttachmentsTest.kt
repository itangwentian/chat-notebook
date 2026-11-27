package com.chatnotebook.app.data.sync

import android.net.Uri
import com.chatnotebook.app.data.AttachmentStore
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncAttachmentsTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun existingVerifiedFileIsReusedAndMissingContentIsFetchedOnce() = runBlocking {
        val store = MemoryFiles()
        val sync = SyncAttachments(store, files.root)
        val bytes = byteArrayOf(1, 2, 3)
        val oldBytes = byteArrayOf(4, 5)
        store.write("shared.png", oldBytes)
        val existing = image(oldBytes)
        val missing = image(bytes).copy(id = "../external")
        val fetched = mutableListOf<AttachmentContent>()

        val prepared = sync.prepare(
            listOf(existing, missing, missing.copy(id = "other-reference")),
            mapOf(existing.content to "attachments/shared.png"),
        ) { content ->
            fetched += content
            bytes.inputStream()
        }
        val localPath = prepared.paths.getValue(missing.content)

        assertEquals(listOf(missing.content), fetched)
        assertTrue(localPath.matches(Regex("attachments/sync-[0-9a-f-]+\\.png")))
        assertEquals("attachments/shared.png", prepared.paths.getValue(existing.content))
        assertArrayEquals(bytes, sync.open(localPath).use { it.readBytes() })
        prepared.discard()
        assertEquals(setOf("shared.png"), store.names)
        assertArrayEquals(oldBytes, sync.open("attachments/shared.png").use { it.readBytes() })
    }

    @Test fun failedBatchDiscardsAnyCopiesAndLeavesExistingFiles() = runBlocking {
        val store = MemoryFiles()
        val sync = SyncAttachments(store, files.root)
        val good = byteArrayOf(1, 2)
        val expected = byteArrayOf(7, 8)
        val first = image(good)
        val second = image(expected)
        store.write("shared.png", good)

        for (invalid in listOf(byteArrayOf(7), byteArrayOf(7, 9), byteArrayOf(7, 8, 9))) {
            val failure = runCatching {
                sync.prepare(listOf(first, second), emptyMap()) { content ->
                    if (content == first.content) good.inputStream() else invalid.inputStream()
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(setOf("shared.png"), store.names)
            assertTrue(files.root.list().orEmpty().isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotReadOutsideAttachmentDirectory() {
        SyncAttachments(MemoryFiles(), files.root).open("attachments/../notes.json")
    }

    private fun image(bytes: ByteArray) =
        SyncAttachment("image", "image", "image/png", 0, 1, attachmentContent(bytes.inputStream()))

    private class MemoryFiles : AttachmentStore {
        private val records = mutableMapOf<String, ByteArray>()
        val names: Set<String> get() = records.keys.toSet()
        override fun write(name: String, bytes: ByteArray): String {
            records[name] = bytes
            return name
        }
        override fun read(name: String) = records[name] ?: throw IOException("missing")
        override fun exists(name: String) = name in records
        override fun delete(name: String) { records.remove(name) }
        override fun uri(name: String): Uri? = null
    }
}
