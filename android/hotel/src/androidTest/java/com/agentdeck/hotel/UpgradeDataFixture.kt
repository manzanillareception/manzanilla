package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Installed only on a fresh disposable emulator. No production application class references:
 * this candidate test APK must also run against the previously published application.
 */
class UpgradeDataFixture : Instrumentation() {
    private var args: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        args = arguments
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val case = args?.getString("case").takeIf { it in setOf("seed", "verify", "bundled") } ?: "unknown"
        try {
            check(args?.getString("isolated") == "1")
            check(Build.HARDWARE == "ranchu")
            check(targetContext.packageName == TARGET && context.packageName == "$TARGET.test")
            val marker = args?.getString("marker") ?: error("Missing fixture marker")
            check(Regex("[0-9a-fA-F]{40}").matches(marker))
            val fixture = targetContext.getSharedPreferences("upgrade-data-fixture", Context.MODE_PRIVATE)
            val deck = targetContext.getSharedPreferences("deck", Context.MODE_PRIVATE)
            val file = File(targetContext.filesDir, "upgrade-data-fixture.bin")
            val alias = "manzanilla-upgrade-fixture-$marker"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // Invoke each installed APK's actual credential implementation. This
            // fixture also runs on the previous release, so do not link candidate
            // production classes into the test APK. Only synthetic data is used.
            val secretClass = targetContext.classLoader.loadClass("com.agentdeck.hotel.SecretStore")
            val openAiStore = secretClass.getConstructor(Context::class.java, String::class.java)
                .newInstance(targetContext, "openai")
            val openAiMarker = "fixture-only-openai-$marker"
            val openAiFile = File(targetContext.noBackupFilesDir, "credential-" +
                Base64.encodeToString("openai".toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".enc")
            fun ciphertextDigest(): String {
                check(openAiFile.isFile && openAiFile.length() >= 28)
                val bytes = openAiFile.readBytes()
                check(!bytes.toString(Charsets.ISO_8859_1).contains(openAiMarker))
                check(!targetContext.getSharedPreferences("openai", Context.MODE_PRIVATE).contains("k"))
                return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)
            }

            when (case) {
                "seed" -> {
                    check(!fixture.contains("marker") && !file.exists() && !store.containsAlias(alias))
                    // Refuse to overwrite existing application preferences, even on an emulator.
                    check(PREFERENCES.keys.none(deck::contains))
                    check(!openAiFile.exists())
                    check(secretClass.getMethod("status").invoke(openAiStore).toString() == "ABSENT")
                    check(secretClass.getMethod("save", String::class.java).invoke(openAiStore, openAiMarker) == true)
                    check(secretClass.getMethod("load").invoke(openAiStore) == openAiMarker)
                    val openAiDigest = ciphertextDigest()
                    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    generator.init(KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey())
                    val encrypted = cipher.doFinal(marker.toByteArray(Charsets.UTF_8))
                    file.outputStream().use { output ->
                        output.write(marker.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    check(deck.edit().apply { PREFERENCES.forEach { (name, value) -> putString(name, value) } }.commit())
                    check(fixture.edit().putString("marker", marker)
                        .putString("openai_ciphertext_sha256", openAiDigest)
                        .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                        .putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
                }
                "verify" -> {
                    check(fixture.getString("marker", null) == marker)
                    check(PREFERENCES.all { (name, value) -> deck.getString(name, null) == value })
                    check(file.readText(Charsets.UTF_8) == marker)
                    val key = store.getKey(alias, null)
                    check(key != null)
                    val iv = Base64.decode(fixture.getString("iv", null) ?: error("Missing fixture IV"), Base64.NO_WRAP)
                    val encrypted = Base64.decode(fixture.getString("encrypted", null) ?: error("Missing encrypted fixture"), Base64.NO_WRAP)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    check(String(cipher.doFinal(encrypted), Charsets.UTF_8) == marker)
                    check(ciphertextDigest() == fixture.getString("openai_ciphertext_sha256", null))
                    check(secretClass.getMethod("load").invoke(openAiStore) == openAiMarker)
                    check(secretClass.getMethod("status").invoke(openAiStore).toString() == "READY")
                }
                "bundled" -> {
                    // Only the exact synthetic key seeded above can be removed;
                    // this instrumentation also refuses to run outside an emulator.
                    check(secretClass.getMethod("load").invoke(openAiStore) == openAiMarker)
                    secretClass.getMethod("clear").invoke(openAiStore)
                    val absent = secretClass.getMethod("status").invoke(openAiStore)
                    check(absent.toString() == "ABSENT")
                    check(!openAiFile.exists())
                    // Cartesia bundling is the owner's V1 release choice. OpenAI stays BYOK.
                    // Values are inspected only for presence and are never printed or returned.
                    val buildConfig = targetContext.classLoader.loadClass("com.agentdeck.hotel.BuildConfig")
                    check(buildConfig.getField("DEBUG").get(null) == false)
                    check(buildConfig.fields.none { it.name.contains("OPENAI", ignoreCase = true) || it.name.contains("FISH", ignoreCase = true) })
                    check(buildConfig.fields.filter { it.name.endsWith("API_KEY") }.map { it.name } == listOf("CARTESIA_API_KEY"))
                    check((buildConfig.getField("CARTESIA_API_KEY").get(null) as String).isNotBlank())
                    check((buildConfig.getField("CARTESIA_AGENT_ID").get(null) as String).isNotBlank())
                    val policy = targetContext.classLoader.loadClass("com.agentdeck.hotel.AudioPolicy")
                    val instance = policy.getField("INSTANCE").get(null)
                    val gate = policy.getMethod("blockReason", String::class.java, Boolean::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType)
                    check(gate.invoke(instance, "translation", false, true) == "Add your OpenAI API key in Translation setup.")
                    check(gate.invoke(instance, "translation", true, false) == "")
                    check(gate.invoke(instance, "reception", false, true) == "")
                    check((gate.invoke(instance, "reception", true, false) as String).isNotBlank())
                }
                else -> error("Unknown fixture case")
            }
            val evidence = if (case == "bundled") "openai_build_field=false cartesia_bundled=true translation_gate=missing_byok" else
                "preferences=true private_file=true encrypted_marker=true openai_credential=true openai_ciphertext=true"
            result.putString("stream", "PASS case=$case $evidence\n")
            finish(-1, result)
        } catch (_: Throwable) {
            // Never print values from device storage, credentials, or arbitrary exception text.
            result.putString("stream", "FAIL case=$case upgrade data fixture did not complete\n")
            finish(0, result)
        }
    }

    private companion object {
        const val TARGET = "dev.orange.hotel"
        val PREFERENCES = mapOf("reception_appearance" to "light",
            "reception_guest_language" to "fr", "reception_staff_language" to "de")
    }
}
