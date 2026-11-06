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
            check(Build.HARDWARE in setOf("ranchu", "goldfish"))
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
            val voiceStore = secretClass.getConstructor(Context::class.java, String::class.java)
                .newInstance(targetContext, "fish")
            val voiceMarker = "fixture-only-voice-$marker"

            when (case) {
                "seed" -> {
                    check(!fixture.contains("marker") && !file.exists() && !store.containsAlias(alias))
                    // Refuse to overwrite existing application preferences, even on an emulator.
                    check(PREFERENCES.keys.none(deck::contains))
                    check(secretClass.getMethod("status").invoke(voiceStore).toString() == "ABSENT")
                    check(secretClass.getMethod("save", String::class.java).invoke(voiceStore, voiceMarker) == true)
                    check(secretClass.getMethod("load").invoke(voiceStore) == voiceMarker)
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
                    check(secretClass.getMethod("load").invoke(voiceStore) == voiceMarker)
                    check(secretClass.getMethod("status").invoke(voiceStore).toString() == "READY")
                }
                "bundled" -> {
                    // Only the exact synthetic key seeded above can be removed;
                    // this instrumentation also refuses to run outside an emulator.
                    check(secretClass.getMethod("load").invoke(voiceStore) == voiceMarker)
                    secretClass.getMethod("clear").invoke(voiceStore)
                    val absent = secretClass.getMethod("status").invoke(voiceStore)
                    check(absent.toString() == "ABSENT")
                    // Check the installed candidate, never the test APK's inlined
                    // BuildConfig. Do not log, copy or compare credential values.
                    // Candidates must ship without an embedded Fish credential.
                    val buildConfig = targetContext.classLoader.loadClass("com.agentdeck.hotel.BuildConfig")
                    val bundled = buildConfig.getField("FISH_API_KEY").get(null) as String
                    check(bundled.isBlank())
                    val statusClass = targetContext.classLoader.loadClass("com.agentdeck.hotel.SecretStore\$Status")
                    val policy = targetContext.classLoader.loadClass("com.agentdeck.hotel.AudioPolicy")
                    val gate = policy.getMethod("blockReason", Boolean::class.javaPrimitiveType,
                        statusClass, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                        .invoke(policy.getField("INSTANCE").get(null), false, absent, false, false)
                    check((gate as String).isNotEmpty())
                }
                else -> error("Unknown fixture case")
            }
            val evidence = if (case == "bundled") "bundled_key=false byok_gate=blocked" else
                "preferences=true private_file=true encrypted_marker=true voice_credential=true"
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
