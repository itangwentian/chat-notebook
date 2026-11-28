package com.chatnotebook.app.data.sync

import android.content.SharedPreferences
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val OFFER_LIFETIME_MS = 120_000L
private const val PAIRING_VERSION = 4
private val fingerprintPattern = Regex("[a-f0-9]{64}")
private val tokenPattern = Regex("[a-f0-9]{48}")

private fun String.isDeviceCode(): Boolean = isEmpty() || fingerprintPattern.matches(this)

/** 只接受点分十进制 IPv4 字面量，避免解码时触发域名解析。 */
private fun String.isIpv4Literal(): Boolean {
    val parts = split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
            (part.length == 1 || !part.startsWith('0')) && part.toInt() <= 255
    }
}

internal fun requirePairingVersion(protocol: Int) {
    if (protocol != PAIRING_VERSION) throw IOException("配对版本不兼容，请更新两台设备")
}

@Serializable
internal data class PairingOffer(
    val protocol: Int = PAIRING_VERSION,
    val address: String,
    val port: Int,
    val fingerprint: String,
    val token: String,
) {
    init {
        require(protocol == PAIRING_VERSION) { "二维码配对版本不兼容，请更新两台设备" }
        require(fingerprintPattern.matches(fingerprint) && tokenPattern.matches(token)) { "配对信息无效" }
        val host = address.takeIf { it.isIpv4Literal() }?.let { InetAddress.getByName(it) }
            ?: throw IllegalArgumentException("配对地址无效")
        require(!host.isAnyLocalAddress && !host.isLoopbackAddress && !host.isMulticastAddress) { "配对地址无效" }
        require(port in 1024..65535) { "配对端口无效" }
    }

    fun encode(): String = "chat-notebook-pair:" + syncJson.encodeToString(this)

    companion object {
        fun decode(text: String): PairingOffer {
            require(text.length <= 512 && text.startsWith("chat-notebook-pair:")) { "无法识别配对二维码" }
            val payload = text.removePrefix("chat-notebook-pair:")
            // 旧版本二维码缺少地址与端口，先读出版本号才能提示升级而不是“内容无效”。
            val protocol = runCatching {
                syncJson.parseToJsonElement(payload).jsonObject["protocol"]?.jsonPrimitive?.int
            }.getOrNull()
            if (protocol != null && protocol != PAIRING_VERSION) {
                throw IllegalArgumentException("二维码配对版本不兼容，请更新两台设备")
            }
            return syncJson.decodeFromString(payload)
        }
    }
}

@Serializable
internal data class LanTrust(
    val fingerprint: String,
    val localFingerprint: String = "",
    val name: String,
    val deviceCode: String = "",
    val localDeviceCode: String = "",
    val host: String = "",
) {
    fun belongsTo(localDeviceCode: String, localFingerprint: String): Boolean = when {
        this.localDeviceCode.isNotBlank() -> this.localDeviceCode == localDeviceCode
        else -> this.localFingerprint == localFingerprint
    }

    fun rebind(localDeviceCode: String, localFingerprint: String): LanTrust =
        copy(localDeviceCode = localDeviceCode, localFingerprint = localFingerprint)
}

internal data class TrustRepair(val trusts: List<LanTrust>, val identityChanged: Boolean)

/**
 * 归一化已保存的信任：其他设备的备份数据被清理，本机条目在证书密钥变化时重绑定，
 * 旧版本（无设备码）条目原地迁移而不丢失。
 */
internal fun List<LanTrust>.repair(
    localDeviceCode: String,
    localFingerprint: String,
): TrustRepair {
    var identityChanged = false
    val trusts = mapNotNull { entry ->
        if (entry.localDeviceCode.isNotBlank() && entry.localDeviceCode != localDeviceCode) {
            null
        } else if (entry.localFingerprint != localFingerprint || entry.localDeviceCode != localDeviceCode) {
            identityChanged = identityChanged || entry.localFingerprint != localFingerprint
            entry.rebind(localDeviceCode, localFingerprint)
        } else {
            entry
        }
    }
    return TrustRepair(trusts, identityChanged)
}

