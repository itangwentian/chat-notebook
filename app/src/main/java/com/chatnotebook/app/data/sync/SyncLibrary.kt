package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.RecordRepository
import com.chatnotebook.app.data.SessionFileCodec
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SyncLibrary(
    private val records: RecordRepository,
    private val attachments: SyncAttachments,
    private val mutations: Mutex? = null,
) {
    suspend fun generation(): Long = records.syncSnapshot().first.generation
    suspend fun snapshot(): LibrarySnapshot = withContext(Dispatchers.IO) {
        val (state, payload) = records.syncSnapshot()
        val contentByPath = mutableMapOf<String, AttachmentContent>()
        val paths = mutableMapOf<AttachmentContent, String>()
        val notes = SessionFileCodec().split(payload).mapValues { (_, part) ->
            part.toSyncConversation { path ->
                contentByPath.getOrPut(path) {
                    val known = state.attachmentIndex[path]
                    val verified = try { attachments.identify(path) } catch (error: IOException) {
                        if (known == null) throw IOException("附件无法读取，且还没有可用于恢复的同步记录", error)
                        null
                    }
                    val identity = known ?: checkNotNull(verified)
                    if (identity == verified) paths[identity] = path
                    identity
                }
            }
        }
        val indexed = records.rememberSyncAttachments(state, contentByPath)
        val entries = state.revisions.map { (id, revision) -> revision.entry(id, notes[id]?.digest()) }
        LibrarySnapshot(
            indexed,
            SyncManifest(conversations = entries, availableAttachments = paths.keys),
            notes,
            paths,
        )
    }

    fun openAttachment(snapshot: LibrarySnapshot, content: AttachmentContent): InputStream =
        attachments.open(snapshot.paths.getValue(content))

    suspend fun receive(
        snapshot: LibrarySnapshot,
        changes: Collection<ConversationRevision>,
        repairAttachments: Boolean = false,
        fetch: suspend (AttachmentContent) -> InputStream,
    ): Map<AttachmentContent, String> = withContext(Dispatchers.IO) {
        if (changes.isEmpty()) return@withContext emptyMap()
        require(changes.map { it.entry.id }.distinct().size == changes.size)
        val needed = changes.flatMap { change -> change.conversation?.messages.orEmpty().flatMap { it.attachments } }
        val prepared = attachments.prepare(needed, snapshot.paths, fetch)
        var committed = false
        try {
            val expected = changes.associate { it.entry.id to snapshot.state.revisions[it.entry.id] }
            val imported = changes.associate { change ->
                val entry = change.entry
                entry.id to DirectoryRevision(entry.digest == null, entry.versions, entry.author)
            }
            val files = changes.associate { change ->
                change.entry.id to change.conversation?.toSession { content -> prepared.paths.getValue(content) }
            }
            var cleanupFailure: Exception? = null
            suspend fun commit() {
                if (prepared.paths.any { (content, path) -> !attachments.verify(path, content) }) {
                    throw IOException("本机附件已变化，请重新同步")
                }
                records.applySyncRevisions(
                    snapshot.state.directoryId, expected, imported, files,
                    attachmentIndex = prepared.paths.entries.associate { it.value to it.key },
                    repairAttachments = repairAttachments,
                ) { removed ->
                    committed = true
                    for (path in removed) {
                        try { attachments.delete(path) } catch (error: Exception) { cleanupFailure = error }
                    }
                }
            }
            if (mutations == null) commit() else mutations.withLock { commit() }
            cleanupFailure?.let { throw IOException("笔记已同步，但部分不再引用的附件清理失败", it) }
            if (committed) prepared.paths else emptyMap()
        } finally {
            if (!committed) prepared.discard()
        }
    }
}

internal data class LibrarySnapshot(
    val state: SyncDirectoryState,
    val manifest: SyncManifest,
    val notes: Map<String, SyncConversation>,
    val paths: Map<AttachmentContent, String>,
) {
    fun revision(entry: SyncManifestEntry) = ConversationRevision(entry, notes[entry.id])
}
