package com.chatnotebook.app.data.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LanTrustStoreTest {
    @Test fun legacyTrustSurvivesUpgradeAndCanBeRemovedAfterReopeningStore() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("lan-trust-test", Context.MODE_PRIVATE)
        val stored = """{"fingerprint":"peer-key","directoryId":"old-directory","localFingerprint":"my-key","name":"我的平板"}"""
        prefs.edit().putString("lan_sync_peer", stored).commit()

        val trust = requireNotNull(LanTrustStore(prefs).read().singleOrNull())
        assertEquals("我的平板", trust.name)
        assertTrue(trust.matches("my-key"))
        assertFalse(trust.matches("replacement-key"))
        LanTrustStore(prefs).replace(listOf(trust))
        assertEquals(listOf(trust), LanTrustStore(prefs).read())
        assertFalse(prefs.contains("lan_sync_peer"))

        LanTrustStore(prefs).replace(emptyList())
        assertTrue(LanTrustStore(prefs).read().isEmpty())
    }

    @Test fun multiplePeersArePersistedIndependently() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("lan-trust-multiple-test", Context.MODE_PRIVATE)
        val tablet = LanTrust("peer-a", "my-key", "我的平板")
        val phone = LanTrust("peer-b", "my-key", "我的手机")

        LanTrustStore(prefs).replace(listOf(tablet, phone))
        assertEquals(listOf(tablet, phone), LanTrustStore(prefs).read())

        LanTrustStore(prefs).replace(listOf(phone))
        assertEquals(listOf(phone), LanTrustStore(prefs).read())
    }
}
