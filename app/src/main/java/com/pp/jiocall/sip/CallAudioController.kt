package com.pp.jiocall.sip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates the Android communication audio session for a single call:
 *
 * 1. Waits for `CallService` foreground service promotion (`phoneCall|microphone`)
 *    when `targetSdk >= 34` so Android/OEM policy does not zero-mute mic frames.
 * 2. Acquires `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` (fallback `GAIN_TRANSIENT`)
 *    with `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`.
 * 3. Explicitly clears `AudioManager.isMicrophoneMute`.
 * 4. Switches `AudioManager.mode = MODE_IN_COMMUNICATION` and waits via
 *    `OnModeChangedListener` (API 31+, Baresip pattern) until the HAL confirms
 *    communication mode before `AudioRecord`/`AudioTrack` open.
 * 5. Routes Earpiece / Speaker / Wired Headset / Bluetooth SCO/BLE and restores
 *    normal mode + focus cleanly on release.
 */
internal class CallAudioController(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val released = AtomicBoolean(false)
    private var focusRequest: AudioFocusRequest? = null
    private var commDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var previousMode: Int = AudioManager.MODE_NORMAL
    @Volatile private var speakerPreferred: Boolean = false
    @Volatile private var sessionToken: Long = 0L

    fun acquireSession(speakerOn: Boolean) {
        sessionToken = activeSessionToken.incrementAndGet()
        speakerPreferred = speakerOn
        val am = audioManager ?: return

        // 1. Wait for Foreground Service microphone capability (Android 14-17)
        CallService.awaitMicrophoneForeground(context, timeoutMs = 1200L)

        // 2. Request exclusive VoIP audio focus so no media/other call hijacks mic
        requestAudioFocus(am)

        // 3. Ensure system microphone mute is explicitly cleared
        runCatching { am.isMicrophoneMute = false }

        // 4. Transition HAL into MODE_IN_COMMUNICATION and wait for confirmation (API 31+)
        previousMode = am.mode
        enterCommunicationModeAndWait(am, timeoutMs = 600L)

        // 5. Apply initial output routing (Speaker / Bluetooth / Wired / Earpiece)
        applyDeviceRoute(am, speakerOn)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerCommunicationDeviceWatcher(am)
        }
    }

    fun setSpeaker(speakerOn: Boolean) {
        speakerPreferred = speakerOn
        val am = audioManager ?: return
        applyDeviceRoute(am, speakerOn)
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commDeviceListener?.let { listener ->
                runCatching { am.removeOnCommunicationDeviceChangedListener(listener) }
            }
            commDeviceListener = null
        }

        // Only reset global AudioManager routing/mode if a newer call hasn't acquired an active session.
        if (activeSessionToken.get() != sessionToken) {
            abandonAudioFocus(am)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                am.isSpeakerphoneOn = false
            }
        }

        runCatching {
            val targetMode = if (previousMode == AudioManager.MODE_RINGTONE) AudioManager.MODE_NORMAL else previousMode
            am.mode = if (targetMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else targetMode
        }

        abandonAudioFocus(am)
        Log.i(TAG, "Audio session released (mode=${am.mode})")
    }

    private fun requestAudioFocus(am: AudioManager) {
        if (focusRequest != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val exclusive = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                Log.d(TAG, "AudioFocus change=$change")
            }
            .build()

        val granted = runCatching {
            am.requestAudioFocus(exclusive) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)

        if (granted) {
            focusRequest = exclusive
            Log.d(TAG, "AudioFocus AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE granted")
            return
        }

        val transient = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> Log.d(TAG, "AudioFocus change=$change") }
            .build()

        if (runCatching { am.requestAudioFocus(transient) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)) {
            focusRequest = transient
            Log.d(TAG, "AudioFocus AUDIOFOCUS_GAIN_TRANSIENT granted (fallback)")
        } else {
            Log.w(TAG, "AudioFocus request denied; proceeding cautiously")
        }
    }

    private fun abandonAudioFocus(am: AudioManager) {
        val req = focusRequest ?: return
        focusRequest = null
        runCatching { am.abandonAudioFocusRequest(req) }
    }

    private fun enterCommunicationModeAndWait(am: AudioManager, timeoutMs: Long) {
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            Log.d(TAG, "AudioManager already in MODE_IN_COMMUNICATION")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            return
        }

        val latch = CountDownLatch(1)
        val listener = AudioManager.OnModeChangedListener { newMode ->
            if (newMode == AudioManager.MODE_IN_COMMUNICATION || newMode == AudioManager.MODE_IN_CALL) {
                latch.countDown()
            }
        }
        runCatching {
            am.addOnModeChangedListener(context.mainExecutor, listener)
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val reached = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            Log.i(TAG, "MODE_IN_COMMUNICATION transition confirmed=$reached currentMode=${am.mode}")
        }.onFailure {
            Log.w(TAG, "OnModeChangedListener wait failed: ${it.message}")
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        runCatching { am.removeOnModeChangedListener(listener) }
    }

    private fun registerCommunicationDeviceWatcher(am: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || commDeviceListener != null) return
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            Log.d(TAG, "Communication device changed -> type=${device?.type} name=${device?.productName}")
        }
        runCatching {
            am.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
            commDeviceListener = listener
        }
    }

    companion object {
        private const val TAG = "CallAudioController"
        private val activeSessionToken = AtomicLong(0L)

        private val EXTERNAL_HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        fun isExternalCommDeviceType(type: Int): Boolean = type in EXTERNAL_HEADSET_TYPES

        fun selectTargetDeviceType(availableTypes: Collection<Int>, speakerOn: Boolean): Int? {
            if (speakerOn) {
                return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER.takeIf { it in availableTypes }
                    ?: availableTypes.firstOrNull()
            }
            val externalPriority = intArrayOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            )
            for (pref in externalPriority) {
                if (pref in availableTypes) return pref
            }
            return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE.takeIf { it in availableTypes }
                ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER.takeIf { it in availableTypes }
                ?: availableTypes.firstOrNull()
        }

        fun applyDeviceRoute(am: AudioManager, speakerOn: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    val devices = am.availableCommunicationDevices
                    val targetType = selectTargetDeviceType(devices.map { it.type }, speakerOn)
                    val target = targetType?.let { wanted -> devices.firstOrNull { it.type == wanted } }
                    if (target != null) {
                        val current = am.communicationDevice
                        if (current?.id != target.id) {
                            val ok = am.setCommunicationDevice(target)
                            Log.i(TAG, "setCommunicationDevice(type=${target.type}) -> $ok (speaker=$speakerOn)")
                        }
                    } else if (!speakerOn) {
                        am.clearCommunicationDevice()
                    }
                }.onFailure {
                    Log.w(TAG, "Failed applying communication device: ${it.message}")
                }
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
                    val hasBt = AudioDeviceInfo.TYPE_BLUETOOTH_SCO in outputs
                    if (!speakerOn && hasBt) {
                        am.isSpeakerphoneOn = false
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    } else {
                        if (am.isBluetoothScoOn) {
                            am.stopBluetoothSco()
                            am.isBluetoothScoOn = false
                        }
                        am.isSpeakerphoneOn = speakerOn
                    }
                }
            }
        }
    }
}
