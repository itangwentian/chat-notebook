package com.chatnotebook.app.data.sync

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.CertificateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPairingTest {
    @Test fun expiredCancelledAndPreviouslyUsedOffersAreRejected() {
        var time = 10L
        val ticket = ticket("1".repeat(64)) { time }
        assertEquals(ticket.offer, PairingOffer.decode(ticket.offer.encode()))
        assertEquals("192.168.1.20", ticket.offer.address)
        assertEquals(20_000, ticket.offer.port)
        assertFalse(ticket.consume("bad"))
        assertTrue(ticket.consume(ticket.offer.token))
        assertFalse(ticket.consume(ticket.offer.token))
        val cancelled = ticket("2".repeat(64)) { time }
        cancelled.cancel()
        assertFalse(cancelled.consume(cancelled.offer.token))
        val expired = ticket("3".repeat(64)) { time }
        time += 120_000
        assertFalse(expired.consume(expired.offer.token))
    }

    @Test fun pairingOffersRejectNonIpv4AddressesAndInvalidPorts() {
        val fingerprint = "1".repeat(64)
        val token = "2".repeat(48)
        fun offer(address: String, port: Int) = PairingOffer(4, address, port, fingerprint, token)

        offer("192.168.1.20", 20_000)
        listOf("" to 20_000, "localhost" to 20_000, "0.0.0.0" to 20_000,
            "127.0.0.1" to 20_000, "192.168.1" to 20_000, "192.168.01.2" to 20_000,
            "192.168.1.20" to 80, "192.168.1.20" to 70_000).forEach { (address, port) ->
            assertTrue("应拒绝 $address:$port",
                runCatching { offer(address, port) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun pinsRejectThirdPartyAndReplacedTrustRejectsOldPeer() {
        val known = HeldCertificate.Builder().ecdsa256().build().certificate
        val other = HeldCertificate.Builder().ecdsa256().build().certificate
        PeerTrust(known.fingerprint()).checkClientTrusted(arrayOf(known), "EC")
        assertTrue(runCatching { PeerTrust(known.fingerprint()).checkClientTrusted(arrayOf(other), "EC") }
            .exceptionOrNull() is CertificateException)
        assertTrue(runCatching { PeerTrust(other.fingerprint()).checkClientTrusted(arrayOf(known), "EC") }
            .exceptionOrNull() is CertificateException)
    }

    @Test fun trustedKeyDoesNotExpireWithItsSelfSignedCertificate() {
        val certificate = HeldCertificate.Builder().ecdsa256().validityInterval(1L, 60_000L).build().certificate
        PeerTrust(certificate.fingerprint()).checkClientTrusted(arrayOf(certificate), "EC")
    }

    @Test fun scanningOncePersistsThePinnedPeerBeforeReportingSuccess() = runBlocking {
        val a = identity()
        val b = identity()
        val hostCode = "a".repeat(64)
        val joinerCode = "b".repeat(64)
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val offer = ticket(a.fingerprint) { 0L }
            val host = async(Dispatchers.IO) {
                server.accept().use {
                    LanPairing(a, "第一台", "Model A", hostCode).accept(it, offer) { device ->
                        assertEquals(b.fingerprint, device.fingerprint)
                        assertEquals(joinerCode, device.deviceCode)
                        assertEquals("Model B", device.model)
                        assertEquals(loopback.hostAddress, device.host)
                    }
                }
            }
            val joined = Socket(loopback, server.localPort).use {
                LanPairing(b, "第二台", "Model B", joinerCode).join(it, offer.offer)
            }
            assertEquals(PairDevice(a.fingerprint, "第一台", hostCode, "Model A", offer.offer.address), joined)
            assertEquals(
                PairDevice(b.fingerprint, "第二台", joinerCode, "Model B", loopback.hostAddress.orEmpty()),
                host.await(),
            )
        }
    }

    @Test fun legacyPairingVersionIsRejectedWithUpdateHint() {
        val error = runCatching { requirePairingVersion(2) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("不兼容"))
    }

    @Test fun inconsistentPairingVersionIsRejectedWithUpdateHint() {
        val legacy = "chat-notebook-pair:" + """{"protocol":2,"address":"192.168.1.20","port":20000,""" +
            """"fingerprint":"${"1".repeat(64)}","token":"${"2".repeat(48)}"}"""

        val error = runCatching { PairingOffer.decode(legacy) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("不兼容"))
    }

    @Test fun hostStorageFailurePreventsScannerFromReportingSuccess() = runBlocking {
        val a = identity()
        val b = identity()
        val loopback = InetAddress.getLoopbackAddress()
        supervisorScope {
            ServerSocket(0, 1, loopback).use { server ->
                val offer = ticket(a.fingerprint) { 0L }
                val host = async(Dispatchers.IO) {
                    runCatching {
                        server.accept().use {
                            LanPairing(a, "第一台", "Model A", "a".repeat(64)).accept(it, offer) { error("cannot save trust") }
                        }
                    }
                }
                val joined = runCatching {
                    Socket(loopback, server.localPort).use {
                        LanPairing(b, "第二台", "Model B", "b".repeat(64)).join(it, offer.offer)
                    }
                }
                assertTrue(joined.isFailure)
                assertTrue(host.await().isFailure)
            }
        }
    }

    private fun identity() = HeldCertificate.Builder().ecdsa256().build().let {
        LanIdentity(it.keyPair.private, it.certificate)
    }

    private fun ticket(fingerprint: String, clock: () -> Long) =
        PairingTicket(fingerprint, "192.168.1.20", 20_000, clock)
}