/**
 * 将本次配对结果写入信任：同一设备码、同一指纹或唯一同名的旧条目被替换，
 * 不新增重复设备，也不影响其他已配对设备。
 */
internal fun List<LanTrust>.withPaired(
    peer: PairDevice,
    localDeviceCode: String,
    localFingerprint: String,
): List<LanTrust> {
    val code = peer.deviceCode
    val legacyCandidates = if (code.isBlank()) {
        emptyList()
    } else {
        filter {
            it.deviceCode.isBlank() &&
                (it.name == peer.name || (peer.model.isNotBlank() && it.name == peer.model))
        }
    }
    val legacyName = legacyCandidates.size == 1
    val remaining = filterNot { entry ->
        (code.isNotBlank() && entry.deviceCode == code) ||
            entry.fingerprint == peer.fingerprint ||
            (legacyName && entry.deviceCode.isBlank() && legacyCandidates.contains(entry))
    }
    return remaining + LanTrust(
        fingerprint = peer.fingerprint,
        localFingerprint = localFingerprint,
        name = peer.name,
        deviceCode = code,
        localDeviceCode = localDeviceCode,
        host = peer.host,
    )
}

@Serializable
private data class PairRequest(
    val protocol: Int,
    val token: String,
    val name: String,
    val model: String = "",
    val deviceCode: String = "",
)

@Serializable
private data class PairAccepted(
    val protocol: Int,
    val name: String,
    val model: String = "",
    val deviceCode: String = "",
)

internal data class PairDevice(
    val fingerprint: String,
    val name: String,
    val deviceCode: String = "",
    val model: String = "",
    val host: String = "",
)

internal class LanPairing(
    private val identity: LanIdentity,
    private val name: String,
    private val model: String,
    private val deviceCode: String,
) {
    suspend fun accept(
        socket: Socket,
        ticket: PairingTicket,
        persist: suspend (PairDevice) -> Unit,
    ): PairDevice = identity.secure(socket, client = false, expectedFingerprint = null).use { secure ->
        val fingerprint = (secure.session.peerCertificates.single() as X509Certificate).fingerprint()
        val frames = LanFrames(secure)
        val request = frames.read<PairRequest>(1024)
        requirePairingVersion(request.protocol)
        if (fingerprint == identity.fingerprint || !ticket.consume(request.token)) {
            throw IOException("配对令牌无效")
        }
        if (!request.deviceCode.isDeviceCode()) throw IOException("配对信息无效")
        val device = PairDevice(
            fingerprint,
            request.name.take(60),
            request.deviceCode,
            request.model.take(60),
            socket.inetAddress.hostAddress.orEmpty(),
        )
        persist(device)
        frames.write(PairAccepted(PAIRING_VERSION, name.take(60), model.take(60), deviceCode), 1024)
        device
    }

    fun join(
        socket: Socket,
        offer: PairingOffer,
    ): PairDevice = identity.secure(socket, client = true, expectedFingerprint = offer.fingerprint).use { secure ->
        val frames = LanFrames(secure)
        frames.write(PairRequest(offer.protocol, offer.token, name.take(60), model.take(60), deviceCode), 1024)
        val accepted = frames.read<PairAccepted>(1024)
        requirePairingVersion(accepted.protocol)
        if (!accepted.deviceCode.isDeviceCode()) throw IOException("配对信息无效")
        PairDevice(offer.fingerprint, accepted.name.take(60), accepted.deviceCode, accepted.model.take(60), offer.address)
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

internal class PairingTicket(
    fingerprint: String,
    address: String,
    port: Int,
    private val clock: () -> Long,
) {
    val offer = PairingOffer(
        address = address,
        port = port,
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
