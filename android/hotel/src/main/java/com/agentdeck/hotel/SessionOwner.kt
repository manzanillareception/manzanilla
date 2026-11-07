package com.agentdeck.hotel

/** One native owner survives permission, greeting, transport and release transitions. */
class SessionOwner {
    data class Request(val id: String, val mode: String, val guest: String, val staff: String, val greeting: String = "")
    data class Lease(val request: Request, val generation: Int)
    var current: Lease? = null
        private set
    var closing = false
        private set
    var audioUnknown = false
        private set
    private var generation = 0
    private var lastReleased: Lease? = null

    fun acquire(request: Request): Lease? {
        if (current != null || audioUnknown) return null
        require(request.mode == "translation" || request.mode == "reception")
        require(request.mode != "translation" || request.guest != request.staff)
        return Lease(request, ++generation).also { current = it; closing = false }
    }

    fun accepts(id: String, epoch: Int, duringClose: Boolean = false): Boolean {
        val lease = current ?: return false
        return lease.request.id == id && lease.generation == epoch && (duringClose || !closing)
    }


    /** A user can cancel the exact new UUID before the UI receives its generation. */
    fun acceptsCancellation(id: String, epoch: Int?): Boolean {
        val lease = current ?: return false
        return lease.request.id == id && (epoch == null || lease.generation == epoch)
    }


    fun beginClose(): Lease? {
        if (closing) return null
        val lease = current ?: return null
        closing = true
        return lease
    }

    /** The last lease whose local release may still be unknown. */
    val lastReleasedLease: Lease? get() = lastReleased
    fun released(lease: Lease, knownOff: Boolean): Boolean {
        if (current != lease || !closing) return false
        current = null
        closing = false
        audioUnknown = !knownOff
        lastReleased = lease
        return true
    }

    fun confirmLateRelease(id: String, epoch: Int): Boolean {
        val lease = lastReleased ?: return false
        if (current != null || !audioUnknown || lease.request.id != id || lease.generation != epoch) return false
        audioUnknown = false
        return true
    }
}
