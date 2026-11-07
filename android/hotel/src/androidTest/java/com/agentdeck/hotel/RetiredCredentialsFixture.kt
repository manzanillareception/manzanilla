package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.Base64
import java.util.UUID

/** Isolated storage only; corrupt retired entries must be removable without decrypting. */
internal object RetiredCredentialsFixture {
    fun verify(test: Instrumentation) {
        val base = test.targetContext
        val suffix = "retired-credential-fixture-${UUID.randomUUID()}"
        val root = File(base.cacheDir, suffix).apply { mkdirs() }
        val touched = HashSet<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                touched.add(name)
                return super.getSharedPreferences("$suffix-$name", mode)
            }
        }
        try {
            val hotel = HotelConnection(context)
            check(hotel.save("https://fixture.hotel.invalid", "fixture-only-" + "x".repeat(32)))
            val other = SecretStore(context, "upgrade-test")
            check(other.save("fixture-only-upgrade-marker"))
            val legacy = context.getSharedPreferences("fish", Context.MODE_PRIVATE)
            check(legacy.edit().putString("k", "invalid-retired-encoding").commit())
            val retiredFile = File(root, "credential-" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("fish".toByteArray(Charsets.UTF_8)) + ".enc")
            retiredFile.writeText("corrupt-retired-entry-that-must-not-be-decrypted")
            val deck = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
            check(deck.edit().putBoolean("translation_direct_fish", true).commit())
            check(RetiredCredentials.remove(context))
            check(!retiredFile.exists() && !legacy.contains("k"))
            check(!deck.contains("translation_direct_fish"))
            check(deck.getBoolean("retired_audio_credentials_removed", false))
            check(hotel.load()?.base == "https://fixture.hotel.invalid")
            check(other.load() == "fixture-only-upgrade-marker")
            check(RetiredCredentials.remove(context))
            check(hotel.load() != null && other.status() == SecretStore.Status.READY)
        } finally {
            for (name in touched.toList()) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }
}
