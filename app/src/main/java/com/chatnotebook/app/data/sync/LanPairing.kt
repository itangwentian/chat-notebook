package com.chatnotebook.app.data.sync

import android.content.SharedPreferences
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val OFFER_LIFETIME_MS = 120_000L
private const val PAIRING_VERSION = 2
private val fingerprintPattern = Regex("[a-f0-9]{64}")
private val tokenPattern = Regex("[a-f0-9]{48}")

@Serializable
internal data class PairingOffer(
    val protocol: Int = PAIRING_VERSION,
    val discoveryId: String,
    val fingerprint: String,
    val token: String,
) {
    init {
        require(protocol == PAIRING_VERSION) { "二维码配对版本不兼容，请更新两台设备" }
        require(discoveryId.length == 36 && fingerprintPattern.matches(fingerprint) && tokenPattern.matches(token))
    }

    fun encode(): String = "chat-notebook-pair:" + syncJson.encodeToString(this)

    companion object {
        fun decode(text: String): PairingOffer {
            require(text.length <= 512 && text.startsWith("chat-notebook-pair:")) { "无法识别配对二维码" }
            return syncJson.decodeFromString(text.removePrefix("chat-notebook-pair:"))
        }
    }
}

@Serializable
internal data class LanTrust(
    val fingerprint: String,
    val localFingerprint: String,
    val name: String,
) {
    fun matches(localIdentity: String): Boolean = localIdentity == localFingerprint
}

@Serializable
private data class PairRequest(val protocol: Int, val token: String, val name: String)

@Serializable
private data class PairAccepted(val protocol: Int, val name: String)

internal data class PairDevice(val fingerprint: String, val name: String)

internal class LanPairing(private val identity: LanIdentity, private val name: String) {
    suspend fun accept(
        socket: Socket,
        ticket: PairingTicket,
        persist: suspend (PairDevice) -> Unit,
    ): PairDevice = identity.secure(socket, client = false, expectedFingerprint = null).use { secure ->
        val fingerprint = (secure.session.peerCertificates.single() as X509Certificate).fingerprint()
        val frames = LanFrames(secure)
        val request = frames.read<PairRequest>(1024)
        if (request.protocol != PAIRING_VERSION || fingerprint == identity.fingerprint || !ticket.consume(request.token)) {
            throw IOException("配对令牌无效")
        }
        val device = PairDevice(fingerprint, request.name.take(60))
        persist(device)
        frames.write(PairAccepted(PAIRING_VERSION, name.take(60)), 1024)
        device
    }

    fun join(
        socket: Socket,
        offer: PairingOffer,
    ): PairDevice = identity.secure(socket, client = true, expectedFingerprint = offer.fingerprint).use { secure ->
        val frames = LanFrames(secure)
        frames.write(PairRequest(offer.protocol, offer.token, name.take(60)), 1024)
        val accepted = frames.read<PairAccepted>(1024)
        if (accepted.protocol != PAIRING_VERSION) throw IOException("二维码配对版本不兼容")
        PairDevice(offer.fingerprint, accepted.name.take(60))
    }
}

internal class LanTrustStore(private val prefs: SharedPreferences) {
    private val savedTrustJson = Json(syncJson) { ignoreUnknownKeys = true }

    fun read(): List<LanTrust> {
        prefs.getString(PEERS_KEY, null)?.let { saved ->
            runCatching { savedTrustJson.decodeFromString<List<LanTrust>>(saved) }.getOrNull()?.let { return it }
        }
        return prefs.getString(LEGACY_PEER_KEY, null)?.let {
            runCatching { savedTrustJson.decodeFromString<LanTrust>(it) }.getOrNull()
        }?.let(::listOf) ?: emptyList()
    }

    fun replace(trusts: List<LanTrust>) {
        val edit = prefs.edit()
        if (trusts.isEmpty()) edit.remove(PEERS_KEY) else edit.putString(PEERS_KEY, syncJson.encodeToString(trusts))
        edit.remove(LEGACY_PEER_KEY)
        if (!edit.commit()) throw IOException("无法保存配对状态")
    }

    private companion object {
        const val PEERS_KEY = "lan_sync_peers"
        const val LEGACY_PEER_KEY = "lan_sync_peer"
    }
}

internal class PairingTicket(fingerprint: String, private val clock: () -> Long) {
    val offer = PairingOffer(
        discoveryId = UUID.randomUUID().toString(),
        fingerprint = fingerprint,
        token = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) },
    )
    private val expires = clock() + OFFER_LIFETIME_MS
    private var spent = false

    @Synchronized
    fun consume(token: String): Boolean {
        if (spent || clock() >= expires || offer.token != token) return false
        spent = true
        return true
    }

    @Synchronized
    fun cancel() { spent = true }
}
