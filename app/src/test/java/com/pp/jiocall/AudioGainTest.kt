package com.pp.jiocall

import com.pp.jiocall.sip.AudioGain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioGainTest {

    @Test
    fun unityGainPassesSignalUntouched() {
        val original = shortArrayOf(-30000, -1200, 0, 1200, 24000, 32000)
        val buffer = original.copyOf()
        AudioGain.apply(buffer, buffer.size, AudioGain.MIC)
        assertArrayEquals(original, buffer)
    }

    @Test
    fun softLimiterPreventsOverflowWrapWhenAmplified() {
        val pcm = shortArrayOf(18_000, -18_000, 25_000, -25_000)
        AudioGain.apply(pcm, pcm.size, 2.0f)
        for (sample in pcm) {
            assertTrue("Expected sample within signed 16-bit bounds: $sample", sample in Short.MIN_VALUE..Short.MAX_VALUE)
        }
        assertTrue("Positive signal should saturate smoothly above knee", pcm[0] > 24_000)
        assertTrue("Negative signal should saturate smoothly below -knee", pcm[1] < -24_000)
    }

    @Test
    fun speakerGainExceedsEarpieceUnity() {
        assertEquals(1.0f, AudioGain.MIC, 0.0001f)
        assertEquals(1.0f, AudioGain.EARPIECE, 0.0001f)
        assertTrue(AudioGain.SPEAKER > AudioGain.EARPIECE)

        val ear = shortArrayOf(4000)
        val spk = shortArrayOf(4000)
        AudioGain.apply(ear, 1, AudioGain.EARPIECE)
        AudioGain.apply(spk, 1, AudioGain.SPEAKER)
        assertTrue(spk[0] > ear[0])
    }

    @Test
    fun dcBlockerStripsConstantOffsetWhilePreservingAcSpeech() {
        val blocker = AudioGain.DcBlocker()
        val acFreq = 1000.0
        val sampleRate = 16_000.0
        // Settle filter over 500ms of 1kHz speech riding on +4000 DC bias
        val frame = ShortArray(320)
        for (f in 0 until 25) {
            for (i in frame.indices) {
                val n = f * frame.size + i
                frame[i] = (4000.0 + 3000.0 * sin(2.0 * PI * acFreq * n / sampleRate)).roundToInt().toShort()
            }
            blocker.process(frame, frame.size)
        }
        val meanAfter = frame.map { it.toDouble() }.average()
        assertTrue("Expected DC bias attenuated near 0, got mean=$meanAfter", kotlin.math.abs(meanAfter) < 250.0)
        val peak = AudioGain.peakAmplitude(frame)
        assertTrue("Expected 1kHz speech preserved, got peak=$peak", peak in 2000..4000)
    }

    @Test
    fun adaptiveTxTrimLeavesNormalSpeechNearUnityAndGentlyLiftsFaintSpeech() {
        val trim = AudioGain.AdaptiveTxTrim()
        // Normal speech (~-18 dBFS -> ~4000 peak) stays near 1.0x
        val normal = ShortArray(320) { i ->
            (4000.0 * sin(2.0 * PI * 440.0 * i / 16_000.0)).roundToInt().toShort()
        }
        repeat(10) { trim.process(normal.copyOf(), normal.size) }
        assertTrue("Normal speech should stay near unity: ${trim.currentGain()}", trim.currentGain() in 0.95f..1.25f)

        // Consistently faint mic (~-42 dBFS -> ~350 amplitude) gently lifts without clipping or gating
        val faintTrim = AudioGain.AdaptiveTxTrim()
        val faint = ShortArray(320) { i ->
            (350.0 * sin(2.0 * PI * 440.0 * i / 16_000.0)).roundToInt().toShort()
        }
        repeat(30) { faintTrim.process(faint.copyOf(), faint.size) }
        assertTrue("Faint mic should boost smoothly: ${faintTrim.currentGain()}", faintTrim.currentGain() in 1.4f..2.2f)
    }
}
