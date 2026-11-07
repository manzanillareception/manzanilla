package com.agentdeck.hotel

import org.json.JSONObject

/** Native transport boundary. Credentials and PCM never cross the guest WebView bridge. */
interface VoiceTransport {
    val inputSampleRate: Int
    val outputSampleRate: Int
    fun connect()
    fun sendAudio(pcm: ByteArray)
    fun close()
    fun completeToolCall(id: String, result: JSONObject, isError: Boolean = false) {}

    interface Listener {
        fun onDiagnostics(metrics: JSONObject) {}
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onTranscript(text: String, final: Boolean = false)
        /** Display segmentation is app metadata; it never assigns a person or side. */
        fun onTranscriptSegment(turnId: String, text: String, final: Boolean,
                                startMs: Long? = null, endMs: Long? = null) {
            onTranscript(text, final)
        }
        fun onInterrupted()
        fun onError(message: String, retryable: Boolean)
        fun onClosed()
        fun onToolCall(id: String, name: String, parameters: JSONObject) {}
    }
}
