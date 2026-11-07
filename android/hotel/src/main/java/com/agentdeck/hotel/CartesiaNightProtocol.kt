package com.agentdeck.hotel

import org.json.JSONObject
import java.util.Base64

/** Managed Agents protocol, pinned to the version used by Cartesia's official agents-web client. */
internal class CartesiaNightProtocol(private val hotelContext: String) {
    companion object {
        const val API_VERSION = "2026-08-14"
        const val SAMPLE_RATE = 16_000
        const val OUTPUT_DELIVERY = "speaking_pace"
        const val MAX_TOOL_RESULT_BYTES = 16_384
        private const val MAX_FRAME_BYTES = 1_048_576
        private const val MAX_CALLS = 128
        private val TOOLS = setOf("get_hotel_context", "get_location", "search_nearby", "request_taxi", "confirm_taxi")
    }

    sealed interface Event {
        data object Ready : Event
        data class Audio(val pcm: ByteArray) : Event
        data object Interrupted : Event
        data class Transcript(val text: String, val final: Boolean) : Event
        data class ToolCall(val id: String, val name: String, val parameters: JSONObject) : Event
        data class Send(val frame: String) : Event
        data class Error(val message: String, val retryable: Boolean, val fatal: Boolean) : Event
    }

    private data class Call(val expectsResponse: Boolean, var response: String? = null)
    private val calls = LinkedHashMap<String, Call>()
    private var started = false
    private var closed = false
    private var ready = false
    private var trailingByte: Byte? = null

    fun sessionCreate(): String {
        check(!started && !closed)
        started = true
        // API-key sessions support dynamic variables. The deployed agent must reference
        // {{hotel_context}} and register client tools; these are not per-call config overrides.
        return JSONObject().put("type", "session_create")
            .put("audio", JSONObject().put("input_format", "pcm_16000")
                // Request provider pacing. Native playout must still buffer multi-second frames
                // and network bursts; this mode does not promise a maximum frame duration.
                .put("output_delivery", OUTPUT_DELIVERY).put("continuous_input", true))
            .put("dynamic_variables", JSONObject().put("hotel_context", boundedUtf8(hotelContext, 20_000)))
            .toString()
    }

    fun audioInput(pcm: ByteArray): String? {
        if (!ready || closed || pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 3_200) return null
        return JSONObject().put("type", "audio_input")
            .put("audio", Base64.getEncoder().encodeToString(pcm)).toString()
    }

