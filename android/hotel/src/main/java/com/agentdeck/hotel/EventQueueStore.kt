package com.agentdeck.hotel

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Bounded persisted telemetry queue. Survives process death; capped in events and bytes;
 * overflow drops the oldest events and counts the drops. Android-free: tests use real files.
 */
class EventQueueStore(
    private val file: File,
    private val maxEvents: Int = 500,
    private val maxBytes: Int = 262_144
) {
    private val events = ArrayList<JSONObject>()
    private var loaded = false
    var droppedOldest = 0
        private set
    var storeError: String? = null
        private set

    @Synchronized fun append(event: JSONObject) {
        load()
        events.add(event)
        while (events.size > maxEvents) { events.removeAt(0); droppedOldest++ }
        while (serializedSize() > maxBytes && events.isNotEmpty()) { events.removeAt(0); droppedOldest++ }
        persist()
    }

    @Synchronized fun events(): List<JSONObject> { load(); return ArrayList(events) }

    @Synchronized fun remove(eventIds: Set<String>) {
        load()
        if (events.removeAll { it.optString("eventId") in eventIds }) persist()
    }

    @Synchronized fun clear() {
        load()
        events.clear()
        persist()
    }

    @Synchronized fun size(): Int { load(); return events.size }

    private fun serializedSize() = events.sumOf { it.toString().toByteArray(Charsets.UTF_8).size + 1 }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        try {
            file.readLines().forEach { line ->
                if (line.isNotBlank()) runCatching { JSONObject(line) }.getOrNull()?.let(events::add)
            }
        } catch (_: Exception) {
            // A corrupt queue file must not crash the app; the damaged entries are discarded.
            storeError = "The saved offline event queue could not be read and was reset."
            events.clear()
            runCatching { file.delete() }
        }
    }

    private fun persist() {
        try {
            val directory = file.parentFile ?: return
            if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("Private storage unavailable")
            val candidate = File.createTempFile("queue-", ".pending", directory)
            try {
                FileOutputStream(candidate).use { stream ->
                    events.forEach { stream.write(it.toString().toByteArray(Charsets.UTF_8)); stream.write('\n'.code) }
                    stream.fd.sync()
                }
                Files.move(candidate.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { candidate.delete() }
        } catch (_: Exception) {
            storeError = "The offline event queue could not be saved."
        }
    }
}
