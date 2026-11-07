package com.agentdeck.hotel

import android.content.Context

/** Deletes retired credential records without loading them or changing the shared Keystore key. */
internal object RetiredCredentials {
    private const val COMPLETE = "retired_audio_credentials_removed"

    fun remove(context: Context): Boolean {
        val preferences = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
        if (preferences.getBoolean(COMPLETE, false)) return true
        // These identifiers exist only to erase data written by prior versions.
        val retired = SecretStore(context, "fish")
        retired.clear()
        if (retired.status != SecretStore.Status.ABSENT) return false
        return preferences.edit().remove("translation_direct_fish")
            .putBoolean(COMPLETE, true).commit()
    }
}
