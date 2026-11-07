package com.agentdeck.hotel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Crash-safe metadata outbox and effect receipts. Private memory and credentials never enter this file. */
internal class FleetLedger(private val file: File) {
    private fun read(): JSONObject {
        if (!file.exists()) return JSONObject().put("events", JSONArray()).put("receipts", JSONObject()).put("config_version", 0)
        require(file.length() <= MAX_BYTES) { "Fleet metadata is too large" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }
    private fun write(value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        check(temp.renameTo(file)) { "Fleet metadata could not be committed" }
    }
    fun configVersion(): Int = synchronized(lock) { read().optInt("config_version", 0) }
    /** Commit once per reporter instance before its first heartbeat; never infer order from a UUID. */
    fun nextBootGeneration(): Long = synchronized(lock) {
        val value = read()
        val raw = value.opt("boot_generation")
        require(raw == null || raw is Number) { "Invalid fleet boot generation" }
        val number = raw as? Number
        val previous = number?.toLong() ?: 0L
        require(previous in 0 until MAX_BOOT_GENERATION && (number == null || number.toDouble() == previous.toDouble())) {
            "Fleet boot generation is invalid or exhausted"
        }
        val next = previous + 1
        write(value.put("boot_generation", next))
        next
    }
    fun saveConfig(version: Int, config: JSONObject) = synchronized(lock) {
        require(version >= 0)
        val value = read()
        require(version >= value.optInt("config_version", 0)) { "Stale configuration" }
        value.put("config_version", version).put("public_config", JSONObject(config.toString()))
        write(value)
    }
    fun enqueue(type: String, metrics: JSONObject, nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        val value = read()
        val events = value.optJSONArray("events") ?: JSONArray()
        // Only disposable diagnostic samples are evicted; effect receipts are a separate object.
        while (events.length() >= FleetProtocol.MAX_EVENTS) events.remove(0)
        events.put(FleetProtocol.event(type, metrics, UUID.randomUUID().toString(), nowMillis))
        value.put("events", events)
        write(value)
    }
    fun events(): JSONArray = synchronized(lock) { JSONArray((read().optJSONArray("events") ?: JSONArray()).toString()) }
    fun acknowledgedEvents(ids: Set<String>) = synchronized(lock) {
        val value = read(); val events = value.optJSONArray("events") ?: JSONArray()
        value.put("events", JSONArray((0 until events.length()).map { events.getJSONObject(it) }.filter { it.optString("id") !in ids }))
        write(value)
    }
    fun receipt(id: String): JSONObject? = synchronized(lock) {
        read().optJSONObject("receipts")?.optJSONObject(id)?.let { JSONObject(it.toString()) }
    }
    fun receipt(command: FleetCommand, state: String, code: String, nowMillis: Long = System.currentTimeMillis()) = synchronized(lock) {
        require(state in setOf("received", "deferred", "running", "succeeded", "failed", "reconciliation_required"))
        require(code.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        val value = read(); val receipts = value.optJSONObject("receipts") ?: JSONObject()
        val expired = receipts.keys().asSequence().filter { receipts.getJSONObject(it).optLong("expires_at_ms") < nowMillis - 60_000 }.toList()
        expired.forEach { receipts.remove(it) }
        require(receipts.has(command.id) || receipts.length() < MAX_RECEIPTS) { "Too many unexpired command receipts" }
        val previous = receipts.optJSONObject(command.id)?.optString("state")
        require(previous !in TERMINAL || previous == state) { "A completed command cannot run again" }
        receipts.put(command.id, JSONObject().put("state", state).put("result_code", code)
            .put("expires_at_ms", command.expiresAtMillis).put("type", command.type))
        value.put("receipts", receipts)
        write(value)
    }
    companion object {
        private val lock = Any()
        const val MAX_BYTES = 512 * 1024L
        const val MAX_RECEIPTS = 256
        const val MAX_BOOT_GENERATION = 9_007_199_254_740_991L
        val TERMINAL = setOf("succeeded", "failed", "reconciliation_required")
    }
}
