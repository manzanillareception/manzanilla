package com.agentdeck.hotel

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Counts every unwritten byte, including the packet being written, against the speech backlog. */
internal class PcmPlaybackQueue(private val maximumBytes: Int, maximumPackets: Int = 512) {
    class Packet internal constructor(val epoch: Long, val pcm: ByteArray) {
        internal var remaining = pcm.size
    }

    private val lock = Any()
    private val packets = ArrayBlockingQueue<Packet>(maximumPackets)
    private var epoch = 0L
    private var pending = 0

    init { require(maximumBytes > 0 && maximumPackets > 0) }

    fun offer(pcm: ByteArray): Boolean = synchronized(lock) {
        if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > maximumBytes ||
            pending > maximumBytes - pcm.size) return@synchronized false
        val owned = pcm.copyOf()
        if (!packets.offer(Packet(epoch, owned))) {
            owned.fill(0)
            return@synchronized false
        }
        pending += owned.size
        true
    }

    @Throws(InterruptedException::class)
    fun poll(waitMillis: Long): Packet? = packets.poll(waitMillis, TimeUnit.MILLISECONDS)

    fun isCurrent(packet: Packet): Boolean = synchronized(lock) { packet.epoch == epoch }

    /** Successful native writes free their own bytes immediately, including a partial large chunk. */
    fun consumed(packet: Packet, count: Int) = synchronized(lock) {
        require(count >= 0 && count % 2 == 0 && count <= packet.remaining)
        packet.remaining -= count
        if (packet.epoch == epoch) pending -= count
    }

    fun complete(packet: Packet) {
        synchronized(lock) {
            if (packet.epoch == epoch) pending -= packet.remaining
            packet.remaining = 0
        }
        packet.pcm.fill(0)
    }

    fun clear() = synchronized(lock) {
        epoch++
        var packet = packets.poll()
        while (packet != null) {
            packet.pcm.fill(0)
            packet = packets.poll()
        }
        pending = 0
    }

    internal fun pendingBytes(): Int = synchronized(lock) { pending }
}
