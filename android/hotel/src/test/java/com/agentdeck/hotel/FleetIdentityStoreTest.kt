package com.agentdeck.hotel

import org.junit.Assert.*
import org.junit.Test

class FleetIdentityStoreTest {
    private val oldToken = "fixture-old-device-token-12345678901234567890"
    private val newToken = "fixture-new-device-token-12345678901234567890"
    private class Saved : FleetSecretStorage {
        var value: String? = null
        var error = false
        var writable = true
        override fun read() = value
        override fun failed() = error
        override fun write(value: String): Boolean {
            if (!writable) return false
            this.value = value
            return true
        }
    }
    @Test fun lostFirstResponseReusesTheDurablySavedInstallationAndCapability() {
        val saved = Saved()
        val first = FleetIdentityStore(saved).prepareStart("https://fixture.test", "Reception fixture")
        assertTrue(first.starting!!.pollToken.length >= 32)
        // Network response is lost here. Reopening must not create another request identity.
        val afterCrash = FleetIdentityStore(saved).prepareStart("https://fixture.test", "Reception fixture")
        assertEquals(first.installationId, afterCrash.installationId)
        assertEquals(first.starting, afterCrash.starting)
        assertThrows(IllegalStateException::class.java) {
            FleetIdentityStore(saved).prepareStart("https://other.test", "Reception fixture")
        }
    }
    @Test fun approvedCredentialIsSavedBeforeActivationAndSurvivesLostActivationReply() {
        val saved = Saved()
        val store = FleetIdentityStore(saved)
        val starting = store.prepareStart("https://fixture.test", "Reception fixture").starting!!
        store.savePending(FleetIdentityStore.Pending(starting.base, "request_1", "CODE1234", starting.pollToken, Long.MAX_VALUE))
        store.approved("request_1", "device_1", "hotel_1", "fixture-only-token-12345678901234567890")
        val recovered = FleetIdentityStore(saved)
        val credentials = recovered.load()!!.credentials!!
        assertTrue(credentials.activationPending)
        assertNull(recovered.load()!!.pending)
        recovered.activated(credentials.deviceId, credentials.token)
        assertFalse(FleetIdentityStore(saved).load()!!.credentials!!.activationPending)
    }
    @Test fun storageFailureCannotDisplayAnUnsavedPairingOrReplaceCorruptIdentity() {
        val saved = Saved()
        val store = FleetIdentityStore(saved)
        store.startIdentity()
        val before = saved.value
        saved.writable = false
        assertThrows(IllegalStateException::class.java) { store.prepareStart("https://fixture.test", "Fixture") }
        assertEquals(before, saved.value)
        val unreadable = Saved().apply { error = true }
        assertThrows(IllegalStateException::class.java) { FleetIdentityStore(unreadable).startIdentity() }
        assertNull(unreadable.value)
    }
    @Test fun aMismatchedPairingResponseCannotReplaceTheSavedCapability() {
        val saved = Saved()
        val store = FleetIdentityStore(saved)
        store.prepareStart("https://fixture.test", "Fixture")
        assertThrows(IllegalStateException::class.java) {
            store.savePending(FleetIdentityStore.Pending("https://fixture.test", "request_1", "CODE1234",
                "different-capability-12345678901234567890", Long.MAX_VALUE))
        }
        assertNotNull(FleetIdentityStore(saved).load()!!.starting)
        assertNull(FleetIdentityStore(saved).load()!!.pending)
    }
    private fun enrolled(saved: Saved): FleetIdentityStore {
        val store = FleetIdentityStore(saved)
        val starting = store.prepareStart("https://fixture.test", "Fixture").starting!!
        store.savePending(FleetIdentityStore.Pending(starting.base, "request_1", "CODE1234", starting.pollToken, Long.MAX_VALUE))
        store.approved("request_1", "device_1", "hotel_1", oldToken)
        store.activated("device_1", oldToken)
        return store
    }
    @Test fun rotationOperationSurvivesLostReplyAndRetainsThePriorCredentialUntilActivation() {
        val saved = Saved()
        val store = enrolled(saved)
        val operation = store.prepareRotation().rotation!!
        val reopened = FleetIdentityStore(saved)
        assertEquals(operation, reopened.prepareRotation().rotation)
        assertEquals(oldToken, reopened.load()!!.credentials!!.token)
        reopened.rotated(operation.operationId, "device_1", newToken)
        // Setup and a just-paused reporter can receive the same committed reply.
        assertEquals(newToken, reopened.rotated(operation.operationId, "device_1", newToken).credentials!!.token)
        val afterLostActivationReply = FleetIdentityStore(saved).load()!!
        assertEquals(newToken, afterLostActivationReply.credentials!!.token)
        assertTrue(afterLostActivationReply.credentials.activationPending)
        assertEquals(oldToken, afterLostActivationReply.rotation!!.previous.token)
        reopened.activated("device_1", newToken)
        assertEquals(newToken, reopened.load()!!.credentials!!.token)
        assertFalse(reopened.load()!!.credentials!!.activationPending)
        assertNull(reopened.load()!!.rotation)
        assertFalse(saved.value!!.contains(oldToken))
    }
    @Test fun failedReplacementPersistenceLeavesTheWorkingCredentialAndRetryIdentityIntact() {
        val saved = Saved()
        val store = enrolled(saved)
        val operation = store.prepareRotation().rotation!!
        saved.writable = false
        assertThrows(IllegalStateException::class.java) { store.rotated(operation.operationId, "device_1", newToken) }
        assertEquals(oldToken, store.load()!!.credentials!!.token)
        assertEquals(operation, store.load()!!.rotation)
        saved.writable = true
        store.rotated(operation.operationId, "device_1", newToken)
        assertEquals(newToken, store.load()!!.credentials!!.token)
    }
    @Test fun rejectedExpiredReplacementCanRetryWithOldAuthorizationAndAFreshOperation() {
        val saved = Saved()
        val store = enrolled(saved)
        val operation = store.prepareRotation().rotation!!
        store.rotated(operation.operationId, "device_1", newToken)
        assertThrows(IllegalStateException::class.java) { store.recoverExpiredRotation("other_device", newToken) }
        val recovered = store.recoverExpiredRotation("device_1", newToken)
        assertEquals("hotel_1", recovered.credentials!!.hotelId)
        assertEquals(oldToken, recovered.credentials.token)
        assertFalse(recovered.credentials.activationPending)
        assertNotEquals(operation.operationId, recovered.rotation!!.operationId)
    }
    @Test fun expiredUnreturnedRotationGetsANewOperationWithoutChangingEnrollment() {
        val saved = Saved()
        val store = enrolled(saved)
        val operation = store.prepareRotation().rotation!!
        val retry = store.retryRotation(operation.operationId)
        assertNotEquals(operation.operationId, retry.rotation!!.operationId)
        assertEquals(oldToken, retry.credentials!!.token)
        assertEquals("device_1", retry.credentials.deviceId)
    }
}
