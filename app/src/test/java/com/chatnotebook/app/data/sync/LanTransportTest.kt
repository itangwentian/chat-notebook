package com.chatnotebook.app.data.sync

import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanTransportTest {
    @Test fun onlyForegroundEnabledSessionsListenAndChangesReplaceTheNetwork() = runTest {
        val enabled = MutableStateFlow(false)
        val foreground = MutableStateFlow(true)
        val interfaceName = MutableStateFlow<String?>("wifi")
        val events = mutableListOf<String>()
        val job = launch {
            foregroundLanSessions(enabled, foreground, interfaceName) { network ->
                events += "open $network"
                try { awaitCancellation() } finally { events += "close $network" }
            }.collect {}
        }
        runCurrent()
        assertTrue(events.isEmpty())
        enabled.value = true
        runCurrent()
        interfaceName.value = "ethernet"
        runCurrent()
        foreground.value = false
        runCurrent()
        foreground.value = true
        runCurrent()
        interfaceName.value = null
        runCurrent()
        interfaceName.value = "wifi"
        runCurrent()
        enabled.value = false
        runCurrent()
        assertEquals(listOf("open wifi", "close wifi", "open ethernet", "close ethernet",
            "open ethernet", "close ethernet", "open wifi", "close wifi"), events)
        job.cancel()
    }

    @Test fun failedSessionIsRebuiltInsteadOfEndingSync() = runTest {
        val enabled = MutableStateFlow(true)
        val foreground = MutableStateFlow(true)
        val network = MutableStateFlow<String?>("wifi")
        val failures = mutableListOf<String?>()
        var attempts = 0
        val job = launch {
            foregroundLanSessions(enabled, foreground, network, onFailure = { failures += it.message }) {
                attempts++
                if (attempts == 1) throw IOException("无法发布局域网同步服务 (3)")
                awaitCancellation()
            }.collect {}
        }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(listOf("无法发布局域网同步服务 (3)"), failures)
        advanceTimeBy(SESSION_RETRY_MIN_MS + 1)
        runCurrent()
        assertEquals(2, attempts)
        job.cancel()
    }

    @Test fun interfaceScopeRejectsAnotherSubnetAndSupportsIpv6() {
        fun address(text: String) = InetAddress.getByName(text).address
        assertTrue(sameSubnet(address("192.168.1.20"), address("192.168.1.100"), 24))
        assertFalse(sameSubnet(address("192.168.1.20"), address("192.168.1.140"), 25))
        assertTrue(sameSubnet(address("fd00::11"), address("fd00::22"), 64))
        assertFalse(sameSubnet(address("fd00::11"), address("fd01::22"), 64))
        assertFalse(sameSubnet(address("fd00::11"), address("192.168.1.140"), 24))
    }

    @Test fun syncPortIsSymmetricStableAndInRange() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        assertEquals(lanPort(first, second), lanPort(second, first))
        assertEquals(lanPort(first, second), lanPort(first, second))
        assertTrue(lanPort(first, second) in LAN_PORT_BASE until LAN_PORT_BASE + LAN_PORT_COUNT)
        assertTrue(lanPort(second, first) in LAN_PORT_BASE until LAN_PORT_BASE + LAN_PORT_COUNT)
        assertTrue(lanPort(first, second) != lanPort(first, "c".repeat(64)))
    }
}
