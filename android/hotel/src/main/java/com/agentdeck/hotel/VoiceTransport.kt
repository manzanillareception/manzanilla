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
        fun onReady()
        fun onAudio(pcm: ByteArray)
        fun onTranscript(text: String, final: Boolean = false)
        fun onInterrupted()
        fun onError(message: String, retryable: Boolean)
        fun onClosed()
        fun onToolCall(id: String, name: String, parameters: JSONObject) {}
    }
}
