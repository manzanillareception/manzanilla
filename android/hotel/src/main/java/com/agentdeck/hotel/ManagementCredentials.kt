package com.agentdeck.hotel

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * Android-free validation and serialization for the V2 fleet-management connection.
 * Mirrors the HotelConnection rules: HTTPS base without userinfo/query/fragment and a
 * 32..4096 character credential without whitespace or control characters.
 */
object ManagementCredentials {
    data class Connection(val base: String, val deviceId: String, val credential: String)

    val BUILD = Regex("^[0-9]{1,4}(\\.[0-9]{1,4}){0,3}$")
    val IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$")
    private val UUID_TEXT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun validBase(base: String): String? {
        val url = base.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/')
    }

    fun validCredential(credential: String): String? {
        val value = credential.trim()
        if (value.length !in 32..4096 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        return value
    }

    fun validUuid(value: String): Boolean = UUID_TEXT.matches(value)
    fun validBuild(build: String): Boolean = BUILD.matches(build)
    fun validIdentifier(value: String): Boolean = IDENTIFIER.matches(value) && value.length in 1..96
    fun validLabel(label: String): String? = label.trim().takeIf { it.length in 1..80 }
    fun validSetupToken(value: String): String? = value.trim()
        .takeIf { it.length in 8..128 && it.none { c -> c.isWhitespace() || c.isISOControl() } }
    fun validEmail(email: String): Boolean =
        email.length in 3..254 && email.none { it.isWhitespace() || it.isISOControl() } &&
            email.count { it == '@' } == 1

    fun serialize(connection: Connection): String = JSONObject()
        .put("base", connection.base)
        .put("deviceId", connection.deviceId)
        .put("credential", connection.credential)
        .toString()

    fun parse(value: String): Connection? { return try {
        val json = JSONObject(value)
        val base = validBase(json.getString("base")) ?: return null
        val deviceId = json.getString("deviceId")
        if (!validUuid(deviceId)) return null
        val credential = validCredential(json.getString("credential")) ?: return null
        Connection(base, deviceId, credential)
    } catch (_: Exception) { null } }
}
