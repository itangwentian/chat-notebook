package com.chatnotebook.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

private val recordFileName = Regex(
    "^(session-.+-[0-9]{20}|settings-[0-9]{20}|records-[0-9]{20}-[a-f0-9-]+|sync-[0-9]{20})\\.json$",
)

interface SnapshotStore {
    /** Returns the names of candidate record files. Failure must throw, never look empty. */
    fun list(): List<String>
    fun read(name: String): ByteArray

    /** Writes [bytes] under [name], verifies the stored bytes and returns the actual file name. */
    fun write(name: String, bytes: ByteArray): String
    fun delete(name: String)
}

class SafSnapshotStore(private val context: Context, private val treeUri: Uri) : SnapshotStore {
    private fun directory(): DocumentFile {
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

    override fun list(): List<String> {
        val directory = directory()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            directory.uri, DocumentsContract.getDocumentId(directory.uri),
        )
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(0)
                    if (name != null && recordFileName.matches(name)) add(name)
                }
            }.sorted()
        } ?: throw IOException("Cannot list record directory")
    }

    override fun read(name: String): ByteArray {
        val file = directory().findFile(name) ?: throw IOException("Record file missing")
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw IOException("Record file unreadable")
    }

    override fun write(name: String, bytes: ByteArray): String {
        require(recordFileName.matches(name)) { "Invalid record file name" }
        val file = directory().createFile("application/json", name)
            ?: throw IOException("Cannot create record file")
        context.contentResolver.openOutputStream(file.uri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Cannot write record file")
        val stored = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot verify record file")
        if (!stored.contentEquals(bytes)) throw IOException("Record file verification failed")
        return file.name ?: name
    }

    override fun delete(name: String) {
        val file = directory().findFile(name) ?: return
        if (!file.delete()) throw IOException("Cannot delete record file")
    }
}
