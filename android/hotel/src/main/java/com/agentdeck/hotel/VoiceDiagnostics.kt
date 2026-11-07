package com.agentdeck.hotel

import org.json.JSONObject

/**
 * In-memory diagnostic timeline for the private test build.
 * Timestamps and event endpoint names only: never audio, transcripts, captions,
 * keys or device identifiers. Entries die with the process; nothing is persisted.
 */
internal object VoiceDiagnostics {
    private const val MAX_POINTS = 600

    data class Entry(
        val sessionId: String,
        val generation: Int,
        val sequence: Long,
        val endpoint: String,
        val atMs: Long,
        val boundaryEstimateMs: Long?,
    )

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val connections = ArrayDeque<String>()
    private val timingFields = listOf("connection_ready_ms", "native_audio_start_ms",
        "max_upload_queue_audio_ms", "max_local_receive_dispatch_ms",
        "stream_start_to_first_output_event_ms", "pcm_arrival_to_first_write_ms",
        "pcm_arrival_to_playback_head_ms", "queued_audio_ms")

    /** Only fixed identities and numeric timings enter this local, ephemeral view. */
    fun recordMetrics(generation: Int, metrics: JSONObject) = synchronized(lock) {
        val lines = ArrayList<String>()
        if (metrics.optString("provider") == "OpenAI" && metrics.optString("host") == "api.openai.com") {
            lines.add("OpenAI · api.openai.com · requested model gpt-live-1 · WebSocket ready")
        } else if (metrics.optString("provider") == "Cartesia" && metrics.optString("host") == "api.cartesia.ai") {
            lines.add("Cartesia · api.cartesia.ai · managed voice agent")
        }
        for (name in timingFields) {
            val value = metrics.opt(name) as? Number ?: continue
            val numeric = value.toDouble()
            if (numeric.isFinite() && numeric in 0.0..3_600_000.0) lines.add("$name: ${numeric.toLong()} ms")
        }
        if (lines.isNotEmpty()) {
            while (connections.size >= 120) connections.removeFirst()
            connections.addLast("Session $generation\n" + lines.joinToString("\n"))
        }
    }

    private fun renderConnections(): String = synchronized(lock) {
        if (connections.isEmpty()) "" else "Connection diagnostics\n" +
            "Device preparation, transport startup and PCM playback timings are separate measurements. " +
            "They are not end-to-end speech translation latency or acoustic measurements. " +
            "No key, speech, audio or account data is recorded. Entries clear when the app process closes.\n\n" +
            connections.joinToString("\n\n")
    }

    fun record(sessionId: String, generation: Int, point: AudioTurnTiming.Point) {
        synchronized(lock) {
            if (entries.size >= MAX_POINTS) entries.removeFirst()
            entries.addLast(
                Entry(sessionId, generation, point.sequence, point.endpoint, point.atMs, point.boundaryEstimateMs)
            )
        }
    }

    fun clear() = synchronized(lock) { entries.clear(); connections.clear() }

    /** One text block per session, times relative to that session's first event. */
    fun render(): String {
        val all = synchronized(lock) { entries.toList() }
        val connectionText = renderConnections()
        if (all.isEmpty()) return connectionText.ifEmpty { "No diagnostic events yet. Run a translation session, then return here." }
        val out = StringBuilder()
        out.append("Voice diagnostics (test build)\n")
        out.append("Times are milliseconds from each session's first event. Speech boundaries are RMS estimates, ")
        out.append("not acoustic truth. No audio or speech content is recorded.\n")
        all.groupBy { it.sessionId to it.generation }.toSortedMap(compareBy({ it.first }, { it.second }))
            .forEach { (key, points) ->
                val base = points.minOf { it.atMs }
                out.append('\n').append("Session ${key.first} (generation ${key.second})\n")
                points.forEach { p ->
                    out.append(String.format("+%06d ms  %s", p.atMs - base, p.endpoint))
                    if (p.boundaryEstimateMs != null) {
                        out.append(String.format("  (boundary estimate +%d ms)", p.boundaryEstimateMs - base))
                    }
                    out.append('\n')
                }
            }
        return out.toString() + if (connectionText.isNotEmpty()) "\n\n$connectionText" else ""
    }
}
