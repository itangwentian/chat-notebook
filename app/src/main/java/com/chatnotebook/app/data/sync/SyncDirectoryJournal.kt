package com.chatnotebook.app.data.sync

import com.chatnotebook.app.data.SnapshotStore
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class DirectoryRevision(
    val deleted: Boolean,
    val versions: Map<String, Long>,
    val author: String,
) {
    fun entry(id: String, digest: String?): SyncManifestEntry {
        require(deleted == (digest == null)) { "对话状态与同步版本不一致" }
        return SyncManifestEntry(id, digest, versions, author)
    }

    fun advance(device: String, deleted: Boolean) =
        DirectoryRevision(deleted, versions + (device to Math.addExact(versions[device] ?: 0, 1L)), device)
}

@Serializable
internal data class SyncDirectoryState(
    val directoryId: String = UUID.randomUUID().toString(),
    val localDevice: String,
    val generation: Long,
    val sessionFiles: Map<String, String>,
    val settingsFile: String?,
    val revisions: Map<String, DirectoryRevision>,
    val attachmentIndex: Map<String, AttachmentContent> = emptyMap(),
)

@Serializable
private data class DirectoryEnvelope(val version: Int, val payload: String, val sha256: String)

internal val syncJournalName = Regex("sync-[0-9]{20}\\.json")

internal class SyncDirectoryJournal(private val store: SnapshotStore) {
    private var currentName: String? = null

    fun restore(names: List<String>): SyncDirectoryState? {
        for (name in names.filter(syncJournalName::matches).sortedDescending()) {
            val bytes = store.read(name)
            val envelope = runCatching {
                syncJson.decodeFromString<DirectoryEnvelope>(bytes.decodeToString())
            }.getOrNull() ?: continue
            if (sha256(envelope.payload.toByteArray()) != envelope.sha256) continue
            // 旧版本（低于当前协议版本）的同步元数据仍可读取，下次提交时自动升级。
            if (envelope.version !in 1..LAN_SYNC_VERSION) throw IOException("不支持此记录目录的同步版本")
            val state = syncJson.decodeFromString<SyncDirectoryState>(envelope.payload)
            require(nameOf(state.generation) == name)
            require(state.revisions.filterValues { !it.deleted }.keys == state.sessionFiles.keys)
            require(state.sessionFiles.values.all { it in names } && (state.settingsFile == null || state.settingsFile in names)) {
                "同步记录文件不完整，请检查记录目录"
            }
            currentName = name
            return state
        }
        return null
    }

    fun commit(state: SyncDirectoryState) {
        val payload = syncJson.encodeToString(state)
        val envelope = DirectoryEnvelope(LAN_SYNC_VERSION, payload, sha256(payload.toByteArray()))
        val name = nameOf(state.generation)
        val stored = store.write(name, syncJson.encodeToString(envelope).toByteArray())
        check(stored == name) { "无法写入记录目录的同步元数据" }
        val previous = currentName
        currentName = stored
        previous?.let { runCatching { store.delete(it) } }
    }

    private fun nameOf(generation: Long) = "sync-${generation.toString().padStart(20, '0')}.json"
}
