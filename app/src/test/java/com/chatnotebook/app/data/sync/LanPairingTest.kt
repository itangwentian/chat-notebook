package com.chatnotebook.app.data.sync

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
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { server ->
            val offer = PairingTicket(a.fingerprint) { 0L }
            val host = async(Dispatchers.IO) {
                server.accept().use {
                    LanPairing(a, "第一台").accept(it, offer) { device ->
                        assertEquals(b.fingerprint, device.fingerprint)
                    }
                }
            }
            val joined = Socket(loopback, server.localPort).use {
                LanPairing(b, "第二台").join(it, offer.offer)
            }
            assertEquals(PairDevice(a.fingerprint, "第一台"), joined)
            assertEquals(PairDevice(b.fingerprint, "第二台"), host.await())
        }
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
                        server.accept().use { LanPairing(a, "第一台").accept(it, offer) { error("cannot save trust") } }
                    }
                }
                val joined = runCatching {
                    Socket(loopback, server.localPort).use { LanPairing(b, "第二台").join(it, offer.offer) }
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
