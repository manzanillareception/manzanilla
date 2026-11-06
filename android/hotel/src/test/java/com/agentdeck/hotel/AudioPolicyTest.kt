package com.agentdeck.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioPolicyTest {
    @Test fun removedProviderCannotAuthorizeMicrophoneCapture() {
        var captures = 0
        val messages = ArrayList<String>()
        if (AudioPolicy.allowStart { messages.add(it) }) captures++
        assertFalse(AudioPolicy.LIVE_AUDIO_ENABLED)
        assertEquals(0, captures)
        assertEquals(listOf("Live translation is unavailable in this build."), messages)
    }

    @Test fun missingProviderHasAnActionableTerminalMessage() {
        assertEquals("Live translation is unavailable in this build.", AudioPolicy.blockReason())
    }
}
