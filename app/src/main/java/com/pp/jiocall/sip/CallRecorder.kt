package com.pp.jiocall.sip

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Asynchronous 16-bit mono WAV call recorder mixing local TX mic and remote RX
 * playout via soft-knee summing on a dedicated low-priority writer thread so
 * disk I/O never stalls real-time RTP threads.
 */
class CallRecorder(
    private val outputFile: File,
    private val sampleRate: Int,
) {
    private data class MixChunk(val isRx: Boolean, val pcm: ShortArray)

    private val frameSamples = sampleRate / 50
    private val rxQueue = ArrayDeque<ShortArray>(8)
    private val txQueue = ArrayDeque<ShortArray>(8)
    private val writeQueue = ArrayBlockingQueue<MixChunk>(64)
    private val running = AtomicBoolean(false)
    private var writerThread: Thread? = null
    @Volatile private var totalDataBytes: Long = 0L

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val parent = outputFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        writerThread = Thread({
            var raf: RandomAccessFile? = null
            try {
                raf = RandomAccessFile(outputFile, "rw")
                raf.setLength(0L)
                raf.write(buildWavHeader(0L, sampleRate))
                val byteBuf = ByteArray(frameSamples * 2)
                var dataBytes = 0L

                while (running.get() || writeQueue.isNotEmpty()) {
                    val item = writeQueue.poll(50L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (item != null) {
                        if (item.isRx) {
                            if (rxQueue.size >= 8) rxQueue.removeFirst()
                            rxQueue.addLast(item.pcm)
                        } else {
                            if (txQueue.size >= 8) txQueue.removeFirst()
                            txQueue.addLast(item.pcm)
                        }
                    }

                    while (rxQueue.isNotEmpty() && txQueue.isNotEmpty()) {
                        val rx = rxQueue.removeFirst()
                        val tx = txQueue.removeFirst()
                        dataBytes += writeMixedFrame(raf, rx, tx, byteBuf)
                    }
                    if (!running.get() && item == null) {
                        while (rxQueue.isNotEmpty() || txQueue.isNotEmpty()) {
                            val rx = if (rxQueue.isNotEmpty()) rxQueue.removeFirst() else ShortArray(frameSamples)
                            val tx = if (txQueue.isNotEmpty()) txQueue.removeFirst() else ShortArray(frameSamples)
                            dataBytes += writeMixedFrame(raf, rx, tx, byteBuf)
                        }
                    }
                }
                totalDataBytes = dataBytes
                patchWavHeader(raf, dataBytes)
                Log.i(TAG, "Recording finished: ${outputFile.absolutePath} ($dataBytes bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing audio to ${outputFile.name}: ${e.message}")
            } finally {
                runCatching { raf?.close() }
            }
        }, "CallRecorder-Writer").apply {
            priority = Thread.MIN_PRIORITY + 1
            start()
        }
        return true
    }

    fun writeRx(pcm: ShortArray, srcRate: Int = sampleRate) {
        if (!running.get() || pcm.isEmpty()) return
        val normalized = if (srcRate != sampleRate) PcmResampler.resample(pcm, srcRate, sampleRate) else pcm.copyOf()
        writeQueue.offer(MixChunk(isRx = true, pcm = normalized))
    }

    fun writeTx(pcm: ShortArray, srcRate: Int = sampleRate) {
        if (!running.get() || pcm.isEmpty()) return
        val normalized = if (srcRate != sampleRate) PcmResampler.resample(pcm, srcRate, sampleRate) else pcm.copyOf()
        writeQueue.offer(MixChunk(isRx = false, pcm = normalized))
    }

    fun stop(): File? {
        running.set(false)
        runCatching { writerThread?.join(1500L) }
        writerThread = null
        return if (outputFile.exists() && outputFile.length() > 44L) outputFile else null
    }

    private fun writeMixedFrame(
        raf: RandomAccessFile,
        rx: ShortArray,
        tx: ShortArray,
        byteBuf: ByteArray,
    ): Int {
        val len = minOf(frameSamples, maxOf(rx.size, tx.size))
        for (i in 0 until len) {
            val r = if (i < rx.size) rx[i].toInt() else 0
            val t = if (i < tx.size) tx[i].toInt() else 0
            val mixed = mixSoftKnee(r, t)
            byteBuf[i shl 1] = (mixed and 0xFF).toByte()
            byteBuf[(i shl 1) + 1] = ((mixed ushr 8) and 0xFF).toByte()
        }
        raf.write(byteBuf, 0, len * 2)
        return len * 2
    }

    companion object {
        private const val TAG = "CallRecorder"
        private const val KNEE = 26_000

        fun createRecordingFile(context: Context, remoteNumber: String, outgoing: Boolean): File {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
            val recDir = File(baseDir, "Recordings").apply { mkdirs() }
            val dir = if (outgoing) "out" else "in"
            val safe = remoteNumber.replace(Regex("[^0-9A-Za-z+]"), "_").ifBlank { "unknown" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return File(recDir, "JioCall_${dir}_${safe}_$stamp.wav")
        }

        fun mixSoftKnee(rxSample: Int, txSample: Int): Int {
            val sum = rxSample + txSample
            val a = abs(sum)
            if (a <= KNEE) return sum
            val headroom = 32_767 - KNEE
            val excess = (a - KNEE).toDouble() / headroom
            val shaped = KNEE + (headroom * tanh(excess)).roundToInt()
            return (if (sum >= 0) shaped else -shaped).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }

        fun buildWavHeader(dataBytes: Long, sampleRate: Int, channels: Int = 1): ByteArray {
            val byteRate = sampleRate * channels * 2L
            val blockAlign = (channels * 2).toShort()
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt((dataBytes + 36L).coerceAtMost(0xFFFFFFFFL).toInt())
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate.toInt())
                putShort(blockAlign)
                putShort(16) // Bits per sample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
            }.array()
        }

        fun patchWavHeader(raf: RandomAccessFile, dataBytes: Long) {
            val sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(4L)
            sizeBuf.putInt((dataBytes + 36L).coerceAtMost(0xFFFFFFFFL).toInt())
            raf.write(sizeBuf.array())
            sizeBuf.clear()
            raf.seek(40L)
            sizeBuf.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
            raf.write(sizeBuf.array())
        }
    }
}
