package com.pp.jiocall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pp.jiocall.sip.AmrDecoder
import com.pp.jiocall.sip.AmrEncoder
import com.pp.jiocall.sip.AmrFraming
import com.pp.jiocall.sip.AudioGain
import com.pp.jiocall.sip.Codec
import com.pp.jiocall.sip.PcmResampler
import com.pp.jiocall.sip.RtpAudio

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import android.util.Log
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun testAmrWbEncoderDecoder() {
        val encoder = AmrEncoder(Codec.AMR_WB)
        val decoder = AmrDecoder(Codec.AMR_WB)

        // Generate 16000 Hz 440Hz sine wave (320 samples per 20ms frame)
        val frameSamples = 320
        val pcm = ShortArray(frameSamples)
        for (i in 0 until frameSamples) {
            pcm[i] = (3000 * Math.sin(2.0 * Math.PI * 440.0 * i / 16000.0)).toInt().toShort()
        }

        var encodedFrames = 0
        var decodedFrames = 0
        for (f in 0 until 10) {
            val encoded = encoder.encode(pcm)
            if (encoded != null) {
                encodedFrames++
                Log.i("TestAMR", "Frame $f encoded size: ${encoded.size}, hex: ${encoded.take(8).joinToString(" ") { "%02X".format(it) }}")
                val be = RtpAudio.amrToBandwidthEfficient(encoded)
                Log.i("TestAMR", "Frame $f BE size: ${be.size}, hex: ${be.take(8).joinToString(" ") { "%02X".format(it) }}")
                val recovered = RtpAudio.bandwidthEfficientToAmr(be, 0, be.size, true)
                assertNotNull("Failed to recover AMR frame from BE", recovered)
                val decoded = decoder.decode(recovered!!)
                if (decoded != null) {
                    decodedFrames++
                    Log.i("TestAMR", "Frame $f decoded sample count: ${decoded.size}")
                }
            }
        }
        encoder.release()
        decoder.release()
        assertTrue("Encoder should produce frames", encodedFrames > 0)
        assertTrue("Decoder should produce decoded PCM", decodedFrames > 0)
    }

    @Test
    fun testFull48kCaptureToJioMode3OctetAlignedLoopback() {
        val encoder = AmrEncoder(Codec.AMR_WB, setOf(0, 1, 2, 3))
        val decoder = AmrDecoder(Codec.AMR_WB)
        val dcBlocker = AudioGain.DcBlocker()
        val trim = AudioGain.AdaptiveTxTrim()

        var sampleCursor = 0
        var lastDecodedPeak = 0
        var zeroCrossings = 0

        // Run 20 frames (400 ms) of continuous 440 Hz @ 48 kHz -> 16 kHz -> AMR-WB Mode 3 (37 bytes + 1 CMR = 38)
        for (f in 0 until 20) {
            val cap48k = ShortArray(960) { i ->
                val t = (sampleCursor + i).toDouble() / 48_000.0
                (6000.0 * sin(2.0 * PI * 440.0 * t)).roundToInt().toShort()
            }
            sampleCursor += 960
            val pcm16k = PcmResampler.resample(cap48k, 48_000, 16_000, 960)
            dcBlocker.process(pcm16k, pcm16k.size)
            trim.process(pcm16k, pcm16k.size)

            for (frame in encoder.encodeFrames(pcm16k)) {
                val oa = AmrFraming.toOctetAligned(frame)
                assertEquals("Mode 3 OA RTP packet must be 38 bytes matching Jio ISBC", 38, oa.size)
                val unpacked = AmrFraming.unpackRtpFrames(oa, 0, oa.size, isWideband = true, negotiatedOctetAligned = true)
                assertNotNull(unpacked)
                val decoded = decoder.decode(unpacked!!.frames.single())
                if (decoded != null && f >= 10) {
                    lastDecodedPeak = maxOf(lastDecodedPeak, AudioGain.peakAmplitude(decoded))
                    for (j in 1 until decoded.size) {
                        if ((decoded[j - 1] < 0 && decoded[j] >= 0) || (decoded[j - 1] >= 0 && decoded[j] < 0)) {
                            zeroCrossings++
                        }
                    }
                }
            }
        }
        encoder.release()
        decoder.release()
        assertTrue("Decoded voice peak should stay strong, got $lastDecodedPeak", lastDecodedPeak > 2500)
        // 10 frames (200 ms) of 440 Hz -> ~176 zero crossings expected
        assertTrue("Decoded tone frequency corrupted (crossings=$zeroCrossings)", zeroCrossings in 120..240)
    }
}
