package com.pp.jiocall

import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import com.pp.jiocall.sip.AmrEncoder
import com.pp.jiocall.sip.AmrFraming
import com.pp.jiocall.sip.CallRecorder
import com.pp.jiocall.sip.Codec
import com.pp.jiocall.sip.PcmConcealer
import com.pp.jiocall.sip.PcmResampler
import com.pp.jiocall.sip.RtpAudio
import com.pp.jiocall.sip.RtpJitterBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioEngineTest {

    private fun sampleStorageFrame(ft: Int, isWideband: Boolean, seed: Int = 11): ByteArray {
        val len = AmrFraming.storageFrameLengthForFt(ft, isWideband)
        val speechBits = AmrFraming.speechBitsForFt(ft, isWideband)
        val out = ByteArray(len)
        out[0] = ((ft shl 3) or 0x04).toByte() // F=0, FT, Q=1
        for (i in 1 until len) out[i] = ((i * 41 + seed) and 0xFF).toByte()
        val unused = (len - 1) * 8 - speechBits
        if (unused > 0) {
            out[len - 1] = (out[len - 1].toInt() and (0xFF shl unused)).toByte()
        }
        return out
    }

    @Test
    fun amrFramingSupportsAllModesIncludingFt3AndSid() {
        assertEquals(37, AmrFraming.storageFrameLengthForFt(3, isWideband = true))
        assertEquals(36, AmrFraming.speechBytesForFt(3, isWideband = true))
        assertEquals(6, AmrFraming.storageFrameLengthForFt(9, isWideband = true))
        assertEquals(6, AmrFraming.storageFrameLengthForFt(8, isWideband = false))

        for (ft in 0..8) {
            val frame = sampleStorageFrame(ft, isWideband = true, seed = ft + 3)
            val oa = AmrFraming.toOctetAligned(frame)
            val parsedOa = AmrFraming.unpackRtpFrames(oa, 0, oa.size, isWideband = true, negotiatedOctetAligned = true)
            assertNotNull("WB OA FT=$ft", parsedOa)
            assertArrayEquals("WB OA FT=$ft", frame, parsedOa!!.frames.single())

            val be = AmrFraming.toBandwidthEfficient(frame, isWideband = true)
            val parsedBe = AmrFraming.unpackRtpFrames(be, 0, be.size, isWideband = true, negotiatedOctetAligned = false)
            assertNotNull("WB BE FT=$ft", parsedBe)
            assertArrayEquals("WB BE FT=$ft", frame, parsedBe!!.frames.single())
        }

        for (ft in 0..7) {
            val frame = sampleStorageFrame(ft, isWideband = false, seed = ft + 19)
            val be = AmrFraming.toBandwidthEfficient(frame, isWideband = false)
            val parsedBe = AmrFraming.unpackRtpFrames(be, 0, be.size, isWideband = false, negotiatedOctetAligned = false)
            assertNotNull("NB BE FT=$ft", parsedBe)
            assertArrayEquals("NB BE FT=$ft", frame, parsedBe!!.frames.single())
        }
    }

    @Test
    fun multiFrameOctetAlignedAndBandwidthEfficientRtpPacketsUnpackBothFrames() {
        val f1 = sampleStorageFrame(2, isWideband = true, seed = 7)
        val f2 = sampleStorageFrame(3, isWideband = true, seed = 23)

        // Octet-Aligned 2-frame RTP packet (CMR=2, ToC1 F=1, ToC2 F=0, speech1, speech2)
        val oaMulti = ByteArray(1 + 2 + (f1.size - 1) + (f2.size - 1))
        oaMulti[0] = (2 shl 4).toByte() // CMR=2
        oaMulti[1] = (f1[0].toInt() or 0x80).toByte() // F=1
        oaMulti[2] = f2[0] // F=0
        System.arraycopy(f1, 1, oaMulti, 3, f1.size - 1)
        System.arraycopy(f2, 1, oaMulti, 3 + (f1.size - 1), f2.size - 1)

        val unpacked = AmrFraming.unpackRtpFrames(oaMulti, 0, oaMulti.size, isWideband = true, negotiatedOctetAligned = true)
        assertNotNull(unpacked)
        assertEquals(2, unpacked!!.cmr)
        assertEquals(2, unpacked.frames.size)
        assertArrayEquals(f1, unpacked.frames[0])
        assertArrayEquals(f2, unpacked.frames[1])
    }

    @Test
    fun corruptedAmrFrameTypeIsRejectedPerRfc4867() {
        // FT=12 is reserved/invalid -> packet must be discarded
        val bad = byteArrayOf(0xF0.toByte(), 0x64.toByte(), 0x11, 0x22)
        assertNull(AmrFraming.unpackRtpFrames(bad, 0, bad.size, isWideband = true, negotiatedOctetAligned = true))
    }

    @Test
    fun splitStorageFramesHandlesFileHeaderAndConcatenatedFrames() {
        val f1 = sampleStorageFrame(2, isWideband = true, seed = 1)
        val f2 = sampleStorageFrame(8, isWideband = true, seed = 2)
        val hdr = "#!AMR-WB\n".toByteArray(Charsets.US_ASCII)
        val combined = hdr + f1 + f2
        val split = AmrFraming.splitStorageFrames(combined, isWideband = true)
        assertEquals(2, split.size)
        assertArrayEquals(f1, split[0])
        assertArrayEquals(f2, split[1])
    }

    @Test
    fun jitterBufferOrdersPacketsHandlesWraparoundAndResumesOnDtx() {
        val jb = RtpJitterBuffer(minPrefillFrames = 2, maxCapacityFrames = 12)
        jb.push(RtpJitterBuffer.Packet(seq = 12, timestamp = 1600L, codec = Codec.AMR_WB, frame = byteArrayOf(12)))
        jb.push(RtpJitterBuffer.Packet(seq = 10, timestamp = 960L, codec = Codec.AMR_WB, frame = byteArrayOf(10)))
        jb.push(RtpJitterBuffer.Packet(seq = 11, timestamp = 1280L, codec = Codec.AMR_WB, frame = byteArrayOf(11)))

        val p1 = jb.popNext() as RtpJitterBuffer.PopResult.Frame
        val p2 = jb.popNext() as RtpJitterBuffer.PopResult.Frame
        val p3 = jb.popNext() as RtpJitterBuffer.PopResult.Frame
        assertEquals(10, p1.packet.seq)
        assertEquals(11, p2.packet.seq)
        assertEquals(12, p3.packet.seq)

        // DTX jump resets cursor promptly without dropping resumed talkspurt
        jb.push(RtpJitterBuffer.Packet(seq = 80, timestamp = 25_600L, codec = Codec.AMR_WB, frame = byteArrayOf(80)))
        val resumed = jb.popNext()
        assertTrue("Expected immediate Frame after DTX jump, got $resumed", resumed is RtpJitterBuffer.PopResult.Frame)
        assertEquals(80, (resumed as RtpJitterBuffer.PopResult.Frame).packet.seq)

        // Sequence number 16-bit wraparound
        jb.reset()
        jb.push(RtpJitterBuffer.Packet(seq = 0xFFFF, timestamp = 320L, codec = Codec.PCMU, frame = byteArrayOf(1)))
        jb.push(RtpJitterBuffer.Packet(seq = 0, timestamp = 480L, codec = Codec.PCMU, frame = byteArrayOf(2)))
        assertEquals(0xFFFF, (jb.popNext() as RtpJitterBuffer.PopResult.Frame).packet.seq)
        assertEquals(0, (jb.popNext() as RtpJitterBuffer.PopResult.Frame).packet.seq)
    }

    @Test
    fun pcmConcealerFadesToSilenceWithin120Ms() {
        val concealer = PcmConcealer(16_000)
        concealer.recordGood(ShortArray(320) { 10_000 })
        val c1 = concealer.conceal()
        val c2 = concealer.conceal()
        assertTrue(c1[0] in 1 until 10_000)
        assertTrue(c2[0] < c1[0])
        repeat(5) { concealer.conceal() }
        val silence = concealer.conceal()
        assertEquals(0, silence[0].toInt())
    }

    @Test
    fun resamplerConverts48kTo16kAnd8kWithAntiAliasing() {
        // 1 kHz pure tone @ 48 kHz -> should survive cleanly at 16 kHz and 8 kHz
        val in48k = ShortArray(960) { i ->
            (10_000.0 * sin(2.0 * PI * 1000.0 * i / 48_000.0)).roundToInt().toShort()
        }
        val out16k = PcmResampler.resample(in48k, 48_000, 16_000)
        val out8k = PcmResampler.resample(in48k, 48_000, 8_000)
        assertEquals(320, out16k.size)
        assertEquals(160, out8k.size)
        assertTrue(out16k.maxOf { kotlin.math.abs(it.toInt()) } > 7000)
        assertTrue(out8k.maxOf { kotlin.math.abs(it.toInt()) } > 6000)

        // High-frequency ultrasonic tone (18 kHz) @ 48 kHz MUST be attenuated when downsampling to 16 kHz (Nyquist 8 kHz)
        val hi48k = ShortArray(960) { i ->
            (12_000.0 * sin(2.0 * PI * 18_000.0 * i / 48_000.0)).roundToInt().toShort()
        }
        val aliasedCheck = PcmResampler.resample(hi48k, 48_000, 16_000)
        // Check steady-state passband/stopband attenuation away from the hard frame endpoints
        val steadyPeak = aliasedCheck.slice(20 until aliasedCheck.size - 20).maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("Expected anti-aliasing filter to suppress steady-state 18kHz below 300, got $steadyPeak", steadyPeak < 300)
    }

    @Test
    fun amrModeSetAndBitrateTableRespectNegotiatedBounds() {
        assertEquals(3, AmrEncoder.pickInitialMode(Codec.AMR_WB, setOf(0, 1, 2, 3)))
        assertEquals(14250, AmrEncoder.bitrateForMode(Codec.AMR_WB, 3))
        assertEquals(8, AmrEncoder.pickInitialMode(Codec.AMR_WB, null))
        assertEquals(23850, AmrEncoder.bitrateForMode(Codec.AMR_WB, 8))
        assertEquals(7, AmrEncoder.pickInitialMode(Codec.AMR_NB, null))
        assertEquals(12200, AmrEncoder.bitrateForMode(Codec.AMR_NB, 7))
        assertEquals(5, AmrEncoder.modeForBitrate(Codec.AMR_WB, 18250))
    }

    @Test
    fun routingAndCaptureCandidateSelectionMatchTelephonyPriority() {
        val candidates = RtpAudio.buildCaptureCandidates(codecRate = 8_000, nativeRate = 48_000, preferHardwareAec = true)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, candidates.first().audioSource)
        assertEquals(8_000, candidates.first().sampleRate)
        assertTrue(candidates.any { it.audioSource == MediaRecorder.AudioSource.MIC && it.sampleRate == 48_000 })
        assertTrue(candidates.any { it.audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION })

        val devices = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, RtpAudio.selectTargetDeviceType(devices, speakerOn = true))
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, RtpAudio.selectTargetDeviceType(devices, speakerOn = false))
        assertTrue(RtpAudio.isExternalCommDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(RtpAudio.isExternalCommDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
    }

    @Test
    fun callRecorderBuildsValidWavHeaderAndSoftKneeMix() {
        val hdr = CallRecorder.buildWavHeader(dataBytes = 3200L, sampleRate = 16_000)
        assertEquals(44, hdr.size)
        assertEquals("RIFF", String(hdr, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(hdr, 8, 4, Charsets.US_ASCII))
        assertEquals(15_000, CallRecorder.mixSoftKnee(10_000, 5_000))
        val clippedPos = CallRecorder.mixSoftKnee(25_000, 25_000)
        assertTrue(clippedPos in 26_001..32_767)
        val clippedNeg = CallRecorder.mixSoftKnee(-25_000, -25_000)
        assertTrue(clippedNeg in -32_767..-26_001)
    }

    @Test
    fun jitterBufferTrimBacklogKeepsNewestFramesInOrder() {
        val jb = RtpJitterBuffer(minPrefillFrames = 2, maxCapacityFrames = 24)
        for (seq in 100 until 106) {
            jb.push(RtpJitterBuffer.Packet(seq = seq, timestamp = seq * 160L, codec = Codec.AMR_NB, frame = byteArrayOf(seq.toByte())))
        }
        // Prime and consume first packet (seq=100)
        val first = jb.popNext() as RtpJitterBuffer.PopResult.Frame
        assertEquals(100, first.packet.seq)

        // Shed backlog down to the newest 2 packets (104, 105)
        jb.trimBacklog(maxKeepFrames = 2)
        val p1 = jb.popNext() as RtpJitterBuffer.PopResult.Frame
        val p2 = jb.popNext() as RtpJitterBuffer.PopResult.Frame
        assertEquals(104, p1.packet.seq)
        assertEquals(105, p2.packet.seq)
        assertEquals(0L, jb.totalLost)
    }

    @Test
    fun resamplerSupportsUpsamplingAndFractionalRatesIntoBuffer() {
        val in8k = ShortArray(160) { i ->
            (8_000.0 * sin(2.0 * PI * 1000.0 * i / 8_000.0)).roundToInt().toShort()
        }
        val dst48k = ShortArray(960)
        val written48 = PcmResampler.resampleInto(in8k, 8_000, 48_000, in8k.size, dst48k)
        assertEquals(960, written48)
        assertTrue(dst48k.maxOf { kotlin.math.abs(it.toInt()) } > 6000)

        val in44 = ShortArray(882) { i ->
            (8_000.0 * sin(2.0 * PI * 1000.0 * i / 44_100.0)).roundToInt().toShort()
        }
        val out16 = PcmResampler.resample(in44, 44_100, 16_000)
        assertEquals(320, out16.size)
        assertTrue(out16.maxOf { kotlin.math.abs(it.toInt()) } > 5000)
    }
}
