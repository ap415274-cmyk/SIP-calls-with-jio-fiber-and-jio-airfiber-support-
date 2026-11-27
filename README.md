# JioCall

Native Android SIP/VoIP dialer written in **100% Kotlin & Jetpack Compose** with automatic **JioFiber / JioAirFiber ONT discovery & SIP provisioning** as well as full support for standard SIP (`UDP` / `TCP` / `TLS`) accounts. Zero NDK, C++, or external native VoIP libraries required.

[![Platform](https://img.shields.io/badge/Android-9.0%2B%20(API%2028)-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202026.02.01-4285F4?logo=jetpackcompose&logoColor=white)](gradle/libs.versions.toml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

---

## Disclaimer

> **Independent Open-Source Project:** JioCall is not affiliated with, sponsored by, or endorsed by Reliance Jio Infocomm Ltd. "Jio", "JioFiber", and "JioAirFiber" are trademarks of their respective owners and are referenced solely to describe local network ONT router compatibility.
>
> Use this software responsibly and only with your own router, voice line, or authorized SIP server/PBX.

---

## Features

### JioFiber & JioAirFiber Provisioning
- **Zero-config router discovery**: Probes `jiofiber.local.html`, the active default IPv4 gateway, `192.168.29.1`, and `192.168.31.1` on port `8080` (`/request_account`) to locate your router's SIP voice line (`JioRouter`, `JioClient`, `RouterHttp`).
- **Temporary & Permanent modes**:
  - **Temporary (`eth`)**: Requests volatile SIP credentials over the router's HTTPS endpoint (`port 8443`) with no OTP and automatically renews them when expired or rejected.
  - **Permanent (`wifi`)**: Registers the device with OTP verification sent to the line's mobile number for persistent credentials.
- **Deterministic device identity**: Computes the router MAC identifier and `+sip.instance` UUID extension required by Jio ONT proxies (`port 5068` over TLS).

### Full-Featured SIP & Real-Time Audio Engine
- **Pure Kotlin RFC 3261 signaling**: Supports `UDP`, `TCP`, and `TLS` transports, MD5 Digest authentication, `REGISTER` refresh, NAT keep-alive, Call Hold, Caller ID hiding (`Privacy: id`), Do Not Disturb (`486 Busy Here`), Call Forwarding (`302`), Call Waiting, and Linksys/Cisco-style `DialPlan` rewrite rules.
- **HD Voice & Multi-Codec Support**: Negotiates **AMR-WB (`16 kHz`)**, **AMR-NB (`8 kHz`)**, **PCMA (G.711 A-law)**, and **PCMU (G.711 μ-law)** with consistent single-codec SDP answering (`MediaNegotiation`, `Sdp`).
- **RFC 4867 AMR Framing**: Complete support for Bandwidth-Efficient (`BE`) and Octet-Aligned (`OA`) frame packing/unpacking, plus dynamic Codec Mode Request (`CMR`) adaptation (`AmrFraming`).
- **Hardware-aware real-time audio pipeline**:
  - Uses deterministic Google Codec2 / OMX AMR software `MediaCodec` instances (`AmrCodec`) to avoid vendor hardware codec distortion.
  - Adaptive jitter buffer (`RtpJitterBuffer`), Packet Loss Concealment (`PcmConcealer`), and cached Blackman-sinc `PcmResampler`.
  - Direct codec-rate `AudioTrack` fallback (`AudioPlaybackEngine`) when `AudioFlinger` rejects `FLAG_FAST` low-latency mode, plus concurrent encoder/audio-session initialization (`RtpAudio`).
  - Hardware `AcousticEchoCanceler` and `NoiseSuppressor` management (`AudioEffectsController`), soft-knee TX/RX gain control (`AudioGain`), and RFC 2833/4733 DTMF + dial/ringback tones (`ToneGenerator`).
  - Asynchronous WAV local call recording (`CallRecorder`) and live ITU-T E-model MOS quality estimation (`CallQuality`).

### Dialer UI & Diagnostics
- **Jetpack Compose Material 3 UI**: Clean Light and Dark modes across `Dial`, `Recents`, and `Contacts` tabs.
- **T9 SmartDial & Contacts**: Instant T9 digit/name search with highlighted matches (`SmartDial`), `libphonenumber` formatting (`PhoneUtils`), contact photos, and quick WhatsApp shortcut (`com.whatsapp` / `com.whatsapp.w4b`).
- **System Call Experience**: Full-screen dark call screen (`CallScreen`), One UI–inspired swipe-to-answer/decline incoming controls (`IncomingSlideControls`), `CallStyle` notifications via foreground `CallService`, ongoing-call banner (`OngoingCallBanner`), speaker/mute/hold/DTMF keypad/record actions, proximity screen-off wake lock, and `tel:` / `sip:` deep links.
- **Built-in SIP Diagnostics**: Live SIP packet inspector (`DiagnosticsScreen`, `SipLogBuffer`) with TX/RX counters, search, filtering, copy, and share.

---

## Architecture

```text
app/src/main/java/com/pp/jiocall/
├── App.kt                        # Application lifecycle, service/network watchers, credential refresh
├── MainActivity.kt               # Edge-to-edge UI host, dial/sip intent handling, 3-tab pager
├── jio/                          # JioFiber / JioAirFiber discovery & HTTPS/HTTP provisioning
│   ├── JioRouter.kt              # Device hash/MAC/+sip.instance, provisioning XML/JSON parsers
│   ├── JioClient.kt              # Auto-discovery, temporary/permanent credentials, OTP verification
│   └── RouterHttp.kt             # Raw HTTP/HTTPS client compatible with router ONT endpoints
├── sip/                          # SIP stack, SDP negotiation, RTP/codec audio & call service
│   ├── SipEngine.kt / SipAccount.kt / SipTransport.kt / SipMessage.kt / Sdp.kt / Digest.kt
│   ├── MediaNegotiation.kt / DialPlan.kt / CallQuality.kt / SipLogBuffer.kt
│   ├── RtpAudio.kt / AmrCodec.kt / AmrFraming.kt / JitterBuffer.kt
│   ├── AudioPlaybackEngine.kt / AudioCaptureEngine.kt / AudioEffectsController.kt / AudioGain.kt
│   ├── CallAudioController.kt / CallRecorder.kt / ToneGenerator.kt
│   └── CallService.kt / CallActionReceiver.kt / BootReceiver.kt
├── data/                         # Persistence, data models, SmartDial index, phone utilities
│   └── Models.kt / Store.kt / ContactItem.kt / SmartDial.kt / PhoneUtils.kt
└── ui/                           # Jetpack Compose screens, ViewModel, reusable call/dial components
    ├── AppViewModel.kt / DialerScreen.kt / RecentsScreen.kt / ContactsScreen.kt
    ├── CallScreen.kt / ConnectScreen.kt / AccountsScreen.kt / SettingsScreen.kt / DiagnosticsScreen.kt
    └── components/               # IncomingSlideControls, OngoingCallBanner, ContactAvatar, ContactQuickActions
```

---

## Tech Stack & Requirements

| Component | Details |
| --- | --- |
| **Android API** | Minimum SDK `28` (Android 9.0) • Target / Compile SDK `37` |
| **Language** | Kotlin `2.2.10` • Java `11` bytecode target |
| **Build Tools** | Gradle `9.6.0` • Android Gradle Plugin `9.5.2` (R8 enabled for release) |
| **UI** | Jetpack Compose BOM `2026.02.01`, Material 3, Lifecycle Compose |
| **Async & Telecom** | `kotlinx-coroutines-android` `1.9.0`, `com.googlecode.libphonenumber` `9.0.44` |
| **Native Deps** | **None** (100% JVM/Android SDK; no C/C++/Rust/NDK toolchain needed) |

---

## Getting Started

### Download Prebuilt Release APK
- Prebuilt signed release APK: [`releases/JioCall-v1.0-release.apk`](releases/JioCall-v1.0-release.apk) (R8-minified, APK Signature Scheme v2).
- Automated CI builds and tagged releases are published under the repository's **Releases** tab via [`.github/workflows/release.yml`](.github/workflows/release.yml).

### 1. Clone the repository
```bash
git clone https://github.com/ap415274-cmyk/SIP-calls-with-jio-fiber-and-jio-airfiber-support-.git
cd SIP-calls-with-jio-fiber-and-jio-airfiber-support-
```

### 2. Build & Install
Open in Android Studio, or build from the command line:

```bash
# Build debug APK
./gradlew assembleDebug

# Build minified release APK
# Signs automatically if local keystore.properties or RELEASE_KEYSTORE_* env vars are set;
# otherwise outputs an unsigned release APK.
./gradlew assembleRelease

# Install debug build on connected device or emulator
./gradlew installDebug
```

On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew` (with `JAVA_HOME` pointing to your JDK 17+/Android Studio JBR).

<details>
<summary>Optional: Configure release signing (local or GitHub Actions)</summary>

- **Local builds**: create a gitignored `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`.
- **GitHub Actions**: configure repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` so tagged workflows (`v*`) attach a signed APK automatically.

</details>

### 3. Quick Setup in App
1. Ensure your Android device is connected to your **JioFiber / JioAirFiber** Wi-Fi network (or VPN/LAN reaching a SIP server).
2. Open **Jio call** -> menu **Connect JioFiber/AirFiber** to auto-detect your router:
   - Choose **Temporary** for instant no-OTP setup on local sessions, or
   - Choose **Permanent** and verify the one-time OTP sent to your registered mobile number.
3. Alternatively, open **Accounts** to configure any standard **SIP (`UDP` / `TCP` / `TLS`)** account manually.

---

## Testing & Verification

```bash
# JVM unit tests (SIP/SDP/RFC 4867 framing, SmartDial, JitterBuffer, AudioGain, JioRouter, etc.)
./gradlew testDebugUnitTest

# Android Lint
./gradlew lintDebug

# Instrumented tests on a connected Android device or emulator (requires MediaCodec + Compose UI test runner)
./gradlew connectedDebugAndroidTest
```

---

## Permissions

Declared in [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml):

- **Network & Router Provisioning**: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` (cleartext HTTP restricted in [`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml) strictly to local ONT endpoints `jiofiber.local.html`, `192.168.29.1`, `192.168.31.1`, and loopback/emulator addresses).
- **Voice & Audio Routing**: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`, `WAKE_LOCK`, `VIBRATE`.
- **Dialer & Incoming Calls**: `READ_CONTACTS`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `MANAGE_OWN_CALLS`.
- **Background SIP Reachability**: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_REMOTE_MESSAGING`, `RECEIVE_BOOT_COMPLETED`.

---

## License

Licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE) and [NOTICE](NOTICE) for full terms and copyright notices.
