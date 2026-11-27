package com.pp.jiocall.sip

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Direction of a captured SIP packet.
 */
enum class SipDirection {
    TX, // Sent over wire
    RX, // Received from wire
}

/**
 * One SIP message captured on the wire for in-app diagnostics.
 * Directly corresponds to RusTone's `sip_engine::debug::SipMessage`.
 */
data class SipLogPacket(
    val id: Long,
    val timestamp: Long,
    val direction: SipDirection,
    val transport: String,
    val remote: String,
    val text: String,
) {
    val firstLine: String get() = text.lines().firstOrNull()?.trim().orEmpty()

    val formattedTime: String get() {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

/**
 * Thread-safe ring buffer storing recent SIP packets in memory.
 */
object SipLogBuffer {
    private const val MAX_SIZE = 1000
    private var seq = 0L
    private val lock = Any()
    private val buffer = ArrayDeque<SipLogPacket>(MAX_SIZE)
    private val changeVersion = AtomicLong(0L)

    val version: Long get() = changeVersion.get()

    fun log(direction: SipDirection, transport: String, remote: String, text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (buffer.size >= MAX_SIZE) {
                buffer.removeFirst()
            }
            seq++
            buffer.addLast(
                SipLogPacket(
                    id = seq,
                    timestamp = System.currentTimeMillis(),
                    direction = direction,
                    transport = transport,
                    remote = remote,
                    text = text,
                )
            )
            changeVersion.incrementAndGet()
        }
    }

    fun getLogs(): List<SipLogPacket> = synchronized(lock) {
        buffer.toList()
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        changeVersion.incrementAndGet()
    }
}
