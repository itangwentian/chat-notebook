package com.chatnotebook.app.data.sync

internal enum class VersionOrder { Before, Equal, After, Concurrent }

internal fun compareVersions(first: Map<String, Long>, second: Map<String, Long>): VersionOrder {
    val devices = first.keys + second.keys
    val earlier = devices.any { (first[it] ?: 0) < (second[it] ?: 0) }
    val later = devices.any { (first[it] ?: 0) > (second[it] ?: 0) }
    return when {
        earlier && later -> VersionOrder.Concurrent
        earlier -> VersionOrder.Before
        later -> VersionOrder.After
        else -> VersionOrder.Equal
    }
}

internal fun mergeVersions(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> =
    (first.keys + second.keys).sorted().associateWith { maxOf(first[it] ?: 0, second[it] ?: 0) }

internal fun localRevision(
    previous: SyncManifestEntry?,
    id: String,
    digest: String?,
    device: String,
): SyncManifestEntry {
    require(previous == null || previous.id == id)
    if (previous != null && previous.digest == digest) return previous
    val versions = previous?.versions.orEmpty()
    return SyncManifestEntry(id, digest, versions + (device to Math.addExact(versions[device] ?: 0, 1L)), device)
}

internal enum class Difference { Keep, Accept, MergeVersions, ResolveConflict }

internal data class ConversationDifference(
    val id: String,
    val decision: Difference,
    val local: SyncManifestEntry?,
    val remote: SyncManifestEntry?,
) {
    val needsRemoteConversation: Boolean
        get() = remote?.digest != null && (decision == Difference.Accept || decision == Difference.ResolveConflict)
}

/** Each peer calculates its own receive plan using the same two manifests. */
internal fun differences(local: SyncManifest, remote: SyncManifest): List<ConversationDifference> {
    require(local.protocol == LAN_SYNC_VERSION && remote.protocol == LAN_SYNC_VERSION) { "同步协议版本不兼容" }
    val localById = local.conversations.associateBy { it.id }
    val remoteById = remote.conversations.associateBy { it.id }
    require(localById.size == local.conversations.size && remoteById.size == remote.conversations.size) {
        "同步清单包含重复对话"
    }
    return (localById.keys + remoteById.keys).sorted().map { id ->
        val first = localById[id]
        val second = remoteById[id]
        val decision = when {
            second == null -> Difference.Keep
            first == null -> Difference.Accept
            first.digest == second.digest -> {
                if (mergeVersions(first.versions, second.versions) == first.versions) Difference.Keep
                else Difference.MergeVersions
            }
            else -> when (compareVersions(first.versions, second.versions)) {
                VersionOrder.Before -> Difference.Accept
                VersionOrder.After -> Difference.Keep
                VersionOrder.Concurrent -> Difference.ResolveConflict
                VersionOrder.Equal -> error("同一版本的对话内容不一致")
            }
        }
        ConversationDifference(id, decision, first, second)
    }
}
