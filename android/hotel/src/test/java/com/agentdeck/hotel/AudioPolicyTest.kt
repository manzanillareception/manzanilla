package com.agentdeck.hotel

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPolicyTest {
    @Test fun translationRequiresItsOwnDeviceKeyRegardlessOfReceptionReadiness() {
        for (cartesia in listOf(false, true)) {
            assertEquals("Add your OpenAI API key in Translation setup.",
                AudioPolicy.blockReason("translation", false, cartesia))
            assertEquals("", AudioPolicy.blockReason("translation", true, cartesia))
        }
    }

    @Test fun nightReceptionRequiresItsOwnConfigurationRegardlessOfTranslationReadiness() {
        for (openAi in listOf(false, true)) {
            assertEquals("The night receptionist is not configured in this app build.",
                AudioPolicy.blockReason("reception", openAi, false))
            assertEquals("", AudioPolicy.blockReason("reception", openAi, true))
        }
    }

    @Test fun unsupportedModesNeverAcquireAudioEvenWhenBothProvidersAreConfigured() {
        for (mode in listOf("", "fish", "Translation", "taxi")) {
            assertEquals("Choose a supported voice mode.", AudioPolicy.blockReason(mode, true, true))
        }
    }

    @Test fun missingTranslationKeyHasAnActionableSetupMessage() {
        assertEquals(AudioPolicy.translationBlockReason(false), AudioPolicy.blockReason())
        assertEquals("", AudioPolicy.translationBlockReason(true))
        assertEquals("", AudioPolicy.receptionBlockReason(true))
    }
}
