package com.agentdeck.hotel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted device credential storage; independent of any bundled test key. */
class SecretStore(ctx: Context, prefsName: String = "gem") {
    enum class Status { ABSENT, READY, ERROR }

    companion object {
        // Existing encrypted entries share this alias. Do not rotate it during migration.
        private const val ALIAS = "manz_gem"
        private val lock = Any()
    }

    private val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val storage = CredentialStorage(
        File(ctx.noBackupFilesDir, "credential-" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(prefsName.toByteArray(Charsets.UTF_8)) + ".enc"),
        object : LegacyCredential {
            override fun read(): String? = prefs.getString("k", null)
            override fun remove() {
                if (prefs.contains("k") && !prefs.edit().remove("k").commit()) {
                    throw IllegalStateException("Legacy credential removal failed")
                }
            }
        },
        object : CredentialCipher {
            override fun encrypt(value: ByteArray): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
                check(cipher.iv.size == 12)
                return cipher.iv + cipher.doFinal(value)
            }
            override fun decrypt(value: ByteArray): ByteArray {
                require(value.size >= 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, value, 0, 12))
                return cipher.doFinal(value, 12, value.size - 12)
            }
        }
    )

    val status: Status get() = synchronized(lock) { Status.valueOf(storage.status.name) }
    val errorMessage: String? get() = synchronized(lock) { storage.errorMessage }

    /** Refreshes status, including a legacy plaintext migration, without exposing a key to UI. */
    fun status(): Status = synchronized(lock) { storage.load(); status }
    fun has(): Boolean = !load().isNullOrBlank()
    fun load(): String? = synchronized(lock) { storage.load() }
    fun save(value: String): Boolean = synchronized(lock) { storage.save(value) }
    fun clear() = synchronized(lock) { storage.clear() }

    private fun key(create: Boolean): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keystore.containsAlias(ALIAS)) {
            return keystore.getKey(ALIAS, null) as? SecretKey
                ?: throw IllegalStateException("Saved encryption key is unavailable")
        }
        // Reading an old entry must never generate a new key over its missing original.
        check(create)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }
}
