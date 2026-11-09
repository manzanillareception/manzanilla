package com.agentdeck.hotel

import android.app.Instrumentation
import android.os.Bundle

/** Run only on an isolated emulator. No provider session or guest data. */
class VoiceLifecycleFixture : Instrumentation() {
    private var args: Bundle? = null
    override fun onCreate(arguments: Bundle?) { args = arguments; super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val case = args?.getString("case") ?: "translation-unavailable"
        try {
            ProviderBuildFixture.verifyDebug(this)
            when (case) {
                "reception-bridge" -> ReceptionWebViewFixture.verify(this)
                "voice-setup" -> VoiceSetupFixture.verify(this)
                "permission-lifecycle" -> PermissionLifecycleFixture.verify(this)
                "native-audio-burst" -> NativeAudioFixture.verify(this)
                "retired-credentials" -> RetiredCredentialsFixture.verify(this)
                // Retained runner alias: V1 translation is gated only until BYOK is saved.
                "translation-unavailable", "translation-missing-key" -> ReceptionWebViewFixture.verifyTranslationMissingKey(this)
                "update-delivery" -> UpdaterFixture.verify(this)
                "management-screen" -> ManagementScreenFixture.verify(this)
                else -> error("Unknown fixture case: $case")
            }
            result.putString("stream", "PASS case=$case \n")
            finish(-1, result)
        } catch (e: Throwable) {
            // Arbitrary exception text must never expose typed or stored credentials.
            result.putString("stream", "FAIL case=$case ${e.javaClass.simpleName}; fixture did not complete\n")
            finish(0, result)
        }
    }
}
