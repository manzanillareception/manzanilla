package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementClientTest {
    private class FakeTransport(var status: Int, var response: String?, var fail: Boolean = false) : ManagementTransport {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<JSONObject?>()
        override fun execute(method: String, url: String, bearer: String?, apiKey: String?, body: JSONObject?): ManagementHttpResponse {
            calls.add("$method $url bearer=$bearer")
            bodies.add(body)
            if (fail) throw java.net.SocketTimeoutException("timeout")
            return ManagementHttpResponse(status, response)
        }
    }

    @Test fun `enroll with setup token posts to the contract route`() {
        val transport = FakeTransport(200, """{"deviceId":"d1","deviceCredential":"cred-1234567890abcdef","desiredAccess":"pending","policyRevision":1}""")
        val client = ManagementClient(transport)
        val result = client.enroll("https://admin.example.com", null, "mzn-setup-12345", "Lobby tablet", "17.0.45", "boot-1")
        assertTrue(result is ManagementClient.Result.Ok)
        assertEquals("d1", (result as ManagementClient.Result.Ok).value.deviceId)
        assertTrue(transport.calls[0].startsWith("POST https://admin.example.com/api/v1/devices/enroll"))
        assertEquals("mzn-setup-12345", transport.bodies[0]!!.getString("setupToken"))
    }

    @Test fun `enroll with hotel jwt sends bearer without setup token`() {
        val transport = FakeTransport(200, """{"deviceId":"d1","deviceCredential":"cred-1234567890abcdef","desiredAccess":"pending","policyRevision":1}""")
        val client = ManagementClient(transport)
        client.enroll("https://admin.example.com", "jwt-token", null, "Lobby tablet", "17.0.45", "boot-1")
        assertTrue(transport.calls[0].contains("bearer=jwt-token"))
        assertTrue(!transport.bodies[0]!!.has("setupToken"))
    }

    @Test fun `rejected maps the error envelope`() {
        val transport = FakeTransport(409, """{"error":{"code":"SETUP_TOKEN_CONSUMED","message":"used","retryable":false}}""")
        val client = ManagementClient(transport)
        val result = client.enroll("https://admin.example.com", null, "mzn-setup-12345", "Lobby", "17.0.45", "boot-1")
        assertTrue(result is ManagementClient.Result.Rejected)
        assertEquals("SETUP_TOKEN_CONSUMED", (result as ManagementClient.Result.Rejected).code)
    }

    @Test fun `unreachable maps transport exceptions`() {
        val transport = FakeTransport(0, null, fail = true)
        val client = ManagementClient(transport)
        assertTrue(client.enroll("https://admin.example.com", null, "x-setup-12345", "Lobby", "17.0.45", "boot-1") is ManagementClient.Result.Unreachable)
    }

    @Test fun `admission denial carries the reason`() {
        val transport = FakeTransport(200, """{"admit":false,"reason":"blocked","policyRevision":3}""")
        val client = ManagementClient(transport)
        val result = client.admitSession("https://admin.example.com", "cred", "s1", "a1", "translation")
        assertEquals("blocked", (result as ManagementClient.Result.Ok).value.reason)
    }

    @Test fun `batch envelope carries schema version`() {
        val transport = FakeTransport(200, """{"accepted":1,"duplicates":0}""")
        val client = ManagementClient(transport)
        client.sendBatch("https://admin.example.com", "cred", listOf(JSONObject().put("eventId", "e1").put("kind", "policy.ack")))
        assertEquals(1, transport.bodies[0]!!.getInt("schemaVersion"))
    }
}
