package com.chatnotebook.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanIdentityTest {
    @Test fun stableDeviceCodeIsDeterministicAndDistinct() {
        val first = stableDeviceCode("android-id-1")
        val again = stableDeviceCode("android-id-1")
        val other = stableDeviceCode("android-id-2")

        assertEquals(first, again)
        assertNotEquals(first, other)
        assertTrue(Regex("[a-f0-9]{64}").matches(first))
    }

    @Test fun readableDeviceNamePrefersSystemName() {
        assertEquals("书房的手机", readableDeviceName("书房的手机", "Google", "Pixel 6"))
        assertEquals("书房的手机", readableDeviceName("  书房的手机  ", "unknown", "unknown"))
    }

    @Test fun readableDeviceNameFallsBackToManufacturerAndModel() {
        assertEquals("Google Pixel 6", readableDeviceName(null, "Google", "Pixel 6"))
        assertEquals("Xiaomi 13", readableDeviceName(null, "Xiaomi", "Xiaomi 13"))
        assertEquals("Pixel 6", readableDeviceName(null, "unknown", "Pixel 6"))
        assertEquals("Android", readableDeviceName(null, "unknown", "unknown"))
    }

    @Test fun readableDeviceNameCleansUpModelCodes() {
        assertEquals(
            "Google Sdk Gphone64 X86 64",
            readableDeviceName(null, "Google", "sdk_gphone64_x86_64"),
        )
    }
}