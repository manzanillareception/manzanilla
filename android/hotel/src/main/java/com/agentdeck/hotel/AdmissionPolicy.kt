package com.agentdeck.hotel

/**
 * Managed-start admission decisions. v1 is FAIL CLOSED: a managed (enrolled) tablet with no
 * fresh admission blocks new voice starts; there is no cached-approval fallback. Unenrolled
 * tablets are unmanaged and keep the legacy V1 behavior with no management calls at all.
 */
object AdmissionPolicy {
    enum class Decision { UNMANAGED, ADMIT, DENY, UNAVAILABLE }

    fun blockedText(reason: String?): String = when (reason) {
        "pending" -> "This tablet is waiting for approval by the Manzanilla team. Voice starts once the device is approved."
        "blocked" -> "Voice on this tablet is paused by the Manzanilla team. Contact hotel staff or Manzanilla support."
        else -> "Voice is not available on this tablet right now. Contact hotel staff or Manzanilla support."
    }

    const val UNAVAILABLE_TEXT =
        "This tablet could not confirm its management status. Check the internet connection and try again. Voice stays paused until the check succeeds."

    const val REVOKED_TEXT =
        "This tablet's enrollment was revoked. Re-enroll it from Staff setup with the hotel account or a new setup code."

    const val STOP_ON_BLOCK_TEXT =
        "Voice on this tablet was paused by the Manzanilla team. The current conversation has ended."
}
