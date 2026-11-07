package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class VoiceDiagnosticsTest {
    @After fun clear() = VoiceDiagnostics.clear()
    @Test fun onlyFixedProviderIdentityAndFiniteKnownTimingsAreShown() {
        VoiceDiagnostics.recordMetrics(1, JSONObject().put("provider", "OpenAI")
            .put("host", "api.openai.com").put("model", "secret-marker")
            .put("key", "secret-marker").put("text", "private-speech")
            .put("connection_ready_ms", 40).put("queued_audio_ms", -1)
            .put("authorization", "secret-marker"))
        val view = VoiceDiagnostics.render()
        assertTrue(view.contains("OpenAI · api.openai.com"))
        assertTrue(view.contains("connection_ready_ms: 40 ms"))
        assertFalse(view.contains("secret-marker"))
        assertFalse(view.contains("private-speech"))
        assertFalse(view.contains("queued_audio_ms:"))
        assertTrue(view.contains("not end-to-end"))
    }
    @Test fun unknownHostsCannotClaimAProviderAndClearRemovesAllEntries() {
        VoiceDiagnostics.recordMetrics(1, JSONObject().put("provider", "OpenAI").put("host", "untrusted.example"))
        assertFalse(VoiceDiagnostics.render().contains("OpenAI"))
        VoiceDiagnostics.recordMetrics(2, JSONObject().put("native_audio_start_ms", 7))
        assertTrue(VoiceDiagnostics.render().contains("7 ms"))
        VoiceDiagnostics.clear()
        assertFalse(VoiceDiagnostics.render().contains("7 ms"))
    }
}
