package com.agentdeck.hotel

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CartesiaNightProtocolTest {
    private fun ready(protocol: CartesiaNightProtocol): CartesiaNightProtocol {
        protocol.sessionCreate()
        assertEquals(listOf(CartesiaNightProtocol.Event.Ready), protocol.receive("{\"type\":\"session_ready\",\"audio\":{\"input_format\":\"pcm_16000\"}}"))
        return protocol
    }

    private fun audio(vararg bytes: Int): String = JSONObject().put("type", "audio_output")
        .put("audio", Base64.getEncoder().encodeToString(bytes.map { it.toByte() }.toByteArray())).toString()

    private fun tool(id: String, name: String = "get_location", expects: Boolean = true): String = JSONObject()
        .put("type", "client_tool_call").put("tool_call_id", id).put("tool_name", name)
        .put("parameters", JSONObject()).put("expects_response", expects).toString()

    @Test fun `only handshake permits microphone and ready is emitted once`() {
        val protocol = CartesiaNightProtocol("Hotel Magnolia")
        assertNull(protocol.audioInput(byteArrayOf(1, 2)))
        val initial = JSONObject(protocol.sessionCreate())
        assertEquals("pcm_16000", initial.getJSONObject("audio").getString("input_format"))
        assertEquals("speaking_pace", initial.getJSONObject("audio").getString("output_delivery"))
        assertTrue(initial.getJSONObject("audio").getBoolean("continuous_input"))
        assertEquals("Hotel Magnolia", initial.getJSONObject("dynamic_variables").getString("hotel_context"))
        assertTrue(protocol.receive(audio(1, 2)).isEmpty())
        assertNull(protocol.audioInput(byteArrayOf(1, 2)))
        assertEquals(listOf(CartesiaNightProtocol.Event.Ready), protocol.receive("{\"type\":\"session_ready\"}"))
        assertTrue(protocol.receive("{\"type\":\"session_ready\"}").isEmpty())
        assertNotNull(protocol.audioInput(ByteArray(1280)))
        assertNull(protocol.audioInput(ByteArray(3202)))
        assertNull(protocol.audioInput(byteArrayOf(1)))
        protocol.close()
        assertNull(protocol.audioInput(byteArrayOf(1, 2)))
        assertTrue(protocol.receive(tool("after-close")).isEmpty())
    }

    @Test fun `audio spans frame boundaries and interruption drops partial sample`() {
        val protocol = ready(CartesiaNightProtocol(""))
        assertTrue(protocol.receive(audio(1)).isEmpty())
        val combined = protocol.receive(audio(2, 3, 4)).single() as CartesiaNightProtocol.Event.Audio
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), combined.pcm)
        protocol.receive(audio(5))
        assertEquals(listOf(CartesiaNightProtocol.Event.Interrupted), protocol.receive("{\"type\":\"audio_output_clear\"}"))
        val fresh = protocol.receive(audio(6, 7)).single() as CartesiaNightProtocol.Event.Audio
        assertArrayEquals(byteArrayOf(6, 7), fresh.pcm)
        // Mic input continues while output is flowing: no half-duplex state exists.
        assertNotNull(protocol.audioInput(byteArrayOf(8, 9)))
    }

    @Test fun `unpaced server output is rejected before native audio starts`() {
        val protocol = CartesiaNightProtocol("")
        protocol.sessionCreate()
        val event = protocol.receive("""{"type":"session_ready","audio":{"input_format":"pcm_16000","output_delivery":"as_available"}}""").single()
            as CartesiaNightProtocol.Event.Error
        assertTrue(event.fatal)
        assertFalse(event.retryable)
        assertNull(protocol.audioInput(byteArrayOf(1, 2)))
        assertTrue(protocol.receive(audio(1, 2)).isEmpty())
    }

    @Test fun `duplicate taxi tools execute once and replay only the original result`() {
        val protocol = ready(CartesiaNightProtocol(""))
        assertTrue(protocol.receive(tool("taxi-1", "confirm_taxi")).single() is CartesiaNightProtocol.Event.ToolCall)
        assertTrue(protocol.receive(tool("taxi-1", "confirm_taxi")).isEmpty())
        assertNull(protocol.toolResult("wrong-id", JSONObject().put("booked", true), false))
        val frame = protocol.toolResult("taxi-1", JSONObject().put("booked", false), false)!!
        assertNull(protocol.toolResult("taxi-1", JSONObject().put("booked", true), false))
        assertEquals(frame, (protocol.receive(tool("taxi-1", "confirm_taxi")).single() as CartesiaNightProtocol.Event.Send).frame)
        assertEquals("client_tool_result", JSONObject(frame).getString("type"))
        assertFalse(JSONObject(JSONObject(frame).getString("result")).getBoolean("booked"))
    }

    @Test fun `tool results use UTF8 byte limit and stay valid JSON`() {
        val protocol = ready(CartesiaNightProtocol(""))
        protocol.receive(tool("location-1"))
        val frame = JSONObject(protocol.toolResult("location-1", JSONObject().put("place", "🌍".repeat(5000)), false)!!)
        assertTrue(frame.getBoolean("is_error"))
        val result = frame.getString("result")
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= CartesiaNightProtocol.MAX_TOOL_RESULT_BYTES)
        assertEquals("tool_result_too_large", JSONObject(result).getString("error"))
    }

    @Test fun `unknown tools never reach device and no response notifications cannot be answered`() {
        val protocol = ready(CartesiaNightProtocol(""))
        val rejected = protocol.receive(tool("unknown-1", "send_money")).single() as CartesiaNightProtocol.Event.Send
        assertTrue(JSONObject(rejected.frame).getBoolean("is_error"))
        assertTrue(protocol.receive(tool("notify-1", "get_location", false)).single() is CartesiaNightProtocol.Event.ToolCall)
        assertNull(protocol.toolResult("notify-1", JSONObject(), false))
    }

    @Test fun `assistant deltas and final text do not include guest turns`() {
        val protocol = ready(CartesiaNightProtocol(""))
        assertEquals(listOf(CartesiaNightProtocol.Event.Transcript("Hello", false)), protocol.receive("{\"type\":\"turn_output_text_delta\",\"role\":\"assistant\",\"text\":\"Hello\"}"))
        assertTrue(protocol.receive("{\"type\":\"turn_ended\",\"role\":\"user\",\"text\":\"Private guest question\"}").isEmpty())
        assertEquals(listOf(CartesiaNightProtocol.Event.Transcript("Hello there", true)), protocol.receive("{\"type\":\"turn_ended\",\"role\":\"assistant\",\"text\":\"Hello there\"}"))
    }

    @Test fun `invalid frames are ignored and errors never echo provider secrets`() {
        val protocol = ready(CartesiaNightProtocol(""))
        assertTrue(protocol.receive("not json").isEmpty())
        assertTrue(protocol.receive("{\"type\":\"future_event\"}").isEmpty())
        assertTrue(protocol.receive("{\"type\":\"audio_output\",\"audio\":\"!!!\"}").isEmpty())
        val error = protocol.receive("{\"type\":\"error\",\"code\":\"authentication_failed\",\"message\":\"key=do-not-show\",\"fatal\":true}").single() as CartesiaNightProtocol.Event.Error
        assertFalse(error.message.contains("do-not-show"))
        assertFalse(error.retryable)
        assertTrue(error.fatal)
    }

    @Test fun `session context and incoming events respect websocket byte limit`() {
        val protocol = CartesiaNightProtocol("🌍".repeat(12_000))
        val frame = protocol.sessionCreate()
        assertTrue(frame.toByteArray(Charsets.UTF_8).size < 32_768)
        assertTrue(JSONObject(frame).getJSONObject("dynamic_variables").getString("hotel_context").endsWith("🌍"))
        assertTrue(CartesiaNightProtocol("\u0000".repeat(20_000)).sessionCreate().toByteArray(Charsets.UTF_8).size < 32_768)
        protocol.receive("{\"type\":\"session_ready\"}")
        assertTrue(protocol.receive(JSONObject().put("type", "client_tool_call").put("parameters", "x".repeat(33_000)).toString()).isEmpty())
    }
}
