package com.chatnotebook.app.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

private const val KEY_ALIAS = "chat-notebook-lan-identity-v2"

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
