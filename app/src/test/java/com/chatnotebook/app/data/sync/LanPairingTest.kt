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
        val ticket = PairingTicket("1".repeat(64)) { time }
        assertEquals(ticket.offer, PairingOffer.decode(ticket.offer.encode()))
        assertFalse(ticket.consume("bad"))
        assertTrue(ticket.consume(ticket.offer.token))
        assertFalse(ticket.consume(ticket.offer.token))
        val cancelled = PairingTicket("2".repeat(64)) { time }
        cancelled.cancel()
        assertFalse(cancelled.consume(cancelled.offer.token))
        val expired = PairingTicket("3".repeat(64)) { time }
        time += 120_000
        assertFalse(expired.consume(expired.offer.token))
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
            val offer = PairingTicket(a.fingerprint) { 0L }
            val host = async(Dispatchers.IO) {
                server.accept().use {
                    LanPairing(a, "第一台", "Model A", hostCode).accept(it, offer) { device ->
                        assertEquals(b.fingerprint, device.fingerprint)
                        assertEquals(joinerCode, device.deviceCode)
                        assertEquals("Model B", device.model)
                    }
                }
            }
            val joined = Socket(loopback, server.localPort).use {
                LanPairing(b, "第二台", "Model B", joinerCode).join(it, offer.offer)
            }
            assertEquals(PairDevice(a.fingerprint, "第一台", hostCode, "Model A"), joined)
            assertEquals(PairDevice(b.fingerprint, "第二台", joinerCode, "Model B"), host.await())
        }
    }

    @Test fun legacyPairingVersionIsRejectedWithUpdateHint() {
        val error = runCatching { requirePairingVersion(2) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("不兼容"))
    }

    @Test fun inconsistentPairingVersionIsRejectedWithUpdateHint() {
        val legacy = "chat-notebook-pair:" + """{"protocol":2,"discoveryId":"${"0".repeat(8)}-0000-0000-0000-${"0".repeat(12)}",""" +
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
                val offer = PairingTicket(a.fingerprint) { 0L }
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
}
