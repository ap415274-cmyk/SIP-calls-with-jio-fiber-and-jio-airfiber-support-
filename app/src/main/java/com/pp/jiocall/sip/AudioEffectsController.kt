package com.pp.jiocall.sip

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.UUID

/**
 * Hardware-only pre-processing effects controller modeled directly on
 * WebRTC's `WebRtcAudioEffects.java`.
 *
 * AOSP software AEC and NS implementations (`bb392ec0-...` and `c06c8400-...`)
 * are explicitly blocklisted because they aggressively gate or corrupt TX mic
 * frames on many devices. Only vendor/hardware-backed effects with distinct
 * UUIDs are enabled, and always before `AudioRecord.startRecording()`.
 */
internal class AudioEffectsController {

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    fun attach(sessionId: Int, enableAec: Boolean, enableNs: Boolean) {
        release()
        if (sessionId <= 0) return

        if (enableAec) {
            attachAec(sessionId)
        }
        if (enableNs) {
            attachNs(sessionId)
        }
    }

    private fun attachAec(sessionId: Int) {
        if (!isHardwareAecSupported()) {
            Log.d(TAG, "Hardware AEC unavailable or blocklisted; skipping AcousticEchoCanceler")
            return
        }
        runCatching {
            val created = AcousticEchoCanceler.create(sessionId) ?: return
            val isHw = !isAospSoftwareAec(created.descriptor)
            if (!isHw) {
                created.release()
                return
            }
            val prevEnabled = created.enabled
            val err = created.setEnabled(true)
            if (err != AudioEffect.SUCCESS) {
                Log.w(TAG, "AcousticEchoCanceler.setEnabled(true) failed: $err")
                created.release()
                return
            }
            aec = created
            Log.i(
                TAG,
                "Hardware AEC enabled: name=${created.descriptor.name} uuid=${created.descriptor.uuid} (was=$prevEnabled, now=${created.enabled})",
            )
        }.onFailure {
            Log.w(TAG, "AcousticEchoCanceler init failed: ${it.message}")
        }
    }

    private fun attachNs(sessionId: Int) {
        if (!isHardwareNsSupported()) {
            Log.d(TAG, "Hardware NS unavailable or blocklisted; skipping NoiseSuppressor")
            return
        }
        runCatching {
            val created = NoiseSuppressor.create(sessionId) ?: return
            if (isAospSoftwareNs(created.descriptor)) {
                created.release()
                return
            }
            val err = created.setEnabled(true)
            if (err != AudioEffect.SUCCESS) {
                Log.w(TAG, "NoiseSuppressor.setEnabled(true) failed: $err")
                created.release()
                return
            }
            ns = created
            Log.i(
                TAG,
                "Hardware NS enabled: name=${created.descriptor.name} uuid=${created.descriptor.uuid} now=${created.enabled}",
            )
        }.onFailure {
            Log.w(TAG, "NoiseSuppressor init failed: ${it.message}")
        }
    }

    fun release() {
        runCatching { aec?.release() }
        aec = null
        runCatching { ns?.release() }
        ns = null
    }

    companion object {
        private const val TAG = "AudioEffects"

        // Exact UUIDs blocklisted in WebRTC's WebRtcAudioEffects.java
        private val AOSP_SW_AEC_UUID = UUID.fromString("bb392ec0-8d4d-11e0-a896-0002a5d5c51b")
        private val AOSP_SW_NS_UUID = UUID.fromString("c06c8400-8e06-11e0-9cb6-0002a5d5c51b")

        fun isAospSoftwareAec(desc: AudioEffect.Descriptor?): Boolean =
            desc != null && desc.uuid == AOSP_SW_AEC_UUID

        fun isAospSoftwareNs(desc: AudioEffect.Descriptor?): Boolean =
            desc != null && desc.uuid == AOSP_SW_NS_UUID

        private val hwAecAvailable: Boolean by lazy {
            if (!runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)) {
                return@lazy false
            }
            val effects = runCatching { AudioEffect.queryEffects() }.getOrNull() ?: return@lazy false
            effects.any {
                it.type == AudioEffect.EFFECT_TYPE_AEC && !isAospSoftwareAec(it)
            }
        }

        private val hwNsAvailable: Boolean by lazy {
            if (!runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {
                return@lazy false
            }
            val effects = runCatching { AudioEffect.queryEffects() }.getOrNull() ?: return@lazy false
            effects.any {
                it.type == AudioEffect.EFFECT_TYPE_NS && !isAospSoftwareNs(it)
            }
        }

        fun isHardwareAecSupported(): Boolean = hwAecAvailable

        fun isHardwareNsSupported(): Boolean = hwNsAvailable
    }
}
