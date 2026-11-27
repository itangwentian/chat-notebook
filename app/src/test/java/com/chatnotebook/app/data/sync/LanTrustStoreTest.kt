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
        assertEquals("", trust.deviceCode)
        assertEquals("", trust.localDeviceCode)
        assertTrue(trust.belongsTo("device-code", "my-key"))
        assertFalse(trust.belongsTo("device-code", "replacement-key"))
        LanTrustStore(prefs).replace(listOf(trust))
        assertEquals(listOf(trust), LanTrustStore(prefs).read())
        assertFalse(prefs.contains("lan_sync_peer"))

        LanTrustStore(prefs).replace(emptyList())
        assertTrue(LanTrustStore(prefs).read().isEmpty())
    }

    @Test fun identityChangeRebindsLocalTrustInsteadOfDroppingIt() {
        val legacy = LanTrust("peer-a", "my-key", "我的平板")
        val repaired = listOf(legacy).repair("device-code", "replacement-key")
        assertTrue(repaired.identityChanged)
        assertEquals("replacement-key", repaired.trusts.single().localFingerprint)
        assertEquals("device-code", repaired.trusts.single().localDeviceCode)

        val coded = LanTrust("peer-a", "old-key", "我的平板", "peer-code", "device-code")
        val rotated = listOf(coded).repair("device-code", "new-key")
        assertTrue(rotated.identityChanged)
        assertEquals("new-key", rotated.trusts.single().localFingerprint)
        assertEquals(1, rotated.trusts.size)
    }

    @Test fun trustRestoredFromAnotherDeviceIsDropped() {
        val mine = LanTrust("peer-a", "my-key", "我的平板", "peer-a-code", "device-code")
        val foreign = LanTrust("peer-b", "my-key", "旧手机", "peer-b-code", "other-device-code")

        val repaired = listOf(mine, foreign).repair("device-code", "my-key")

        assertEquals(listOf(mine), repaired.trusts)
        assertFalse(repaired.identityChanged)
    }

    @Test fun pairingSameDeviceCodeReusesEntryAndKeepsOtherPeers() {
        val old = LanTrust("old-fingerprint", "local", "手机", "peer-code", "local-code")
        val other = LanTrust("other-fingerprint", "local", "平板", "other-code", "local-code")

        val merged = listOf(old, other)
            .withPaired(PairDevice("new-fingerprint", "手机", "peer-code"), "local-code", "local")

        assertEquals(
            listOf(other, LanTrust("new-fingerprint", "local", "手机", "peer-code", "local-code")),
            merged,
        )
    }

    @Test fun sameModelDevicesWithDifferentCodesStaySeparate() {
        val first = LanTrust("fingerprint-1", "local", "Pixel 6", "code-1", "local-code")
        val second = LanTrust("fingerprint-2", "local", "Pixel 6", "code-2", "local-code")

        val merged = listOf(first, second)
            .withPaired(PairDevice("fingerprint-1-new", "Pixel 6", "code-1"), "local-code", "local")

        assertEquals(2, merged.size)
        assertEquals(setOf("code-1", "code-2"), merged.map { it.deviceCode }.toSet())
        assertEquals("fingerprint-1-new", merged.single { it.deviceCode == "code-1" }.fingerprint)
    }

    @Test fun uniqueLegacyEntryIsReplacedWhenReadableNameDiffers() {
        val legacy = LanTrust("old-fingerprint", "local", "22041211AC", "", "local-code")

        val merged = listOf(legacy)
            .withPaired(PairDevice("new-fingerprint", "Redmi K50", "peer-code", "22041211AC"), "local-code", "local")

        assertEquals(1, merged.size)
        assertEquals("Redmi K50", merged.single().name)
        assertEquals("peer-code", merged.single().deviceCode)
        assertEquals("new-fingerprint", merged.single().fingerprint)
    }

    @Test fun duplicateLegacyNamesAreNotMerged() {
        val first = LanTrust("old-a", "local", "Pixel 6", "", "local-code")
        val second = LanTrust("old-b", "local", "Pixel 6", "", "local-code")

        val merged = listOf(first, second)
            .withPaired(PairDevice("new-fingerprint", "Pixel 6", "peer-code"), "local-code", "local")

        assertEquals(3, merged.size)
    }

    @Test fun multiplePeersArePersistedIndependently() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("lan-trust-multiple-test", Context.MODE_PRIVATE)
        val tablet = LanTrust("peer-a", "my-key", "我的平板", "tablet-code", "device-code")
        val phone = LanTrust("peer-b", "my-key", "我的手机", "phone-code", "device-code")

        LanTrustStore(prefs).replace(listOf(tablet, phone))
        assertEquals(listOf(tablet, phone), LanTrustStore(prefs).read())

        LanTrustStore(prefs).replace(listOf(phone))
        assertEquals(listOf(phone), LanTrustStore(prefs).read())
    }
}