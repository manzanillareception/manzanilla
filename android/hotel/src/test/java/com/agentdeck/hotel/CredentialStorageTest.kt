package com.agentdeck.hotel

import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CredentialStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Legacy(var entry: String? = null) : LegacyCredential {
        var failRemoval = false
        override fun read() = entry
        override fun remove() {
            if (failRemoval) throw java.io.IOException("Synthetic storage failure")
            entry = null
        }
    }

    /** Real AES-GCM with deterministic fixture-only IVs; no Android/Keystore mock. */
    private class Aes : CredentialCipher {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        private var sequence = 1
        var failEncryption = false
        var failDecryptionAt = -1
        var decryptions = 0
        override fun encrypt(value: ByteArray): ByteArray {
            if (failEncryption) throw java.security.GeneralSecurityException("Synthetic unavailable key")
            val iv = ByteArray(12).also { it[11] = sequence++.toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(value)
        }
        override fun decrypt(value: ByteArray): ByteArray {
            if (++decryptions == failDecryptionAt) throw java.security.GeneralSecurityException("Synthetic read-back failure")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value, 0, 12))
            return cipher.doFinal(value, 12, value.size - 12)
        }
    }

    private fun target(name: String = "key.enc") = File(temporary.root, name)
    private fun plaintext(value: String) = "pl:" + Base64.getEncoder().encodeToString(value.toByteArray())

    @Test fun plaintextMigrationPublishesReadableEncryptedFileBeforeRemovingLegacyEntry() {
        val file = target()
        val legacy = Legacy(plaintext("fixture-only-provider-key"))
        val aes = Aes()
        val store = CredentialStorage(file, legacy, aes)
        assertEquals("fixture-only-provider-key", store.load())
        assertEquals(CredentialStorage.Status.READY, store.status)
        assertNull(legacy.entry)
        assertTrue(file.readText().startsWith("ks:"))
        assertFalse(file.readText().contains("fixture-only-provider-key"))
        assertEquals("fixture-only-provider-key", CredentialStorage(file, legacy, aes).load())
    }

    @Test fun unavailableKeystoreDoesNotReturnOrDiscardLegacyPlaintext() {
        val file = target()
        val raw = plaintext("fixture-only-provider-key")
        val legacy = Legacy(raw)
        val store = CredentialStorage(file, legacy, Aes().apply { failEncryption = true })
        assertNull(store.load())
        assertEquals(raw, legacy.entry)
        assertFalse(file.exists())
        assertEquals(CredentialStorage.Status.ERROR, store.status)
        assertNotNull(store.errorMessage)
    }

    @Test fun failedMigrationReadBackPreservesLegacyAndCanBeRetried() {
        val file = target()
        val raw = plaintext("fixture-key")
        val legacy = Legacy(raw)
        val aes = Aes().apply { failDecryptionAt = 2 }
        val store = CredentialStorage(file, legacy, aes)
        assertNull(store.load())
        assertEquals(raw, legacy.entry)
        assertFalse(file.exists())
        aes.failDecryptionAt = -1
        assertEquals("fixture-key", store.load())
        assertNull(legacy.entry)
    }

    @Test fun encryptionAndReadBackFailuresCannotReplaceWorkingPersistedKey() {
        val file = target()
        val aes = Aes()
        val legacy = Legacy()
        val store = CredentialStorage(file, legacy, aes)
        assertTrue(store.save("original-fixture-key"))
        val originalBytes = file.readBytes()
        aes.failEncryption = true
        assertFalse(store.save("replacement-fixture-key"))
        assertArrayEquals(originalBytes, file.readBytes())
        aes.failEncryption = false
        aes.failDecryptionAt = aes.decryptions + 2
        assertFalse(store.save("replacement-fixture-key"))
        assertArrayEquals(originalBytes, file.readBytes())
        aes.failDecryptionAt = -1
        assertEquals("original-fixture-key", store.load())
        assertEquals(1, temporary.root.listFiles()!!.size)
    }

    @Test fun stagingWriteFailureDoesNotTouchExistingEncryptedEntry() {
        val aes = Aes()
        val legacyBytes = "ks:" + Base64.getEncoder().encodeToString(aes.encrypt("existing-fixture-key".toByteArray()))
        val legacy = Legacy(legacyBytes)
        val blocker = temporary.newFile("not-a-directory")
        val store = CredentialStorage(File(blocker, "key.enc"), legacy, aes)
        assertFalse(store.save("replacement-fixture-key"))
        assertEquals(legacyBytes, legacy.entry)
        assertEquals("existing-fixture-key", store.load())
    }

    @Test fun legacyEncryptedEntryRemainsReadableWithoutRewritingOrRotatingIt() {
        val file = target()
        val aes = Aes()
        val encoded = "ks:" + Base64.getEncoder().encodeToString(aes.encrypt("legacy-fixture-key".toByteArray()))
        val legacy = Legacy(encoded)
        aes.failEncryption = true
        val store = CredentialStorage(file, legacy, aes)
        assertEquals("legacy-fixture-key", store.load())
        assertEquals(encoded, legacy.entry)
        assertFalse(file.exists())
    }

    @Test fun unreadableExistingCredentialIsAnErrorNotAnAbsentKey() {
        val raw = "ks:invalid-ciphertext"
        val legacy = Legacy(raw)
        val store = CredentialStorage(target(), legacy, Aes())
        assertNull(store.load())
        assertEquals(CredentialStorage.Status.ERROR, store.status)
        assertEquals(raw, legacy.entry)
        assertNotNull(store.errorMessage)
        assertNull(CredentialStorage(target("empty.enc"), Legacy(), Aes()).load())
    }

    @Test fun failedLegacyCleanupIsVisibleAndRetriedWithoutUsingPlaintext() {
        val file = target()
        val raw = plaintext("fixture-key")
        val legacy = Legacy(raw).apply { failRemoval = true }
        val aes = Aes()
        val store = CredentialStorage(file, legacy, aes)
        assertNull(store.load())
        assertEquals(CredentialStorage.Status.ERROR, store.status)
        assertEquals(raw, legacy.entry)
        assertTrue(file.exists())
        legacy.failRemoval = false
        assertEquals("fixture-key", store.load())
        assertNull(legacy.entry)
    }

    @Test fun corruptedNewStorageDoesNotFallBackToAnOldPlaintextEntry() {
        val file = target()
        val legacy = Legacy()
        val store = CredentialStorage(file, legacy, Aes())
        assertTrue(store.save("fixture-key"))
        val bytes = file.readBytes()
        bytes[bytes.lastIndex - 3] = if (bytes[bytes.lastIndex - 3] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
        Files.write(file.toPath(), bytes)
        legacy.entry = plaintext("obsolete-fixture-key")
        assertNull(store.load())
        assertEquals(CredentialStorage.Status.ERROR, store.status)
        assertEquals(plaintext("obsolete-fixture-key"), legacy.entry)
    }

    @Test fun clearRemovesOnlyTheSelectedCredentialAndNewSaveIsReadableAfterRestart() {
        val aes = Aes()
        val voice = CredentialStorage(target("voice.enc"), Legacy(), aes)
        val gemFile = target("gem.enc")
        val gemLegacy = Legacy()
        val gem = CredentialStorage(gemFile, gemLegacy, aes)
        assertTrue(voice.save("voice-fixture-key"))
        assertTrue(gem.save("gem-fixture-key"))
        voice.clear()
        assertNull(voice.load())
        assertEquals(CredentialStorage.Status.ABSENT, voice.status)
        assertEquals("gem-fixture-key", CredentialStorage(gemFile, gemLegacy, aes).load())
    }
}
