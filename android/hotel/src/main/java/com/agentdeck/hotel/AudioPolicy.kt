package com.agentdeck.hotel

/** No live-audio provider is installed in this legacy app. */
object AudioPolicy {
    const val LIVE_AUDIO_ENABLED = false
    const val BLOCK_REASON = "Live translation is unavailable in this build."

    fun blockReason(): String = BLOCK_REASON

    /** Evaluate before acquiring audio resources or starting any provider connection. */
    fun allowStart(blocked: (String) -> Unit): Boolean {
        blocked(BLOCK_REASON)
        return false
    }
}
