package com.chatnotebook.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only ciphertext; plaintext credentials never touch ordinary preferences. */
interface CredentialStore {
    fun save(ref: String, secret: String)
    fun load(ref: String): String?
    fun delete(ref: String)
    fun exists(ref: String): Boolean
}

class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun save(ref: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        prefs.edit().putString(ref, stored).apply()
    }

    override fun load(ref: String): String? = try {
        val raw = prefs.getString(ref, null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val ciphertext = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    override fun delete(ref: String) {
        prefs.edit().remove(ref).apply()
    }

    override fun exists(ref: String): Boolean = prefs.contains(ref)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "chat_notebook_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

class MemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<String, String>()

    override fun save(ref: String, secret: String) {
        values[ref] = secret
    }

    override fun load(ref: String): String? = values[ref]

    override fun delete(ref: String) {
        values.remove(ref)
    }

    override fun exists(ref: String): Boolean = values.containsKey(ref)
}
