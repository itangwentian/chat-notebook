package com.chatnotebook.app.data.sync

import java.net.InetAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    @Test fun interfaceScopeRejectsAnotherSubnetAndSupportsIpv6() {
        fun address(text: String) = InetAddress.getByName(text).address
        assertTrue(sameSubnet(address("192.168.1.20"), address("192.168.1.100"), 24))
        assertFalse(sameSubnet(address("192.168.1.20"), address("192.168.1.140"), 25))
        assertTrue(sameSubnet(address("fd00::11"), address("fd00::22"), 64))
        assertFalse(sameSubnet(address("fd00::11"), address("fd01::22"), 64))
        assertFalse(sameSubnet(address("fd00::11"), address("192.168.1.140"), 24))
    }
}
