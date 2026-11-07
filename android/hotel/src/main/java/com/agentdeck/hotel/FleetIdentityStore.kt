package com.agentdeck.hotel

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Independent encrypted V2 identity. Never replaces HotelConnection or a provider credential. */
internal class FleetIdentityStore(private val storage: FleetSecretStorage) {
    constructor(context: Context) : this(object : FleetSecretStorage {
        private val secret = SecretStore(context.applicationContext, "fleet_identity_v2")
        override fun read() = secret.load()
        override fun write(value: String) = secret.save(value)
        override fun failed() = secret.status() == SecretStore.Status.ERROR
    })
    data class Starting(val base: String, val deviceName: String, val pollToken: String)
    data class Pending(val base: String, val requestId: String, val userCode: String,
        val pollToken: String, val expiresAtMillis: Long)
    data class Credentials(val base: String, val deviceId: String, val hotelId: String,
        val token: String, val activationPending: Boolean)
    data class Rotation(val operationId: String, val previous: Credentials)
    data class Snapshot(val installationId: String, val pending: Pending? = null,
        val credentials: Credentials? = null, val starting: Starting? = null, val rotation: Rotation? = null)

    fun load(): Snapshot? = synchronized(lock) {
        val raw = storage.read() ?: return@synchronized null
        val value = JSONObject(raw)
        val pending = value.optJSONObject("pending")?.let {
            Pending(FleetProtocol.base(it.getString("base")), FleetProtocol.identifier(it.getString("request_id")),
                it.getString("user_code").also { code -> require(code.length in 1..32 && code.none(Char::isISOControl)) },
                FleetProtocol.secret(it.getString("poll_token")), it.getLong("expires_at_ms"))
        }
        val credentials = value.optJSONObject("credentials")?.let(::parseCredentials)
        val starting = value.optJSONObject("starting")?.let {
            Starting(FleetProtocol.base(it.getString("base")), it.getString("device_name").also { name ->
                require(name.length in 1..100 && name.none(Char::isISOControl))
            }, FleetProtocol.secret(it.getString("poll_token")))
        }
        val rotation = value.optJSONObject("rotation")?.let {
            Rotation(FleetProtocol.identifier(it.getString("operation_id")), parseCredentials(it.getJSONObject("previous")))
        }
        Snapshot(FleetProtocol.identifier(value.getString("installation_id")), pending, credentials, starting, rotation)
    }

