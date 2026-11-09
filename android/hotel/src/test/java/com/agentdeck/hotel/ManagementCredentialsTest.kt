package com.agentdeck.hotel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementCredentialsTest {
    @Test fun `email validation requires single at and no spaces`() {
        assertTrue(ManagementCredentials.validEmail("front.desk@hotel.example"))
        assertFalse(ManagementCredentials.validEmail("not-an-email"))
        assertFalse(ManagementCredentials.validEmail(""))
    }

    @Test fun `setup token requires length without spaces`() {
        assertNull(ManagementCredentials.validSetupToken("abc"))
        assertNull(ManagementCredentials.validSetupToken("has space here"))
        assertNotNull(ManagementCredentials.validSetupToken("mzn-setup-12345"))
    }

    @Test fun `base url must be https with a host`() {
        assertNull(ManagementCredentials.validBase("http://admin.example.com"))
        assertEquals("https://admin.example.com", ManagementCredentials.validBase("https://admin.example.com/"))
    }

    @Test fun `device credential requires minimum length`() {
        assertNull(ManagementCredentials.validCredential("short"))
        assertNotNull(ManagementCredentials.validCredential("cred-1234567890abcdef1234567890abcdef"))
    }

    @Test fun `serialize parse round trip`() {
        val conn = ManagementCredentials.Connection("https://admin.example.com",
            "7f8d5b7a-1a2b-4c3d-9e0f-1234567890ab", "cred-1234567890abcdef1234567890abcdef")
        val parsed = ManagementCredentials.parse(ManagementCredentials.serialize(conn))
        assertEquals(conn, parsed)
        assertNull(ManagementCredentials.parse("not json"))
    }

    @Test fun `uuid validation`() {
        assertTrue(ManagementCredentials.validUuid("7f8d5b7a-1a2b-4c3d-9e0f-1234567890ab"))
        assertFalse(ManagementCredentials.validUuid("nope"))
    }
}
