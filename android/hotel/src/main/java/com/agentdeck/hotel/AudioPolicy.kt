package com.agentdeck.hotel

/** Independently gate each native voice mode before any microphone acquisition. */
object AudioPolicy {
    fun translationBlockReason(configured: Boolean) = if (configured) "" else "Add your OpenAI API key in Translation setup."
    fun receptionBlockReason(configured: Boolean) = if (configured) "" else "The night receptionist is not configured in this app build."
    fun blockReason(mode: String, openAiConfigured: Boolean, cartesiaConfigured: Boolean) = when (mode) {
        "translation" -> translationBlockReason(openAiConfigured)
        "reception" -> receptionBlockReason(cartesiaConfigured)
        else -> "Choose a supported voice mode."
    }
    fun blockReason() = translationBlockReason(false)
}