    fun startIdentity(): Snapshot = synchronized(lock) {
        load()?.let { return@synchronized it }
        check(!storage.failed()) { "Saved enrollment cannot be decrypted; ask your administrator" }
        Snapshot(UUID.randomUUID().toString()).also(::save)
    }
    fun prepareStart(base: String, deviceName: String): Snapshot = synchronized(lock) {
        val origin = FleetProtocol.base(base)
        require(deviceName.length in 1..100 && deviceName.none(Char::isISOControl))
        val current = startIdentity()
        check(current.credentials == null && (current.pending == null || current.pending.expiresAtMillis <= System.currentTimeMillis()))
        current.starting?.let {
            check(it.base == origin && it.deviceName == deviceName) { "Resume the existing pairing first" }
            return@synchronized current
        }
        val capability = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(capability)
        current.copy(starting = Starting(origin, deviceName, token)).also(::save)
    }
    fun savePending(pending: Pending): Snapshot = synchronized(lock) {
        val current = startIdentity()
        check(current.credentials == null) { "This device is already enrolled" }
        current.starting?.let { check(it.base == pending.base && it.pollToken == pending.pollToken) }
        current.copy(pending = pending, starting = null).also(::save)
    }
    fun approved(requestId: String, deviceId: String, hotelId: String, token: String): Snapshot = synchronized(lock) {
        val current = load() ?: error("Enrollment identity unavailable")
        val pending = current.pending ?: error("Enrollment request unavailable")
        check(pending.requestId == requestId) { "Enrollment request changed" }
        current.copy(pending = null, starting = null, credentials = Credentials(pending.base, FleetProtocol.identifier(deviceId),
            FleetProtocol.identifier(hotelId), FleetProtocol.secret(token), true)).also(::save)
    }
    fun activated(deviceId: String, token: String) = synchronized(lock) {
        val current = load() ?: error("Enrollment unavailable")
        val credentials = current.credentials ?: error("Device credential unavailable")
        check(credentials.deviceId == deviceId && credentials.token == token)
        save(current.copy(credentials = credentials.copy(activationPending = false), rotation = null))
    }
    fun prepareRotation(): Snapshot = synchronized(lock) {
        val current = load() ?: error("Enrollment unavailable")
        val credentials = current.credentials ?: error("Device credential unavailable")
        check(!credentials.activationPending) { "Complete activation before another rotation" }
        current.rotation?.let { return@synchronized current }
        current.copy(rotation = Rotation(UUID.randomUUID().toString(), credentials)).also(::save)
    }
    fun rotated(operationId: String, deviceId: String, token: String): Snapshot = synchronized(lock) {
        val current = load() ?: error("Enrollment unavailable")
        val rotation = current.rotation ?: error("Rotation was not saved")
        check(rotation.operationId == operationId && rotation.previous.deviceId == deviceId)
        val replacement = rotation.previous.copy(token = FleetProtocol.secret(token), activationPending = true)
        if (current.credentials == replacement) return@synchronized current
        check(current.credentials == rotation.previous) { "A different rotation is already pending activation" }
        current.copy(credentials = replacement).also(::save)
    }
    /** Only after the server rejects a pending replacement; old authorization is rechecked remotely. */
    fun recoverExpiredRotation(deviceId: String, token: String): Snapshot = synchronized(lock) {
        val current = load() ?: error("Enrollment unavailable")
        val rotation = current.rotation ?: error("No prior credential is available")
        val credentials = current.credentials ?: error("Replacement credential unavailable")
        check(credentials.deviceId == deviceId && credentials.token == token && credentials.activationPending)
        current.copy(credentials = rotation.previous,
            rotation = Rotation(UUID.randomUUID().toString(), rotation.previous)).also(::save)
    }
    fun retryRotation(operationId: String): Snapshot = synchronized(lock) {
        val current = load() ?: error("Enrollment unavailable")
        val rotation = current.rotation ?: error("Rotation unavailable")
        check(rotation.operationId == operationId && current.credentials == rotation.previous)
        current.copy(rotation = Rotation(UUID.randomUUID().toString(), rotation.previous)).also(::save)
    }
    private fun parseCredentials(value: JSONObject) = Credentials(FleetProtocol.base(value.getString("base")),
        FleetProtocol.identifier(value.getString("device_id")), FleetProtocol.identifier(value.getString("hotel_id")),
        FleetProtocol.secret(value.getString("token")), value.optBoolean("activation_pending", true))
    private fun credentialsJson(value: Credentials) = JSONObject().put("base", value.base).put("device_id", value.deviceId)
        .put("hotel_id", value.hotelId).put("token", value.token).put("activation_pending", value.activationPending)
    private fun save(value: Snapshot) {
        val json = JSONObject().put("installation_id", value.installationId)
        value.starting?.let { json.put("starting", JSONObject().put("base", it.base)
            .put("device_name", it.deviceName).put("poll_token", it.pollToken)) }
        value.pending?.let { json.put("pending", JSONObject().put("base", it.base).put("request_id", it.requestId)
            .put("user_code", it.userCode).put("poll_token", it.pollToken).put("expires_at_ms", it.expiresAtMillis)) }
        value.credentials?.let { json.put("credentials", credentialsJson(it)) }
        value.rotation?.let { json.put("rotation", JSONObject().put("operation_id", it.operationId).put("previous", credentialsJson(it.previous))) }
        check(storage.write(json.toString())) { "Enrollment could not be securely saved" }
    }
    companion object { private val lock = Any() }
}

/** Production adapter is SecretStore; fixtures exercise the durable enrollment protocol without Android. */
internal interface FleetSecretStorage {
    fun read(): String?
    fun write(value: String): Boolean
    fun failed(): Boolean
}
