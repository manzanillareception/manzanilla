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
            when (case) {
                "reception-bridge" -> ReceptionWebViewFixture.verify(this)
                "voice-setup" -> VoiceSetupFixture.verify(this)
                "retired-credentials" -> RetiredCredentialsFixture.verify(this)
                "translation-unavailable" -> ReceptionWebViewFixture.verifyTranslationUnavailable(this)
                "update-delivery" -> UpdaterFixture.verify(this)
                else -> error("Unknown fixture case: $case")
            }
            result.putString("stream", "PASS case=$case \n")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            finish(0, result)
        }
    }
}
