package com.chatnotebook.app.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Stores attachment files under `attachments/` inside the selected record directory. */
interface AttachmentStore {
    /** Writes [bytes] as [name], verifies the stored content and returns the actual file name. */
    fun write(name: String, bytes: ByteArray): String
    fun read(name: String): ByteArray
    fun open(name: String): InputStream = read(name).inputStream()
    fun writeFromFile(name: String, source: File): String = write(name, source.readBytes())
    fun exists(name: String): Boolean
    fun delete(name: String)
    fun uri(name: String): Uri?
}

fun attachmentFileName(relativePath: String): String =
    relativePath.removePrefix(ATTACHMENT_DIRECTORY)

fun attachmentRelativePath(name: String): String = ATTACHMENT_DIRECTORY + name

class SafAttachmentStore(
    private val context: Context,
    private val treeUri: Uri,
) : AttachmentStore {
    private fun root(): DocumentFile {
        val hasGrant = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!hasGrant) throw IOException("Record directory permission unavailable")
        val dir = DocumentFile.fromTreeUri(context, treeUri)
        if (dir == null || !dir.exists() || !dir.canRead() || !dir.canWrite()) {
            throw IOException("Record directory unavailable")
        }
        return dir
    }

    private val directoryName = ATTACHMENT_DIRECTORY.removeSuffix("/")

    private fun existingDirectory(root: DocumentFile): DocumentFile? =
        root.findFile(directoryName)?.takeIf { it.isDirectory }

    private fun directory(): DocumentFile =
        existingDirectory(root()) ?: throw IOException("Attachment directory missing")

    @Synchronized
    internal fun protectExistingMedia() {
        existingDirectory(root())?.let(::ensureMediaMarker)
    }

    @Synchronized
    private fun directoryForWrite(): DocumentFile {
        val root = root()
        val directory = existingDirectory(root) ?: root.createDirectory(directoryName)
            ?: throw IOException("Cannot create attachment directory")
        ensureMediaMarker(directory)
        return directory
    }

    private fun ensureMediaMarker(directory: DocumentFile) {
        val name = MediaStore.MEDIA_IGNORE_FILENAME
        try {
            if (directory.findFile(name)?.isFile == true) return
            val marker = directory.createFile("application/octet-stream", name)
            if (marker?.name == name) return
            // Some document providers rename or reject hidden files.
            marker?.delete()
            Log.w("SafAttachmentStore", "Document provider could not create the media exclusion marker")
        } catch (error: SecurityException) {
            Log.w("SafAttachmentStore", "Document provider blocked the media exclusion marker")
        }
    }

    override fun write(name: String, bytes: ByteArray): String {
        require(name.isNotBlank() && '/' !in name) { "Invalid attachment file name" }
        val dir = directoryForWrite()
        dir.findFile(name)?.delete()
        val file = dir.createFile(mimeType(name), name)
            ?: throw IOException("Cannot create attachment file")
        context.contentResolver.openOutputStream(file.uri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Cannot write attachment file")
        val stored = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot verify attachment file")
        if (!stored.contentEquals(bytes)) throw IOException("Attachment file verification failed")
        return file.name ?: name
    }

    override fun read(name: String): ByteArray {
        return open(name).use { it.readBytes() }
    }

    override fun open(name: String): InputStream {
        val file = directory().findFile(name)
            ?: throw IOException("Attachment file missing")
        return context.contentResolver.openInputStream(file.uri)
            ?: throw IOException("Attachment file unreadable")
    }

    override fun writeFromFile(name: String, source: File): String {
        require(name.matches(Regex("[a-zA-Z0-9.-]+"))) { "Invalid attachment file name" }
        val dir = directoryForWrite()
        val file = dir.createFile(mimeType(name), name) ?: throw IOException("Cannot create attachment file")
        try {
            context.contentResolver.openOutputStream(file.uri, "w")?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Cannot write attachment file")
            val storedName = file.name ?: throw IOException("Cannot read attachment file name")
            require(storedName.isNotBlank() && '/' !in storedName && '\\' !in storedName)
            return storedName
        } catch (error: Exception) {
            runCatching { file.delete() }
            throw error
        }
    }

    override fun exists(name: String): Boolean =
        runCatching { directory().findFile(name) != null }.getOrDefault(false)

    override fun delete(name: String) {
        val file = directory().findFile(name) ?: return
        if (!file.delete()) throw IOException("Cannot delete attachment file")
    }

    override fun uri(name: String): Uri? =
        runCatching { directory().findFile(name)?.uri }.getOrNull()

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
