package com.chatnotebook.app.data

import android.net.Uri
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AttachmentStoreTest {
    @Test fun memoryStoreWritesReadsAndDeletes() {
        val store = MemoryAttachmentStore()

        val stored = store.write("a.jpg", byteArrayOf(1, 2, 3))
        assertEquals("a.jpg", stored)
        assertTrue(store.exists("a.jpg"))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read("a.jpg"))

        store.delete("a.jpg")
        assertFalse(store.exists("a.jpg"))
    }

    @Test fun relativePathsLiveUnderAttachmentsDirectory() {
        assertEquals("attachments/a.jpg", attachmentRelativePath("a.jpg"))
        assertEquals("a.jpg", attachmentFileName("attachments/a.jpg"))
    }

    @Test fun safStoreFailsWithoutDirectoryPermission() {
        val context = RuntimeEnvironment.getApplication()
        val store = SafAttachmentStore(context, Uri.parse("content://missing/tree"))

        assertThrows(IOException::class.java) {
            store.write("a.jpg", byteArrayOf(1))
        }
        assertFalse(store.exists("a.jpg"))
        assertEquals(null, store.uri("a.jpg"))
    }
}

private class MemoryAttachmentStore : AttachmentStore {
    private val files = linkedMapOf<String, ByteArray>()

    override fun write(name: String, bytes: ByteArray): String {
        files[name] = bytes
        return name
    }

    override fun read(name: String): ByteArray = files[name] ?: throw IOException("missing")

    override fun exists(name: String): Boolean = name in files

    override fun delete(name: String) {
        files.remove(name)
    }

    override fun uri(name: String): Uri? = null
}
