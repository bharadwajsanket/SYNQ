# SYNQ

**Your devices, working together seamlessly over your local network.**

SYNQ is an independent, open-source, local-first companion that connects your Mac and Android phone. Copy text on your Mac and paste it on your phone, share files instantly with drag-and-drop, catch Android notifications on macOS with inline quick replies, dial phone calls straight from your menu bar, and control media playback—all without external accounts, cloud servers, or third-party telemetry.

The project is designed and led by **Sanket Bharadwaj**.

---

## Downloads

Get the latest version of SYNQ for your devices.

| Platform | Download | Minimum requirement |
|:--|:--|:--|
| <img src="https://cdn.simpleicons.org/apple" width="16" height="16" alt="Apple" /> **macOS** | **[Download DMG](https://github.com/bharadwajsanket/SYNQ/releases/download/v1.5.4/SYNQ-macOS.dmg)** · [ZIP archive](https://github.com/bharadwajsanket/SYNQ/releases/download/v1.5.4/SYNQ-macOS.zip) | macOS 13 Ventura or later |
| <img src="https://cdn.simpleicons.org/android" width="16" height="16" alt="Android" /> **Android** | **[Download APK](https://github.com/bharadwajsanket/SYNQ/releases/download/v1.5.4/SYNQ-Android.apk)** | Android 8.0 (API 26) or later |

**Latest release:** [v1.5.4 · View release notes and all assets](https://github.com/bharadwajsanket/SYNQ/releases/tag/v1.5.4)

### Verify your download

Verify downloaded files against the published SHA-256 checksums.

[**SHA256SUMS.txt**](https://github.com/bharadwajsanket/SYNQ/releases/download/v1.5.4/SHA256SUMS.txt)

### Prefer building from source?

You can also build both applications yourself using the instructions below.

---

## ✨ Features at a Glance

- **📋 Bidirectional Clipboard:** Seamlessly sync text between macOS and Android with size validation and sensitive-data protections.
- **📁 Streaming File Transfer:** Share files and folders in either direction with live progress, SHA-256 chunk validation, and safe path normalization.
- **🔔 Notification Mirroring:** Forward phone alerts directly to macOS Notification Center, complete with inline quick replies and remote dismissal.
- **🔑 SMS OTP Quick-Copy:** Automatically detect one-time verification codes in incoming text messages and copy them with a single click on macOS.
- **📞 Phone Calls from Mac:** Start outgoing phone calls from your menu bar keypad, contact search, macOS Services context menu, or system `tel://` URLs.
- **👥 Contact & Favorites Sync:** Browse your synced phone directory and pin favorite contacts right inside the macOS menu bar panel.
- **🎵 Media Controls:** Control Music and Spotify playback across devices, complete with automatic pause when incoming calls ring.
- **🔋 Battery Monitoring:** View live battery percentage and charging state with configurable low-battery notifications on macOS.
- **🤝 Local Guest Transfer:** Send and receive files with nearby devices over local Wi-Fi via temporary QR codes without permanent pairing.
- **🔒 True Local Privacy:** Mutually authenticated sessions using P-256 ECDH key agreements and AES-GCM-256 encrypted frames. No cloud servers, no accounts, and no data tracking.

---

## 🚀 Quick Start: Pairing Your Devices

1. Connect your Mac and Android phone to the same local Wi-Fi network.
2. Launch **SYNQ** on macOS (appears in the menu bar) and open the **SYNQ** app on Android.
3. Your Mac will discover your Android device automatically via Bonjour / mDNS.
4. Click **Pair** and verify the 6-digit confirmation code displayed on both screens.
5. Once accepted, devices establish a persistent, encrypted session and reconnect automatically whenever they are on the same network.

---

## 🛠️ Building from Source

### macOS Application

**Prerequisites:** macOS 13.0+ and Xcode 15+ / Command Line Tools.

```bash
cd macos
# Build the native menu bar app bundle
./build-app.sh

# Or package a distributable disk image (.dmg)
./package-release.sh

# Run the automated test suite (93 tests)
swift test
```

The compiled application bundle will be located at `macos/.build/debug/SYNQ.app` (or `.build/release/SYNQ.app`).

### Android Application

**Prerequisites:** JDK 17+ and Android SDK (API 26+).

```bash
cd android
# Build the debug APK
./gradlew assembleDebug

# Run the unit test suite (57 tests)
./gradlew testDebugUnitTest
```

The generated APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`.

---

## 📚 Technical Documentation

Deep-dive architecture, cryptographic specifications, and policies:

- 🏗️ [**System Architecture**](docs/architecture.md) — Architectural layers, state machines, platform components, and data flows.
- 📡 [**Wire Protocol Specification**](docs/protocol.md) — Frame structures, cryptographic contexts, message formats, and test vectors.
- 🛡️ [**Security & Threat Model**](docs/security-testing.md) — Cryptographic boundaries, path sanitization, and verification suites.
- 🧪 [**Testing Guide**](docs/testing.md) — Automated unit, crypto-integration, and manual verification procedures.
- 📦 [**Release & Packaging Workflow**](docs/releasing.md) — Building, ad-hoc signing, and notarizing release artifacts.
- 📱 [**Telephony & Permissions Policy**](docs/sms-call-policy.md) — Privacy rationale for Android telephone and notification permissions.
- 🔐 [**Security Advisory & Policy**](SECURITY.md) — Vulnerability reporting guidelines and supported versions.

---

## 👤 Project Leadership & License

SYNQ is an independent open-source project designed and led by **Sanket Bharadwaj**.

Licensed under the [MIT License](LICENSE).
