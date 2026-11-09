package com.agentdeck.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionPolicyTest {
    @Test fun `deny reasons map to guest safe copy`() {
        assertTrue(AdmissionPolicy.blockedText("pending").contains("waiting for approval"))
        assertTrue(AdmissionPolicy.blockedText("blocked").contains("paused"))
        assertTrue(AdmissionPolicy.blockedText(null).contains("not available"))
    }

    @Test fun `unavailable copy says voice stays paused`() {
        assertTrue(AdmissionPolicy.UNAVAILABLE_TEXT.contains("Voice stays paused"))
    }

    @Test fun `revoked copy points to re-enrollment`() {
        assertTrue(AdmissionPolicy.REVOKED_TEXT.contains("revoked"))
    }
}
