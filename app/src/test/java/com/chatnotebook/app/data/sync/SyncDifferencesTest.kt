package com.chatnotebook.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDifferencesTest {
    @Test fun firstSyncPreservesIndependentNotesAndDoesNotSendIdenticalBodies() {
        val sharedA = entry("shared", "same", "a")
        val sharedB = entry("shared", "same", "b")
        val a = manifest(entry("from-a", "first", "a"), sharedA)
        val b = manifest(entry("from-b", "second", "b"), sharedB)

        assertEquals(listOf("from-b"), requested(a, b))
        assertEquals(listOf("from-a"), requested(b, a))
        assertEquals(Difference.MergeVersions, differences(a, b).last().decision)
        assertEquals(mapOf("a" to 1L, "b" to 1L), mergeVersions(sharedA.versions, sharedB.versions))
    }

    @Test fun oneChangedConversationOnlyTravelsToTheOlderPeer() {
        val first = entry("first", "first", "a")
        val second = entry("second", "second", "b")
        val updated = localRevision(first, first.id, hash("new text"), "b")
        val unchanged = manifest(first, second)
        val changed = manifest(updated, second)

        assertEquals(listOf("first"), requested(unchanged, changed))
        assertTrue(requested(changed, unchanged).isEmpty())
        assertTrue(requested(changed, changed).isEmpty())
        assertEquals(updated, localRevision(updated, updated.id, updated.digest, "a"))
        assertEquals(mapOf("a" to 1L, "b" to 1L), updated.versions)
    }

    @Test fun deletionTravelsAsManifestEntryWithoutBodyAndAbsenceIsNotDeletion() {
        val note = entry("note", "text", "a")
        val deleted = localRevision(note, note.id, null, "b")

        val fromRemote = differences(manifest(note), manifest(deleted)).single()
        assertEquals(Difference.Accept, fromRemote.decision)
        assertTrue(!fromRemote.needsRemoteConversation)
        assertEquals(Difference.Keep, differences(manifest(note), manifest()).single().decision)
        assertEquals(Difference.Keep, differences(manifest(deleted), manifest(note)).single().decision)
    }

    private fun requested(local: SyncManifest, remote: SyncManifest) =
        differences(local, remote).filter { it.needsRemoteConversation }.map { it.id }

    private fun manifest(vararg entries: SyncManifestEntry) = SyncManifest(conversations = entries.toList())

    private fun entry(id: String, text: String, device: String) = localRevision(null, id, hash(text), device)

    private fun hash(text: String) = sha256(text.toByteArray())
}
