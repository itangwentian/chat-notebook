package com.chatnotebook.app.data.sync

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
private data class SyncRequest(val note: String? = null, val attachment: AttachmentContent? = null)

@Serializable
internal data class SyncEpoch(val generation: Long, val protocol: Int = LAN_SYNC_VERSION)

internal fun SyncEpoch.requireCompatible() {
    check(protocol == LAN_SYNC_VERSION) { "同步协议版本不一致" }
}

internal data class SyncTransfer(val notes: Int = 0, val attachmentBytes: Long = 0)

internal class LanSyncProtocol(private val library: SyncLibrary) {
    suspend fun exchange(frames: LanFrames, initiator: Boolean): SyncTransfer {
        val snapshot = library.snapshot()
        val remote = if (initiator) {
            frames.write(snapshot.manifest)
            frames.read<SyncManifest>()
        } else {
            frames.read<SyncManifest>().also { frames.write(snapshot.manifest) }
        }
        if (initiator) {
            val received = pull(frames, snapshot, remote)
            serve(frames, snapshot)
            return received
        }
        serve(frames, snapshot)
        return pull(frames, snapshot, remote)
    }

    private suspend fun serve(frames: LanFrames, snapshot: LibrarySnapshot) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = frames.read<SyncRequest>(2048)
            val note = request.note
            val attachment = request.attachment
            require(note == null || attachment == null)
            when {
                note != null -> frames.write(snapshot.notes[note] ?: throw IOException("找不到请求的对话"))
                attachment != null -> {
                    if (attachment !in snapshot.paths) throw IOException("找不到请求的附件")
                    frames.output.writeLong(attachment.sizeBytes)
                    library.openAttachment(snapshot, attachment).use { stream ->
                        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                        var remaining = attachment.sizeBytes
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val count = stream.read(bytes, 0, minOf(remaining, bytes.size.toLong()).toInt())
                            if (count < 0) throw IOException("本机附件内容已变化")
                            frames.output.write(bytes, 0, count)
                            remaining -= count
                        }
                    }
                    frames.output.flush()
                }
                else -> return
            }
        }
    }

    private suspend fun pull(frames: LanFrames, snapshot: LibrarySnapshot, remote: SyncManifest): SyncTransfer {
        var noteCount = 0
        var attachmentBytes = 0L
        var available = snapshot.paths
        suspend fun fetch(attachment: AttachmentContent): InputStream {
            if (attachment !in remote.availableAttachments) throw IOException("对方缺少待同步的附件")
            frames.write(SyncRequest(attachment = attachment), 2048)
            if (frames.input.readLong() != attachment.sizeBytes) throw IOException("附件长度不符")
            attachmentBytes += attachment.sizeBytes
            return LimitedAttachment(frames.input, attachment.sizeBytes)
        }
        for (difference in differences(snapshot.manifest, remote)) {
            currentCoroutineContext().ensureActive()
            if (difference.decision == Difference.Keep) {
                val local = difference.local ?: continue
                val note = snapshot.notes[local.id] ?: continue
                if (note.attachments().any { it !in available }) {
                    val repaired = library.receive(
                        snapshot.copy(paths = available), listOf(snapshot.revision(local)),
                        repairAttachments = true, fetch = ::fetch,
                    )
                    available = available + repaired
                }
                continue
            }
            val entry = checkNotNull(difference.remote)
            val local = difference.local
            val incoming = if (difference.needsRemoteConversation) {
                frames.write(SyncRequest(note = entry.id), 2048)
                noteCount++
                ConversationRevision(entry, frames.read<SyncConversation>())
            } else ConversationRevision(entry, snapshot.notes[entry.id].takeIf { entry.digest != null })

            val changes = when (difference.decision) {
                Difference.Accept -> listOf(incoming)
                Difference.MergeVersions -> listOf(incoming.copy(entry = entry.copy(
                    versions = mergeVersions(checkNotNull(local).versions, entry.versions),
                    author = minOf(local.author, entry.author),
                )))
                Difference.ResolveConflict -> resolveConflict(snapshot.revision(checkNotNull(local)), incoming)
                    .let { listOfNotNull(it.primary, it.copy) }
                Difference.Keep -> error("unexpected difference")
            }
            val received = library.receive(snapshot.copy(paths = available), changes, fetch = ::fetch)
            available = available + received
        }
        frames.write(SyncRequest(), 2048)
        return SyncTransfer(noteCount, attachmentBytes)
    }
}

private class LimitedAttachment(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        return source.read().also { if (it >= 0) remaining-- }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        return source.read(bytes, offset, minOf(remaining, length.toLong()).toInt())
            .also { if (it > 0) remaining -= it }
    }
}
