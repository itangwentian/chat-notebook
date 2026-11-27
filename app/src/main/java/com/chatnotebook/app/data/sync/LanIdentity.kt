package com.chatnotebook.app.data.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

private const val KEY_ALIAS = "chat-notebook-lan-identity-v2"
private const val DEVICE_CODE_PREFIX = "chat-notebook-device:"
private const val DEVICE_CODE_FALLBACK_KEY = "device_code_fallback"

internal class LanIdentity(private val key: PrivateKey, private val certificate: X509Certificate) {
    val fingerprint = certificate.fingerprint()

    fun secure(socket: Socket, client: Boolean, expectedFingerprint: String?): SSLSocket {
        require(!client || expectedFingerprint != null) { "客户端必须验证配对设备" }
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(keys()), arrayOf(PeerTrust(expectedFingerprint)), null)
        val secure = context.socketFactory.createSocket(
            socket, socket.inetAddress.hostAddress, socket.port, true,
        ) as SSLSocket
        try {
            secure.useClientMode = client
            if (!client) secure.needClientAuth = true
            secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            secure.startHandshake()
            return secure
        } catch (error: Exception) {
            runCatching { secure.close() }
            throw error
        }
    }

    private fun keys() = object : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = aliases(keyType)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = aliases(keyType)
        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
            KEY_ALIAS.takeIf { keyTypes.orEmpty().any(::supports) }
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
            KEY_ALIAS.takeIf { supports(keyType) }
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
        private fun aliases(keyType: String?) = if (supports(keyType)) arrayOf(KEY_ALIAS) else null
        private fun supports(type: String?) = type == "EC" || type?.startsWith("EC_") == true
    }

    companion object {
        @Synchronized
        fun load(): LanIdentity {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keystore.containsAlias(KEY_ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                            // Conscrypt supplies an already hashed handshake message to ECDSA.
                            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                            .build(),
                    )
                    generateKeyPair()
                }
            }
            return LanIdentity(keystore.getKey(KEY_ALIAS, null) as PrivateKey, keystore.getCertificate(KEY_ALIAS) as X509Certificate)
        }
    }
}

internal fun X509Certificate.fingerprint(): String = sha256(publicKey.encoded)

/**
 * 由设备上跨卸载重装保持不变的标识派生设备码，只用于识别“同一台物理设备”。
 * 它 MUST NOT 单独作为认证凭据，连接仍由证书指纹与一次性配对令牌校验。
 */
internal fun stableDeviceCode(rawId: String?): String =
    sha256((DEVICE_CODE_PREFIX + rawId.orEmpty()).toByteArray())

internal fun deviceCodeOf(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    if (!androidId.isNullOrBlank()) return stableDeviceCode(androidId)
    // ANDROID_ID 缺失时退化为本机随机值；该回退值随应用卸载丢失，无法跨重装识别。
    val prefs = context.getSharedPreferences("local_network_sync", Context.MODE_PRIVATE)
    prefs.getString(DEVICE_CODE_FALLBACK_KEY, null)?.let { return it }
    val generated = sha256(UUID.randomUUID().toString().toByteArray())
    prefs.edit().putString(DEVICE_CODE_FALLBACK_KEY, generated).apply()
    return generated
}

/** 优先使用用户可见的系统设备名，其次使用“厂商 型号”，避免只显示型号代号。 */
internal fun deviceNameOf(context: Context): String = readableDeviceName(
    systemName = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull(),
    manufacturer = Build.MANUFACTURER,
    model = Build.MODEL,
)

internal fun readableDeviceName(systemName: String?, manufacturer: String, model: String): String {
    systemName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(60) }
    val maker = manufacturer.meaningful()
    val device = model.meaningful()
    val combined = when {
        device.isEmpty() -> maker
        maker.isEmpty() || device.startsWith(maker, ignoreCase = true) -> device
        else -> "$maker $device"
    }
    val name = combined.ifBlank { "Android" }
        .replace('_', ' ')
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    return name.take(60)
}

private fun String.meaningful(): String =
    trim().takeUnless { it.isEmpty() || it.equals("unknown", ignoreCase = true) }.orEmpty()

/** Unknown certificates are accepted only by the temporary, token-gated pairing endpoint. */
internal class PeerTrust(private val expected: String?) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)

    private fun verify(chain: Array<out X509Certificate>) {
        val certificate = chain.singleOrNull() ?: throw CertificateException("设备证书无效")
        // The device key, rather than the validity dates of its self-signed wrapper, identifies the peer.
        try {
            certificate.verify(certificate.publicKey)
        } catch (error: Exception) {
            throw CertificateException("设备证书签名无效", error)
        }
        if (expected != null && expected != certificate.fingerprint()) {
            throw CertificateException("设备身份与配对信息不一致")
        }
    }
}
