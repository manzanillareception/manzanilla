package com.agentdeck.hotel

import android.content.Context

/**
 * The V2 fleet-management connection: base URL, installation identity and device credential
 * in native encrypted storage, replacing together. Separate from the legacy HotelConnection
 * (staff-led operations backend); neither entry reads or overwrites the other.
 */
class ManagementConnection(context: Context) {
    private val store = SecretStore(context, "management_connection")
    var errorMessage: String? = null
        private set

    fun load(): ManagementCredentials.Connection? {
        val value = store.load() ?: return null
        return ManagementCredentials.parse(value)
    }

    fun save(base: String, deviceId: String, credential: String): Boolean {
        val validBase = ManagementCredentials.validBase(base)
        val validCredential = ManagementCredentials.validCredential(credential)
        if (validBase == null || validCredential == null || !ManagementCredentials.validUuid(deviceId)) {
            errorMessage = "The server returned a connection this build cannot store. Retry enrollment; if it repeats, update the app."
            return false
        }
        val saved = store.save(ManagementCredentials.serialize(
            ManagementCredentials.Connection(validBase, deviceId, validCredential)))
        errorMessage = if (saved) null else store.errorMessage
        return saved
    }

    fun clear() = store.clear()
    fun status(): SecretStore.Status = store.status()
}
