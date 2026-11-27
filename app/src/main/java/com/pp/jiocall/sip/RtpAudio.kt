package com.pp.jiocall.sip

import android.content.Context
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Clean, modular full-duplex RTP voice engine modeled on PJSIP, WebRTC,
 * Baresip, and RusTone.
 *
 * Delegates platform responsibilities to focused single-purpose components:
 * - [CallAudioController]: FGS microphone barrier, AudioFocus, async
 *   `OnModeChangedListener`, and Earpiece / Speaker / Wired / Bluetooth routing
 * - [AudioPlaybackEngine]: low-latency downlink AudioTrack + adaptive jitter buffer
 * - [AudioCaptureEngine]: URGENT_AUDIO capture + HW-only effects + dead-mic fallback
 * - [AmrEncoder] / [AmrDecoder] / [AmrFraming]: RFC 4867 AMR-WB/NB with Codec2
 * - [CallRecorder]: asynchronous soft-knee WAV call recording
 */
class RtpAudio(
    private val context: Context,
    private val preferredIp: String = "",
) {
    private var socket: DatagramSocket? = createSocket(preferIpv6 = isIpv6(preferredIp))
    @Volatile private var boundIpv6: Boolean = isIpv6(preferredIp)

    val port: Int get() = socket?.localPort ?: 0
    @Volatile var amrOctetAligned: Boolean = false
        private set

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val remotePaused = AtomicBoolean(false)
    private val speaker = AtomicBoolean(false)
    private val txLock = Any()

    private var remoteAddr: InetAddress? = null
    private var remotePort: Int = 0
    private var lastRxSsrc: Long = -1L
    private var txCodec: Codec = Codec.AMR_WB
    private var txPayloadType: Int = 96
    private var txOctetAligned: Boolean = false
    private var txEncoder: AmrEncoder? = null
    private var rxPayloadMap: Map<Int, Codec> = emptyMap()
    private var rxOctetAlignedMap: Map<Int, Boolean> = emptyMap()

    private data class SessionSignature(
        val remoteHost: String,
        val remotePort: Int,
        val codec: Codec,
        val payloadType: Int,
        val octetAligned: Boolean,
        val modeSet: Set<Int>?,
    )

    private var activeSignature: SessionSignature? = null
    private var dtmfPt: Int? = 101
    private var dtmfRate: Int = 8_000

    private val ssrc: Long = Random.nextLong(1L, 0x7FFF_FFFFL)
    private var txSeq: Int = Random.nextInt(1, 0xFFFF)
    private var txTimestamp: Long = Random.nextLong(1L, 0x7FFF_FFFFL)
    private val packetsTx = AtomicLong(0L)
    private val packetsRx = AtomicLong(0L)
    private val startedAtMs = AtomicLong(0L)
    @Volatile private var lastTxPeak: Int = 0

    private data class QueuedTxFrame(val pcm: ShortArray, val muted: Boolean)

    private val jitterBuffer = RtpJitterBuffer()
    private val txFrameQueue = ArrayBlockingQueue<QueuedTxFrame>(16)
    private val txFreeQueue = ArrayBlockingQueue<ShortArray>(16)
    private var audioController: CallAudioController? = null
    private var playbackEngine: AudioPlaybackEngine? = null
    private var captureEngine: AudioCaptureEngine? = null
    private var txThread: Thread? = null
    private var rxThread: Thread? = null

    @Volatile private var callRecorder: CallRecorder? = null
    val isRecording: Boolean get() = callRecorder != null

    /**
     * Rebinds the RTP socket if the remote SDP peer uses IPv6 and we currently hold IPv4 (or vice-versa).
     */
    fun prepareFor(remoteHost: String) {
        if (running.get()) return
        val wantIpv6 = isIpv6(remoteHost)
        if (socket != null && boundIpv6 == wantIpv6) return
        runCatching { socket?.close() }
        socket = createSocket(preferIpv6 = wantIpv6)
        boundIpv6 = wantIpv6
        Log.i(TAG, "Prepared RTP socket for ${if (wantIpv6) "IPv6" else "IPv4"} port=$port")
    }

    fun preferredLocalAddress(): String? = runCatching {
        val wantIpv6 = boundIpv6
        val sipIp = preferredIp.trim().substringBefore('%')
        if (sipIp.isNotEmpty() && sipIp != "0.0.0.0" && isIpv6(sipIp) == wantIpv6) {
            if (!wantIpv6 || (!sipIp.startsWith("fe80", true) && !sipIp.startsWith("fec0", true))) {
                return sipIp
            }
        }
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                if (wantIpv6 && addr is Inet6Address && isRoutableIpv6(addr)) {
                    return addr.hostAddress?.substringBefore('%')
                }
                if (!wantIpv6 && addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    fun advertisedAddress(fallback: String): String {
        val clean = fallback.trim()
        if (!boundIpv6 && clean.isNotEmpty() && !isIpv6(clean) && clean != "0.0.0.0") {
            return clean
        }
        if (boundIpv6 && clean.isNotEmpty() && isIpv6(clean) && !clean.startsWith("fe80", true) && !clean.startsWith("fec0", true)) {
            return clean
        }
        return preferredLocalAddress()?.takeIf { it.isNotBlank() } ?: clean.ifBlank { "0.0.0.0" }
    }

    fun setDtmfParams(pt: Int?, rate: Int) {
        dtmfPt = pt?.takeIf { it in 96..127 }
        if (rate > 0) dtmfRate = rate
    }

    fun start(
        remoteHost: String,
        remotePort: Int,
        codec: Codec,
        payloadType: Int,
        octetAligned: Boolean,
        speakerphone: Boolean,
        echoCancellation: Boolean,
        rxCodecs: Map<Int, Codec> = mapOf(payloadType to codec),
        rxOctetAligned: Map<Int, Boolean> = mapOf(payloadType to octetAligned),
        modeSet: Set<Int>? = null,
        explicitOctetAligned: Boolean = false,
    ) {
        val resolved = runCatching { InetAddress.getByName(remoteHost) }.getOrNull()
        if (resolved == null || remotePort <= 0) {
            Log.e(TAG, "Invalid remote media target $remoteHost:$remotePort")
            return
        }

        // Guarantee outgoing ringback AudioTrack is stopped before live audio starts
        ToneGenerator.stopRingback()

        val newSig = SessionSignature(remoteHost, remotePort, codec, payloadType, octetAligned, modeSet)
        if (running.get()) {
            this.rxPayloadMap = rxCodecs
            this.rxOctetAlignedMap = rxOctetAligned
            setSpeaker(speakerphone)
            if (newSig == activeSignature && socket != null) {
                Log.d(TAG, "RTP session unchanged on re-negotiation ($remoteHost:$remotePort pt=$payloadType); keeping active audio streams")
                return
            }
            Log.i(TAG, "Reconfiguring active RTP session without closing socket ($activeSignature -> $newSig)")
            stopStreamsOnly()
        }

        if (socket == null || socket?.isClosed == true) {
            socket = createSocket(preferIpv6 = isIpv6(remoteHost))
            boundIpv6 = isIpv6(remoteHost)
        }

        activeSignature = newSig
        running.set(true)
        this.remoteAddr = resolved
        this.remotePort = remotePort
        this.txCodec = codec
        this.txPayloadType = payloadType
        this.txOctetAligned = octetAligned
        this.amrOctetAligned = octetAligned
        this.rxPayloadMap = rxCodecs
        this.rxOctetAlignedMap = rxOctetAligned
        this.speaker.set(speakerphone)
        this.lastRxSsrc = -1L
        this.jitterBuffer.reset()
        this.packetsTx.set(0L)
        this.packetsRx.set(0L)
        this.lastTxPeak = 0
        this.startedAtMs.set(System.currentTimeMillis())

        val ctrl = audioController ?: CallAudioController(context).also {
            audioController = it
            it.acquireSession(speakerphone)
        }
        ctrl.setSpeaker(speakerphone)

        if (codec.isAmr) {
            txEncoder = runCatching { AmrEncoder(codec, modeSet) }
                .onFailure { Log.e(TAG, "Failed creating TX ${codec.mime} encoder: ${it.message}") }
                .getOrNull()
        } else {
            txEncoder = null
        }

        // Send symmetric-RTP hole-punch packet immediately so SBC opens NAT pinhole cleanly
        sendHolePunchPackets(resolved, remotePort, codec, payloadType, octetAligned)

        // Start playback engine first so downlink USAGE_VOICE_COMMUNICATION stream is active for HW AEC
        val playback = AudioPlaybackEngine(
            primaryCodec = codec,
            jitterBuffer = jitterBuffer,
            isRunning = { running.get() },
            isSpeakerOn = { speaker.get() },
            onDecodedPcm = { pcm, rate -> callRecorder?.writeRx(pcm, rate) },
        ).also { playbackEngine = it }
        playback.start()

        // Start UDP receiver thread
        rxThread = Thread(::receiveLoop, "JioCall-RTP-RX").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        // Start dedicated TX encode/send thread decoupled from AudioRecord HAL read thread
        txFrameQueue.clear()
        txFreeQueue.clear()
        txThread = Thread(::encodeAndSendLoop, "JioCall-TX-Encode").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        // Start microphone capture engine
        val capture = AudioCaptureEngine(
            context = context,
            codec = codec,
            echoCancellation = echoCancellation,
            isRunning = { running.get() },
            isMuted = { muted.get() },
            isRemotePaused = { remotePaused.get() },
            onEncodedFrame = ::enqueueCapturedFrame,
        ).also { captureEngine = it }
        capture.start()

        Log.i(
            TAG,
            "Media started -> $remoteHost:$remotePort localPort=$port codec=${codec.mime} pt=$payloadType oa=$octetAligned explicitOa=$explicitOctetAligned modeSet=${modeSet ?: "any"} speaker=$speakerphone aec=$echoCancellation",
        )
    }

    fun setMuted(muted: Boolean) {
        this.muted.set(muted)
    }

    fun setRemotePaused(paused: Boolean) {
        this.remotePaused.set(paused)
    }

    fun setSpeaker(speakerphone: Boolean) {
        speaker.set(speakerphone)
        audioController?.setSpeaker(speakerphone)
    }

    fun sendDtmf(digit: Char): Boolean {
        val code = when (digit) {
            in '0'..'9' -> digit - '0'
            '*' -> 10
            '#' -> 11
            'A', 'a' -> 12
            'B', 'b' -> 13
            'C', 'c' -> 14
            'D', 'd' -> 15
            else -> return false
        }
        val pt = dtmfPt ?: return false
        val addr = remoteAddr ?: return false
        val targetPort = remotePort.takeIf { it > 0 } ?: return false
        if (!running.get()) return false

        Thread({
            val durationStep = (dtmfRate / 50).coerceAtLeast(160) // 20ms per packet
            val baseTs: Long
            synchronized(txLock) {
                baseTs = txTimestamp
            }
            for (i in 0..4) {
                val isEnd = i == 4
                val dur = ((i + 1) * durationStep).coerceAtMost(0xFFFF)
                val payload = byteArrayOf(
                    code.toByte(),
                    (if (isEnd) 0x8A else 0x0A).toByte(),
                    ((dur ushr 8) and 0xFF).toByte(),
                    (dur and 0xFF).toByte(),
                )
                sendRtpPacket(addr, targetPort, pt, baseTs, payload, marker = i == 0)
                if (i < 4) runCatching { Thread.sleep(20L) }
            }
        }, "JioCall-DTMF-TX").start()
        return true
    }

    fun startRecording(outputFile: File): Boolean {
        if (callRecorder != null) return true
        val rec = CallRecorder(outputFile, txCodec.clockRate)
        if (!rec.start()) return false
        callRecorder = rec
        return true
    }

    fun stopRecording(): File? {
        val rec = callRecorder
        callRecorder = null
        return rec?.stop()
    }

    fun getQuality(): CallQuality {
        val rx = jitterBuffer.totalReceived
        val lost = jitterBuffer.totalLost
        val total = rx + lost
        val lossPct = if (total == 0L) 0f else (lost * 100f / total.toFloat()).coerceIn(0f, 100f)
        val mos = if (total == 0L && packetsTx.get() == 0L) 4.4f else QualityEstimator.calculateMos(rx.coerceAtLeast(1), lost)
        return CallQuality(
            codec = txCodec.mime,
            clockRate = txCodec.clockRate,
            isHdVoice = txCodec == Codec.AMR_WB,
            mos = mos,
            packetsRx = rx,
            packetsTx = packetsTx.get(),
            lossPercent = lossPct,
        )
    }

    private fun stopStreamsOnly() {
        running.set(false)
        captureEngine?.stop()
        captureEngine = null

        val txt = txThread
        txThread = null
        txFrameQueue.clear()
        txFreeQueue.clear()
        if (txt != null && Thread.currentThread() !== txt) {
            runCatching { txt.join(350L) }
        }

        playbackEngine?.stop()
        playbackEngine = null

        val rxt = rxThread
        rxThread = null
        if (rxt != null && Thread.currentThread() !== rxt) {
            runCatching { rxt.join(350L) }
        }

        synchronized(txLock) {
            txEncoder?.release()
            txEncoder = null
        }
    }

    fun stop() {
        stopRecording()
        stopStreamsOnly()
        activeSignature = null

        runCatching { socket?.close() }
        socket = null

        audioController?.release()
        audioController = null
        Log.i(TAG, "Media stopped cleanly (tx=${packetsTx.get()}, rx=${packetsRx.get()}, lost=${jitterBuffer.totalLost})")
    }

    private fun enqueueCapturedFrame(pcm: ShortArray, isMutedFrame: Boolean) {
        if (!running.get()) return
        val pooled = txFreeQueue.poll()
        val copy = if (pooled != null && pooled.size == pcm.size) {
            System.arraycopy(pcm, 0, pooled, 0, pcm.size)
            pooled
        } else {
            pcm.copyOf()
        }
        val frame = QueuedTxFrame(copy, isMutedFrame)
        if (!txFrameQueue.offer(frame)) {
            val dropped = txFrameQueue.poll()
            if (dropped != null) txFreeQueue.offer(dropped.pcm)
            txFrameQueue.offer(frame)
        }
    }

    private fun encodeAndSendLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rtpBuf = ByteArray(1500)
        val rtpPkt = DatagramPacket(rtpBuf, rtpBuf.size)
        while (running.get()) {
            val item = runCatching { txFrameQueue.poll(40L, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            try {
                processAndSendFrame(item.pcm, item.muted, rtpBuf, rtpPkt)
            } finally {
                txFreeQueue.offer(item.pcm)
            }
        }
    }

    private fun processAndSendFrame(
        pcm: ShortArray,
        isMutedFrame: Boolean,
        rtpBuf: ByteArray,
        rtpPkt: DatagramPacket,
    ) {
        if (!running.get()) return
        val addr = remoteAddr ?: return
        val targetPort = remotePort.takeIf { it > 0 } ?: return

        if (!isMutedFrame) {
            callRecorder?.writeTx(pcm, txCodec.clockRate)
            lastTxPeak = maxOf(lastTxPeak, AudioGain.peakAmplitude(pcm))
        }

        val payloads: List<ByteArray> = when (txCodec) {
            Codec.AMR_WB, Codec.AMR_NB -> {
                val frames = synchronized(txLock) { txEncoder?.encodeFrames(pcm).orEmpty() }
                frames.map { frame ->
                    if (txOctetAligned) {
                        AmrFraming.toOctetAligned(frame)
                    } else {
                        AmrFraming.toBandwidthEfficient(frame, txCodec == Codec.AMR_WB)
                    }
                }
            }
            Codec.PCMA -> listOf(ByteArray(pcm.size).also { G711Codec.encodeAlaw(pcm, it) })
            Codec.PCMU -> listOf(ByteArray(pcm.size).also { G711Codec.encodeUlaw(pcm, it) })
        }

        for (payload in payloads) {
            val ts: Long
            synchronized(txLock) {
                ts = txTimestamp
                txTimestamp = (txTimestamp + txCodec.samplesPerFrame) and 0xFFFF_FFFFL
            }
            val count = packetsTx.incrementAndGet()
            sendRtpPacket(addr, targetPort, txPayloadType, ts, payload, marker = count == 1L, rtpBuf = rtpBuf, rtpPkt = rtpPkt)
            if (count <= 2L || count % 250L == 0L) {
                Log.i(TAG, "TX RTP: $count pkts (${txCodec.mime}, oa=$txOctetAligned, maxAmp=$lastTxPeak) -> $addr:$targetPort")
                lastTxPeak = 0
            }
        }
    }

    private fun receiveLoop() {
        val sock = socket ?: return
        val buf = ByteArray(1500)
        val dp = DatagramPacket(buf, buf.size)
        runCatching { sock.soTimeout = 250 }

        while (running.get()) {
            try {
                dp.length = buf.size
                sock.receive(dp)
                val len = dp.length
                if (len < 12) continue

                val version = (buf[0].toInt() and 0xC0) ushr 6
                if (version != 2) continue

                // Symmetric RTP latching for carrier/router NAT compatibility
                if (dp.port > 0 && (dp.address != remoteAddr || dp.port != remotePort)) {
                    remoteAddr = dp.address
                    remotePort = dp.port
                }

                val hasExt = (buf[0].toInt() and 0x10) != 0
                val csrcCount = buf[0].toInt() and 0x0F
                var headerLen = 12 + csrcCount * 4
                if (headerLen > len) continue
                if (hasExt) {
                    if (headerLen + 4 > len) continue
                    val extWords = ((buf[headerLen + 2].toInt() and 0xFF) shl 8) or (buf[headerLen + 3].toInt() and 0xFF)
                    headerLen += 4 + extWords * 4
                }
                val hasPad = (buf[0].toInt() and 0x20) != 0
                val endOffset = if (hasPad && len > headerLen) {
                    val padLen = buf[len - 1].toInt() and 0xFF
                    if (padLen in 1..(len - headerLen)) len - padLen else len
                } else {
                    len
                }
                val payloadLen = endOffset - headerLen
                if (payloadLen <= 0) continue

                val pt = buf[1].toInt() and 0x7F
                if (pt == dtmfPt) continue // RFC 2833 DTMF event packet; skip speech decode

                val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                val ts = ((buf[4].toLong() and 0xFF) shl 24) or
                    ((buf[5].toLong() and 0xFF) shl 16) or
                    ((buf[6].toLong() and 0xFF) shl 8) or
                    (buf[7].toLong() and 0xFF)
                val rxSsrc = ((buf[8].toLong() and 0xFF) shl 24) or
                    ((buf[9].toLong() and 0xFF) shl 16) or
                    ((buf[10].toLong() and 0xFF) shl 8) or
                    (buf[11].toLong() and 0xFF)
                if (lastRxSsrc != -1L && rxSsrc != lastRxSsrc) {
                    Log.i(TAG, "Remote RTP SSRC changed ($lastRxSsrc -> $rxSsrc); resetting jitter buffer cleanly")
                    jitterBuffer.reset()
                }
                lastRxSsrc = rxSsrc

                val codec = rxPayloadMap[pt] ?: txCodec
                val rxCount = packetsRx.incrementAndGet()
                if (rxCount == 1L || rxCount % 250L == 0L) {
                    Log.i(TAG, "RX RTP: $rxCount pkts (pt=$pt codec=${codec.mime} len=$payloadLen lost=${jitterBuffer.totalLost})")
                }

                if (codec.isAmr) {
                    val oa = rxOctetAlignedMap[pt] ?: txOctetAligned
                    val unpacked = AmrFraming.unpackRtpFrames(buf, headerLen, payloadLen, codec == Codec.AMR_WB, oa)
                        ?: continue
                    if (unpacked.cmr in AmrEncoder.validModes(codec)) {
                        txEncoder?.requestCmr(unpacked.cmr)
                    }
                    val frames = unpacked.frames
                    for (idx in frames.indices) {
                        jitterBuffer.push(
                            RtpJitterBuffer.Packet(
                                seq = (seq + idx) and 0xFFFF,
                                timestamp = (ts + idx.toLong() * codec.samplesPerFrame) and 0xFFFF_FFFFL,
                                codec = codec,
                                frame = frames[idx],
                            ),
                        )
                    }
                } else {
                    val raw = buf.copyOfRange(headerLen, endOffset)
                    jitterBuffer.push(RtpJitterBuffer.Packet(seq = seq, timestamp = ts, codec = codec, frame = raw))
                }
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun sendHolePunchPackets(
        addr: InetAddress,
        port: Int,
        codec: Codec,
        payloadType: Int,
        octetAligned: Boolean,
    ) {
        val payload = when {
            codec.isAmr && octetAligned -> byteArrayOf(0xF0.toByte(), 0x78)
            codec.isAmr -> byteArrayOf(0xF7.toByte(), 0x80.toByte())
            else -> ByteArray(160) { 0xD5.toByte() }
        }
        sendRtpPacket(addr, port, payloadType, txTimestamp, payload, marker = true)
    }

    private fun sendRtpPacket(
        addr: InetAddress,
        port: Int,
        payloadType: Int,
        timestamp: Long,
        payload: ByteArray,
        marker: Boolean = false,
        rtpBuf: ByteArray? = null,
        rtpPkt: DatagramPacket? = null,
    ) {
        val sock = socket ?: return
        val seq: Int
        synchronized(txLock) {
            seq = txSeq and 0xFFFF
            txSeq = (txSeq + 1) and 0xFFFF
        }
        val totalLen = 12 + payload.size
        val pkt = if (rtpBuf != null && totalLen <= rtpBuf.size) rtpBuf else ByteArray(totalLen)
        pkt[0] = 0x80.toByte()
        pkt[1] = ((if (marker) 0x80 else 0x00) or (payloadType and 0x7F)).toByte()
        pkt[2] = ((seq ushr 8) and 0xFF).toByte()
        pkt[3] = (seq and 0xFF).toByte()
        pkt[4] = ((timestamp ushr 24) and 0xFF).toByte()
        pkt[5] = ((timestamp ushr 16) and 0xFF).toByte()
        pkt[6] = ((timestamp ushr 8) and 0xFF).toByte()
        pkt[7] = (timestamp and 0xFF).toByte()
        pkt[8] = ((ssrc ushr 24) and 0xFF).toByte()
        pkt[9] = ((ssrc ushr 16) and 0xFF).toByte()
        pkt[10] = ((ssrc ushr 8) and 0xFF).toByte()
        pkt[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, 12, payload.size)
        val datagram = if (rtpPkt != null && pkt === rtpBuf) {
            rtpPkt.setData(pkt, 0, totalLen)
            rtpPkt.address = addr
            rtpPkt.port = port
            rtpPkt
        } else {
            DatagramPacket(pkt, totalLen, addr, port)
        }
        runCatching { sock.send(datagram) }
    }

    companion object {
        private const val TAG = "RtpAudio"

        private fun createSocket(preferIpv6: Boolean): DatagramSocket? {
            if (preferIpv6) {
                runCatching {
                    return DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("::"), 0))
                    }
                }
            }
            return runCatching { DatagramSocket(0) }.getOrNull()
        }

        private fun isIpv6(host: String): Boolean = host.contains(':')

        private fun isRoutableIpv6(addr: Inet6Address): Boolean {
            if (addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isLoopbackAddress ||
                addr.isMulticastAddress || addr.isAnyLocalAddress
            ) {
                return false
            }
            val bytes = addr.address
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            // Exclude deprecated fec0::/10 site-local and fc00::/7 unique-local (emulators use fec0::)
            if (first == 0xFE && (second and 0xC0) == 0xC0) return false
            if ((first and 0xFE) == 0xFC) return false
            return true
        }

        fun isExternalCommDeviceType(type: Int): Boolean =
            CallAudioController.isExternalCommDeviceType(type)

        fun selectTargetDeviceType(availableTypes: Collection<Int>, speakerOn: Boolean): Int? =
            CallAudioController.selectTargetDeviceType(availableTypes, speakerOn)

        fun amrToBandwidthEfficient(frame: ByteArray, isWideband: Boolean = true): ByteArray =
            AmrFraming.toBandwidthEfficient(frame, isWideband)

        fun bandwidthEfficientToAmr(
            data: ByteArray,
            offset: Int,
            length: Int,
            isWideband: Boolean,
        ): ByteArray? = AmrFraming.fromBandwidthEfficient(data, offset, length, isWideband)

        fun amrFrame(
            data: ByteArray,
            offset: Int,
            length: Int,
            isWideband: Boolean,
            octetAligned: Boolean,
        ): ByteArray? = AmrFraming.extractFrame(data, offset, length, isWideband, octetAligned)

        fun buildCaptureCandidates(
            codecRate: Int,
            nativeRate: Int = 48_000,
            preferHardwareAec: Boolean = true,
        ): List<CaptureCandidate> =
            AudioCaptureEngine.buildCaptureCandidates(codecRate, nativeRate, preferHardwareAec)
    }
}
