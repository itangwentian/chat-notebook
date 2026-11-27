package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.ATTACHMENT_DIRECTORY
import com.chatnotebook.app.data.AttachmentStore
import com.chatnotebook.app.data.attachmentRelativePath
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SyncAttachments(private val store: AttachmentStore, private val cache: File) {
    fun open(path: String): InputStream = store.open(fileName(path))

    fun identify(path: String): AttachmentContent = open(path).use(::attachmentContent)
    fun delete(path: String) = store.delete(fileName(path))

    suspend fun prepare(
        required: Collection<SyncAttachment>,
        available: Map<AttachmentContent, String>,
        fetch: suspend (AttachmentContent) -> InputStream,
    ): PreparedSyncAttachments {
        val created = mutableListOf<String>()
        val paths = mutableMapOf<AttachmentContent, String>()
        try {
            for (item in required.distinctBy { it.content }) {
                currentCoroutineContext().ensureActive()
                val known = available[item.content]
                val correct = known != null && verify(known, item.content)
                paths[item.content] = if (correct) {
                    checkNotNull(known)
                } else {
                    receive(item, created, fetch)
                }
            }
            return PreparedSyncAttachments(paths.toMap(), created.toList(), store)
        } catch (error: Exception) {
            created.forEach { name -> runCatching { store.delete(name) } }
            throw error
        }
    }

    private suspend fun receive(
        item: SyncAttachment,
        created: MutableList<String>,
        fetch: suspend (AttachmentContent) -> InputStream,
    ): String {
        if (!cache.isDirectory && !cache.mkdirs()) throw IOException("无法创建附件缓存目录")
        val staged = File.createTempFile("attachment-", ".part", cache)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            fetch(item.content).use { input ->
                staged.outputStream().use { output ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = item.content.sizeBytes
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt())
                        if (count < 0) throw IOException("附件传输未完成")
                        if (count == 0) continue
                        remaining -= count
                        digest.update(bytes, 0, count)
                        output.write(bytes, 0, count)
                    }
                    if (input.read() != -1) throw IOException("附件长度不符")
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != item.content.sha256) {
                throw IOException("附件校验失败")
            }
            val filename = "sync-${UUID.randomUUID()}.${extension(item)}"
            val stored = store.writeFromFile(filename, staged)
            created += stored
            val path = attachmentRelativePath(stored)
            if (!verify(path, item.content)) throw IOException("附件写入校验失败")
            return path
        } finally {
            staged.delete()
        }
    }

    fun verify(path: String, content: AttachmentContent): Boolean =
        try { identify(path) == content } catch (_: IOException) { false }

    private fun fileName(path: String): String {
        require(path.startsWith(ATTACHMENT_DIRECTORY))
        val name = path.removePrefix(ATTACHMENT_DIRECTORY)
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        return name
    }

    private fun extension(item: SyncAttachment): String = when (item.mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/jpeg" -> "jpg"
        "audio/mp4", "audio/m4a" -> "m4a"
        else -> if (item.kind == "audio") "m4a" else "jpg"
    }
}

internal class PreparedSyncAttachments(
    val paths: Map<AttachmentContent, String>,
    private val created: List<String>,
    private val store: AttachmentStore,
) {
    fun discard() {
        created.forEach { name -> runCatching { store.delete(name) } }
    }
}