    fun receive(frame: String): List<Event> {
        if (closed || !started || frame.length > MAX_FRAME_BYTES || frame.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return emptyList()
        val event = try { JSONObject(frame) } catch (_: Exception) { return emptyList() }
        val type = event.optString("type")
        if (type == "error") {
            val code = event.optString("code").lowercase()
            val authentication = code.contains("auth") || code.contains("api_key") || code.contains("permission")
            val configuration = code.contains("agent_not_found") || code.contains("dynamic_variable") || code.contains("invalid_config")
            val message = when {
                authentication -> "Cartesia authentication failed. Check the app's Cartesia configuration."
                configuration -> "The Cartesia night receptionist needs its agent configuration updated."
                code.contains("rate") || code.contains("capacity") -> "Cartesia is busy. Please try again shortly."
                else -> "The Cartesia voice session encountered an error. Please try again."
            }
            return listOf(Event.Error(message, !authentication && !configuration, event.optBoolean("fatal", true)))
        }
        if (type == "session_ready") {
            if (ready) return emptyList()
            val format = event.optJSONObject("audio")?.optString("input_format").orEmpty()
            if (format.isNotEmpty() && format != "pcm_16000") {
                return listOf(Event.Error("Cartesia selected an unsupported audio format.", false, true))
            }
            val delivery = event.optJSONObject("audio")?.optString("output_delivery").orEmpty()
            if (delivery.isNotEmpty() && delivery != OUTPUT_DELIVERY) {
                return listOf(Event.Error("Cartesia did not enable paced audio output.", false, true))
            }
            ready = true
            return listOf(Event.Ready)
        }
        if (!ready) return emptyList()
        return when (type) {
            "audio_output" -> decodeAudio(event.optString("audio"))
            "audio_output_clear" -> {
                trailingByte = null
                listOf(Event.Interrupted)
            }
            "turn_output_text_delta" -> {
                // Only the assistant has streamed text deltas in this protocol.
                if (event.optString("role", "assistant") != "assistant") emptyList()
                else transcript(event.optString("text"), false)
            }
            "turn_ended" -> if (event.optString("role") == "assistant")
                transcript(event.optString("text"), true) else emptyList()
            "client_tool_call" -> toolCall(event)
            else -> emptyList() // New server event types are forward compatible.
        }
    }

    fun toolResult(id: String, result: JSONObject, isError: Boolean): String? {
        if (!ready || closed) return null
        val call = calls[id] ?: return null
        if (!call.expectsResponse || call.response != null) return null
        var body = result.toString()
        var error = isError
        if (body.toByteArray(Charsets.UTF_8).size > MAX_TOOL_RESULT_BYTES) {
            // Never truncate into malformed JSON or a partial Unicode code point.
            body = JSONObject().put("error", "tool_result_too_large")
                .put("message", "The result exceeded the voice service's 16 KiB limit.").toString()
            error = true
        }
        return resultFrame(id, body, error).also { call.response = it }
    }

    fun close() {
        closed = true
        ready = false
        trailingByte = null
        calls.clear()
    }

    private fun transcript(text: String, final: Boolean): List<Event> =
        if (text.isEmpty() || text.length > 32_768) emptyList() else listOf(Event.Transcript(text, final))

    private fun decodeAudio(encoded: String): List<Event> {
        if (encoded.isEmpty() || encoded.length > MAX_FRAME_BYTES) return emptyList()
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return emptyList() }
        if (bytes.isEmpty()) return emptyList()
        val carry = trailingByte
        val joined = if (carry == null) bytes else byteArrayOf(carry) + bytes
        val evenLength = joined.size - joined.size % 2
        trailingByte = if (joined.size % 2 == 1) joined.last() else null
        return if (evenLength == 0) emptyList() else listOf(Event.Audio(joined.copyOf(evenLength)))
    }

    private fun toolCall(event: JSONObject): List<Event> {
        val id = event.optString("tool_call_id")
        val name = event.optString("tool_name")
        val expectsResponse = event.optBoolean("expects_response", true)
        if (id.isBlank() || id.length > 256) return emptyList()
        calls[id]?.let { return it.response?.let { response -> listOf(Event.Send(response)) } ?: emptyList() }
        val parameters = event.optJSONObject("parameters")
        if (calls.size >= MAX_CALLS) {
            return if (expectsResponse) listOf(Event.Send(resultFrame(id, "{\"error\":\"tool_call_limit\"}", true))) else emptyList()
        }
        val call = Call(expectsResponse)
        calls[id] = call
        if (name !in TOOLS || parameters == null || parameters.toString().length > 16_384) {
            if (!expectsResponse) return emptyList()
            val response = resultFrame(id, "{\"error\":\"unsupported_or_invalid_tool\"}", true)
            call.response = response
            return listOf(Event.Send(response))
        }
        return listOf(Event.ToolCall(id, name, parameters))
    }

    private fun resultFrame(id: String, result: String, isError: Boolean): String =
        JSONObject().put("type", "client_tool_result").put("tool_call_id", id)
            .put("result", result).put("is_error", isError).toString()

    private fun boundedUtf8(value: String, limit: Int): String {
        if (JSONObject.quote(value).toByteArray(Charsets.UTF_8).size <= limit + 2) return value
        val output = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val next = String(Character.toChars(point))
            val count = JSONObject.quote(next).toByteArray(Charsets.UTF_8).size - 2
            if (bytes + count > limit) break
            output.append(next)
            bytes += count
            index += Character.charCount(point)
        }
        return output.toString()
    }
}
