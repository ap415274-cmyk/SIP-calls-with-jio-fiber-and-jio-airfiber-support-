package com.pp.jiocall.sip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clean telephony tone feedback (outgoing ringback, call waiting beep, busy tone,
 * local DTMF key tones).
 *
 * Critically ensures [stopRingback] joins and releases its `AudioTrack`
 * synchronously BEFORE live call capture/playback streams open, preventing HAL
 * AEC/routing collisions during `180 Ringing` -> `200 OK` transitions.
 */
object ToneGenerator {
    private const val TAG = "ToneGenerator"
    private const val SAMPLE_RATE = 16_000

    private val ringbackRunning = AtomicBoolean(false)
    private var ringbackThread: Thread? = null
    private var ringbackTrack: AudioTrack? = null
    private var dtmfToneGen: android.media.ToneGenerator? = null

    fun applyCommunicationRoute(context: Context, speakerOn: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        CallAudioController.applyDeviceRoute(am, speakerOn)
    }

    fun startRingback() {
        if (!ringbackRunning.compareAndSet(false, true)) return
        ringbackThread = Thread({
            var track: AudioTrack? = null
            try {
                val minBytes = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(SAMPLE_RATE / 5)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                ringbackTrack = track
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.w(TAG, "Ringback AudioTrack not initialized")
                    return@Thread
                }
                track.play()

                // Standard dual-tone ringback (400 Hz + 450 Hz) cadence: 1.0s ON, 2.0s OFF
                val chunkSamples = SAMPLE_RATE / 50 // 20 ms
                val toneBuf = ShortArray(chunkSamples)
                val silenceBuf = ShortArray(chunkSamples)
                var sampleIdx = 0L

                while (ringbackRunning.get()) {
                    // 1.0s tone (50 x 20ms)
                    for (f in 0 until 50) {
                        if (!ringbackRunning.get()) break
                        for (i in 0 until chunkSamples) {
                            val t = sampleIdx.toDouble() / SAMPLE_RATE
                            val v = 0.5 * (sin(2.0 * PI * 440.0 * t) + sin(2.0 * PI * 480.0 * t))
                            toneBuf[i] = (v * 3500.0).roundToInt().toShort()
                            sampleIdx++
                        }
                        if (ringbackRunning.get()) track.write(toneBuf, 0, chunkSamples)
                    }
                    // 2.0s pause (100 x 20ms)
                    for (f in 0 until 100) {
                        if (!ringbackRunning.get()) break
                        track.write(silenceBuf, 0, chunkSamples)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ringback error: ${e.message}")
            } finally {
                ringbackTrack = null
                runCatching { track?.pause() }
                runCatching { track?.flush() }
                runCatching { track?.stop() }
                runCatching { track?.release() }
                ringbackRunning.set(false)
                Log.d(TAG, "Ringback tone stopped")
            }
        }, "ToneGenerator-Ringback").apply { start() }
    }

    fun stopRingback() {
        val wasRunning = ringbackRunning.getAndSet(false)
        val t = ringbackThread
        ringbackThread = null
        runCatching { ringbackTrack?.pause() }
        runCatching { ringbackTrack?.flush() }
        runCatching { ringbackTrack?.stop() }
        if (wasRunning && t != null && Thread.currentThread() !== t) {
            runCatching { t.join(400L) }
        }
    }

    fun playBusyTone() {
        stopRingback()
        Thread({
            playAndroidTone(android.media.ToneGenerator.TONE_SUP_BUSY, 1200)
        }, "ToneGenerator-Busy").start()
    }

    fun playCallWaitingBeep() {
        Thread({
            playAndroidTone(android.media.ToneGenerator.TONE_SUP_CALL_WAITING, 400)
        }, "ToneGenerator-CallWaiting").start()
    }

    @Synchronized
    fun playDtmf(digit: Char) {
        val toneType = when (digit) {
            '0' -> android.media.ToneGenerator.TONE_DTMF_0
            '1' -> android.media.ToneGenerator.TONE_DTMF_1
            '2' -> android.media.ToneGenerator.TONE_DTMF_2
            '3' -> android.media.ToneGenerator.TONE_DTMF_3
            '4' -> android.media.ToneGenerator.TONE_DTMF_4
            '5' -> android.media.ToneGenerator.TONE_DTMF_5
            '6' -> android.media.ToneGenerator.TONE_DTMF_6
            '7' -> android.media.ToneGenerator.TONE_DTMF_7
            '8' -> android.media.ToneGenerator.TONE_DTMF_8
            '9' -> android.media.ToneGenerator.TONE_DTMF_9
            '*' -> android.media.ToneGenerator.TONE_DTMF_S
            '#' -> android.media.ToneGenerator.TONE_DTMF_P
            'A', 'a' -> android.media.ToneGenerator.TONE_DTMF_A
            'B', 'b' -> android.media.ToneGenerator.TONE_DTMF_B
            'C', 'c' -> android.media.ToneGenerator.TONE_DTMF_C
            'D', 'd' -> android.media.ToneGenerator.TONE_DTMF_D
            else -> return
        }
        runCatching {
            val tg = dtmfToneGen ?: android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 45).also {
                dtmfToneGen = it
            }
            tg.startTone(toneType, 160)
        }.onFailure {
            Log.w(TAG, "Failed to play DTMF tone: ${it.message}")
        }
    }

    @Synchronized
    fun releaseDtmf() {
        runCatching { dtmfToneGen?.release() }
        dtmfToneGen = null
    }

    private fun playAndroidTone(tone: Int, durationMs: Int) {
        runCatching {
            val tg = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55)
            try {
                tg.startTone(tone, durationMs)
                Thread.sleep(durationMs.toLong() + 80L)
            } finally {
                tg.stopTone()
                tg.release()
            }
        }.onFailure {
            Log.w(TAG, "Failed to play tone $tone: ${it.message}")
        }
    }
}
