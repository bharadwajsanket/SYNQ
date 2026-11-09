<div align="center">

# SYNQ

### The missing Continuity bridge between macOS & Android.

**Local Wi-Fi &bull; Zero Cloud &bull; Hardware-Backed Encryption &bull; Line-Speed Transfers**

<p align="center">
  <a href="https://github.com/bharadwajsanket/SYNQ/releases/latest/download/SYNQ-macOS.dmg"><img alt="Download macOS DMG" src="https://img.shields.io/badge/macOS-Download%20DMG-000000?style=for-the-badge&logo=apple&logoColor=white"></a>
  &nbsp;
  <a href="https://github.com/bharadwajsanket/SYNQ/releases/latest/download/SYNQ-Android.apk"><img alt="Download Android APK" src="https://img.shields.io/badge/Android-Download%20APK-202124?style=for-the-badge&logo=android&logoColor=3DDC84"></a>
  &nbsp;
  <a href="https://github.com/bharadwajsanket/SYNQ/releases/latest"><img alt="Release v5.5.4" src="https://img.shields.io/badge/Release-v5.5.4-3478F6?style=for-the-badge&logo=github&logoColor=white"></a>
</p>

<p align="center">
  <img src="assets/hero.svg" alt="SYNQ — macOS &amp; Android Continuity Bridge" width="100%">
</p>

</div>

---

Apple built Continuity. Then locked it inside their garden.

If you love your Mac for work and carry an Android phone in your pocket, you know the daily grind: emailing links to yourself, bouncing files through cloud storage, juggling 2FA codes, or reaching for your phone every time a track changes or a notification buzzes.

**SYNQ fixes that.** It links your Mac and Android phone directly over your local network — no accounts, no cloud relays, no tracking, and no command-line tools. Pair once with a quick QR scan, and your devices work together like they were built for each other.

---

## 🌟 The Experience

### 🖥️ SYNQ View — Your phone, living on your Mac
Experience your Android device inside a native, fluid macOS window.

* **60 FPS Hardware Decoding:** Zero-latency H.264 stream decoded natively on your Mac GPU via `AVSampleBufferDisplayLayer` and VideoToolbox.
* **Synchronized Device Audio:** Crystal-clear internal phone audio piped directly to your Mac speakers or AirPods with calibrated 45ms jitter compensation.
* **Natural Mac Trackpad & Mouse:** Smooth two-finger scrolling, swipes, and drag gestures mapped accurately to Android touch coordinates.
* **Desktop Keyboard Shortcuts:** Use `Cmd+A` (Select All), `Cmd+C` (Copy), `Cmd+V` (Paste), `Cmd+X` (Cut), `Cmd+Z` (Undo), and `Enter` / `Shift+Enter` right inside your Android apps.
* **Designed for macOS:** Always-on-Top mode, full-screen support, instant portrait/landscape rotation, and a floating glass HUD that stays completely out of your way.

> [!NOTE]
> **Privacy-First Audio Design:** In accordance with Android OS sandboxing, microphone input from third-party apps is restricted from hijacking system-wide mic HALs (the exact same security standard adhered to by Apple iPhone Mirroring and scrcpy). Phone calls in SYNQ are handled natively through Call Relay (`SYNQInCallService`).

---

### ⚡ SYNQ Link — Pure line-speed file sharing
Move gigabytes in seconds across local Wi-Fi without uploading a single byte to the internet.

* **Full Wi-Fi Throughput:** 64-chunk concurrent pipelining maxes out your local network bandwidth.
* **Bulletproof Integrity:** Streaming SHA-256 checksums verify every block upon arrival.
* **Remote File Browser:** Pull photos, downloads, or documents straight into Finder from your Mac menu bar.
* **Guest Share:** Need someone without SYNQ to send you a file? Generate a local QR code — they scan it and upload directly via their web browser. No app install needed.

---

### 🎵 Remote Media Control — Desktop sound, pocket controls

<p align="center">
  <img src="assets/media-pipeline.svg" alt="Media Pipeline Architecture" width="100%">
</p>

Control your Mac's music playback without touching your keyboard.

* **Apple Music & Spotify Support:** Automatically detects the active music source on your Mac and routes controls.
* **Android Lock-Screen Player:** Scrub tracks, adjust volume, see real-time album artwork, and skip songs directly from Android's native media player.
* **Instant Hand-Off:** Dismisses cleanly when playback is stopped.

---

### 📋 Universal Clipboard & Smart Flow
* **Two-Way Copy & Paste:** Copy a verification code on your phone, paste it in Safari on your Mac. Copy a URL on Mac, tap it on your phone.
* **Smart Browser Links:** Shared URLs launch directly in your preferred browser on either device.
* **Password Manager Shield:** Passwords and sensitive tokens from 1Password, Bitwarden, and Apple Keychain are automatically masked and protected from clipboard sync.

---

### 📞 Menu Bar Calls, SMS & Battery Intelligence
* **Desktop Dialing & SMS:** Dial contacts and compose text messages right from your menu bar.
* **Live Call Timer:** Active call duration timer updates in real time on macOS, synced with the exact instant your call connects.
* **Two-Way Low-Battery Alerts:** Get notified on your Mac when your phone is running low, and alerted on your phone when your MacBook needs a charger.

---

## 🔒 Security & Privacy by Design

SYNQ was built from line one with a strict zero-knowledge, zero-telemetry architecture:

| Security Layer | Implementation |
| :--- | :--- |
| **Network Path** | **100% Local Peer-to-Peer.** Zero packets ever leave your local Wi-Fi network. |
| **Payload Encryption** | **AES-GCM-256** authenticated encryption with unique per-message nonces. |
| **Key Exchange** | Ephemeral **P-256 ECDH** key exchange verified via visual 6-digit SAS code comparison. |
| **Key Storage** | Cryptographic secrets sealed inside **macOS Keychain** and **Android KeyStore**. |
| **Telemetry & Cloud** | **None.** No user accounts, no analytics, no external servers, no tracking. |

---

## 🚀 Quick Start in 30 Seconds

```
1. Download ──► Grab SYNQ for macOS and the Android APK.
2. Open     ──► Launch both apps on the same Wi-Fi network.
3. Pair     ──► Scan the QR code on your Mac with your phone & confirm the 6-digit code.
```

*That’s it. Your devices will now automatically reconnect whenever you're nearby.*

---

## 🛠️ Build from Source

### macOS (Swift & SwiftUI)
```bash
cd macos
./run-tests.sh          # Runs all 135 unit tests
./package-release.sh    # Builds signed DMG & ZIP in macos/dist/
```

### Android (Kotlin & Jetpack Compose)
```bash
cd android
./gradlew testDebugUnitTest    # Runs all unit tests
./gradlew assembleRelease      # Output: app/build/outputs/apk/release/
```

---

## 📚 Deep Dive Documentation

* 🏛️ [Architecture Overview](docs/architecture.md) — System internals and state machines
* 📡 [Protocol & Wire Format](docs/protocol.md) — Frame formats and encryption specs
* 🎧 [Remote Media Specification](docs/media.md) — AppleScript & MediaSession bridge
* 🛡️ [Security & Threat Model](SECURITY.md) — Cryptographic review and threat analysis
* 📝 [Changelog](CHANGELOG.md) · [Release Notes](RELEASE_NOTES_5.5.4.md) — Version 5.5.4 highlights

---

## 📄 License

Open source under the [MIT License](LICENSE).

Crafted with care by **[Sanket Bharadwaj](https://github.com/bharadwajsanket)**.
