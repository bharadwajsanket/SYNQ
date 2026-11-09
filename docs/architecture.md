# Architecture & System Design — SYNQ 5.5.4

SYNQ is a local-first, peer-to-peer companion application linking macOS and Android devices over the local network (LAN) without intermediate cloud servers, relays, or external accounts.

---

## 1. High-Level Architecture Overview

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                              macOS Host                                 │
│                                                                         │
│  ┌────────────────────────┐                   ┌───────────────────────┐ │
│  │     SwiftUI Views      │                   │   Media Controller    │ │
│  │ (MenuBarExtra/Popover) │                   │ (Apple Music/Spotify) │ │
│  └───────────┬────────────┘                   └───────────┬───────────┘ │
│              │                                            │             │
│              ▼                                            ▼             │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │                    PairingCoordinator (Session)                    │ │
│  │      - Session State Machine (Idle, Connecting, Verifying, Con.)   │ │
│  │      - Feature Gating & Server-Side Enforcement                    │ │
│  └─────────────────────────────────┬──────────────────────────────────┘ │
│                                    │                                    │
│  ┌─────────────────────────────────▼──────────────────────────────────┐ │
│  │                Cryptographic & Framing Subsystem                   │ │
│  │      - Ephemeral P-256 ECDH + HKDF-SHA-256 Key Derivation          │ │
│  │      - Hardware Identity Keys (macOS Keychain)                     │ │
│  │      - AES-GCM-256 Authenticated Encryption                        │ │
│  │      - Newline-Delimited JSON Framing (64 KiB strict cap)          │ │
│  └─────────────────────────────────┬──────────────────────────────────┘ │
│                                    │                                    │
│  ┌─────────────────────────────────▼──────────────────────────────────┐ │
│  │                     Network Layer (NWConnection)                   │ │
│  │      - Bonjour/DNS-SD Publishing & Discovery (_synq._tcp.local.)  │ │
│  │      - Raw TCP Socket Transport                                    │ │
│  └─────────────────────────────────┬──────────────────────────────────┘ │
└────────────────────────────────────┼────────────────────────────────────┘
                                     │ Encrypted Local TCP Stream
                                     │ (Wi-Fi / Ethernet LAN)
┌────────────────────────────────────┼────────────────────────────────────┐
│  ┌─────────────────────────────────▼──────────────────────────────────┐ │
│  │                 Network Layer (Socket / ServerSocket)              │ │
│  │      - Android NSD (Network Service Discovery)                     │ │
│  │      - Camera2 QR Code Scanner Fallback                            │ │
│  └─────────────────────────────────┬──────────────────────────────────┘ │
│                                    │                                    │
│  ┌─────────────────────────────────▼──────────────────────────────────┐ │
│  │                Cryptographic & Framing Subsystem                   │ │
│  │      - Ephemeral P-256 ECDH + HKDF-SHA-256 Key Derivation          │ │
│  │      - Hardware Identity Keys (Android KeyStore)                   │ │
│  │      - AES-GCM-256 Authenticated Encryption                        │ │
│  │      - Block-Buffered Line Reader (64 KiB strict cap)              │ │
│  └─────────────────────────────────┬──────────────────────────────────┘ │
│                                    │                                    │
│  ┌─────────────────────────────────▼──────────────────────────────────┐ │
│  │                 SYNQConnectionService (Foreground)                 │ │
│  │      - Persistent Protocol Dispatch & Replay Filtering             │ │
│  │      - Feature State Synchronization & Policy Checks               │ │
│  └───────────────────┬────────────────────────────┬───────────────────┘ │
│                      │                            │                     │
│                      ▼                            ▼                     │
│  ┌────────────────────────────────────┐  ┌────────────────────────────┐ │
│  │     RemoteMediaSessionManager      │  │    Jetpack Compose UI      │ │
│  │   - System MediaSession (Lock Scrn)│  │   - Material 3 Dashboard   │ │
│  │   - Notification.MediaStyle Shade  │  │   - Now Playing Card       │ │
│  └────────────────────────────────────┘  └────────────────────────────┘ │
│                                                                         │
│                             Android Peer                                │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. macOS Client Architecture

- **Runtime & UI:** Built with Swift 5.9+ and SwiftUI, designed as a lightweight `MenuBarExtra` accessory.
- **`PairingCoordinator`:** Central conductor managing the local listener, outgoing connections, cryptographic handshake state, active session, and feature routing.
- **`MediaController`:** Modular media remote engine:
  - Queries running instances of `com.apple.Music` and `com.spotify.client` via AppleScript.
  - Queries occur on a background thread (`DispatchQueue.global(qos: .userInitiated)`) using non-blocking dispatch groups.
  - Generates 128px JPEG thumbnails (<16 KB) from raw Apple Music descriptors or Spotify CDN image URLs.
  - Ephemerally caches artwork using compound keys (`id|title|artist`).
  - Gated server-side by `settings.global.media`.
- **`FileTransferWindow`:** Dedicated floating HUD presenting active pipelined transfers with progress tracking, image thumbnails, and drag-and-drop into Finder.
- **`GuestTransferManager`:** Runs an embedded local HTTP server bound to an ephemeral port for zero-install visitor uploads.

---

## 3. Android Client Architecture

- **Runtime & UI:** Written in Kotlin with Jetpack Compose following Material 3 Expressive guidelines.
- **`SYNQConnectionService`:** Bound foreground service that maintains the socket connection, handles heartbeat keepalives, and routes decrypted protocol messages.
- **`RemoteMediaSessionManager`:**
  - Manages a system `android.media.session.MediaSession`.
  - Exposes playback status and controls to the lock screen and notification shade via `Notification.MediaStyle`.
  - Clears cleanly (`clear()`) when no media is playing (`state.title.isBlank()`), eliminating phantom empty notifications.
  - Binds ongoing notification status to active playback (`.setOngoing(state.playing)`).
- **`QuickActions`:** Exposes client-side reactive models for media transport, clipboard links, battery reporting, and device alarms.
- **`QRScanner`:** Camera2 implementation with aspect-ratio correction, animated reticle, and anti-SSRF address validation.

---

## 4. Pairing & Authentication (SAS Ceremony)

```text
Mac (Initiator/Receiver)                        Android (Receiver/Initiator)
        │                                                     │
        │ 1. mDNS Discovery / QR Code Scan                    │
        │    (Device ID, Device Name, Port, Ephemeral Salt)   │
        ├────────────────────────────────────────────────────>│
        │                                                     │
        │ 2. TCP Socket Connected                             │
        │<───────────────────────────────────────────────────>│
        │                                                     │
        │ 3. pairing.offer (Ephemeral P-256 Public Key A)     │
        ├────────────────────────────────────────────────────>│
        │                                                     │
        │ 4. pairing.answer (Ephemeral P-256 Public Key B)    │
        │<────────────────────────────────────────────────────┤
        │                                                     │
        │ === Ephemeral ECDH: Shared Secret Z Derived ===     │
        │ === HKDF-SHA-256: Session Keys & SAS Derived ===    │
        │                                                     │
        │ 5. Display 6-Digit SAS Code (Both Screens)          │
        │    e.g., "482910"                                   │
        │                                                     │
        │ [User Confirms Match on Both Devices]               │
        │                                                     │
        │ 6. pairing.confirm (HMAC Proof + Identity P-256 Key)│
        │<───────────────────────────────────────────────────>│
        │                                                     │
        │ === Identity Pinned to Keychain / KeyStore ===      │
        │ === Session Authenticated: Transition to Connected =│
```

- **Short Authentication String (SAS):** A 6-digit verification code calculated from the first 4 bytes of the derived verification key modulo 1,000,000 is rendered on both device screens.
- **Single-Tap Synchronization:** The Mac UI tracks `isLocalConfirmed`. Confirming in either the onboarding window or the menu bar popover updates both surfaces to `"Confirmed. Waiting for [peer]…"`.
- **Identity Pinning:** Devices store long-term P-256 public identity keys in macOS Keychain and Android KeyStore. Subsequent reconnections verify identity keys via cryptographic signature without re-displaying the 6-digit SAS code.

---

## 5. Transport & Framing

- **Wire Format:** Newline-delimited (`\n`) JSON frames sent over standard TCP.
- **Strict Frame Bound:** Enforced 65,536-byte (64 KiB) maximum frame bound prior to JSON deserialization. Oversized frames immediately drop the connection.
- **Encrypted Envelopes:**
  ```json
  {
    "kind": "quick.request",
    "sessionId": "b6a8f110-3889-4fa2-bc46-51e3c804fbf7",
    "messageId": "e302ab55-08e1-4566-a67b-1cb81c3c90cb",
    "nonce": "dGhpcyBpcyBhIDEy",
    "ciphertext": "aGVsbG8gd29ybGQgYWVzLWdjbS1jaXBoZXJ0ZXh0..."
  }
  ```
- **Payload Encryption:** AES-GCM-256 with 12-byte initialization vectors and 16-byte authentication tags. Outer fields (`kind`, `sessionId`, `messageId`) are routed in plaintext JSON.

---

## 6. Clipboard Synchronization

- **Formats:** UTF-8 plain text and rich HTML with a mandatory plain-text fallback.
- **Payload Bounds:** 32 KiB content limit per frame.
- **Concealment Marker Detection:** Automatically detects and skips clipboard items flagged with sensitive markers by password managers (e.g. `org.nspasteboard.ConcealedType`, `com.agilebits.onepassword`).
- **Echo Suppression:** Tracks SHA-256 content hashes within a sliding time window (3 seconds) to prevent infinite clipboard echo loops between devices.

---

## 7. File Transfer Protocol

- **Sliding-Window Pipelining:** Up to 64 in-flight unacknowledged chunks streamed concurrently without per-chunk stop-and-wait stalls.
- **Chunk Sizing:** 24 KiB frames with cumulative block acknowledgments emitted every 16 chunks.
- **Receiver Buffering:** Android and macOS allocate 64 KiB socket buffers and 256 KiB file streams for high-speed local disk I/O.
- **Integrity & Atomic Renaming:** Incoming files are written to isolated temporary files (`.part`). When the final chunk arrives, the full file SHA-256 checksum is validated against the sender's declared hash before atomic rename into the destination directory.

---

## 8. Android-to-Android Direct Sharing

- **Peer Discovery:** Two Android devices on the same Wi-Fi discover each other via NSD (`_synq._tcp.local.`).
- **Encrypted Pairing:** Identical P-256 ECDH and SAS 6-digit confirmation ceremony as Mac-to-Android pairing.
- **Strict Companion Feature Isolation:** To preserve device privacy and boundary clarity, Android-to-Android pairings strictly disable companion features (calls, SMS, notifications, clipboard auto-sync, Ring/Ping). Only high-speed encrypted file transfer (`SYNQFeature.FILES`) is negotiated and allowed.
- **Multi-File Batching:** Supports multi-document selection (`OpenMultipleDocuments`) and bulk queueing.

---

## 9. Media-Control Architecture

### macOS Side
- **Engine:** `MediaController.swift`.
- **Query Pipeline:** Parallel asynchronous AppleScript commands executed via `osascript` targeting:
  - `com.apple.Music`: Track name, artist, player state, player position, track duration, sound volume, raw artwork data.
  - `com.spotify.client`: Track name, artist, player state, player position, track duration, sound volume, artwork URL.
- **Arbitration:** If both players are running, the actively playing instance (`playing == true`) takes precedence over paused or idle sessions.
- **Artwork Downsampling:** Raw image data is downsampled via `CGImageSourceCreateThumbnailAtIndex` into a 128px JPEG thumbnail (<16 KB) and base64-encoded.
- **Permission Guard:** Gated by `settings.global.media`. If disabled, `MediaController` resets state, halts periodic polling, drops outgoing broadcasts, and rejects incoming commands with `accepted: false`.

### Android Side
- **Engine:** `RemoteMediaSessionManager.kt`.
- **System Integration:**
  - Creates and manages an active `MediaSession`.
  - Sets `MediaMetadata` (title, artist, album, duration, album art bitmap).
  - Configures `PlaybackState` (state code, playback position, playback speed, actions: play/pause, skip to next, skip to previous, seek to).
  - Posts a `Notification.MediaStyle` notification to the `synq_remote_media` channel.
- **Lifecycle Control:** Dismisses notification and releases session immediately when `state.title.isBlank()`.

---

## 10. Over-The-Air (OTA) Update Architecture

- **Manifest:** `release-manifest.json` hosted on GitHub Releases containing semantic version, platform builds, download URLs, file sizes, and SHA-256 hashes.
- **Android (`SYNQUpdateManager`):**
  - Compares semantic versioning (`versionCode > currentVersionCode`).
  - Downloads APK in background with notification progress.
  - Verifies payload SHA-256 hash.
  - Verifies release signing certificate fingerprint against embedded pinning (`ad2eeabb...`).
  - Launches Android `PackageInstaller` session.
- **macOS (`SYNQUpdateManager.swift`):**
  - Offers 3 user settings: *Download & Install*, *Only Download* (to `~/Downloads`), and *Check & Inform*.
  - Validates payload SHA-256 hash before mounting the DMG volume and launching the installer.

---

## 11. Screen Mirroring Architecture (Flagship 5.5.4)

- **Android Screen Capture (`MediaProjection` + `MediaCodec`):**
  - Prompted per session using transparent `MirrorConsentActivity` ensuring platform privacy consent.
  - Video hardware encoding via `MediaCodec` (H.264 / AVC Baseline/Main profile).
  - VirtualDisplay output mapped to `createInputSurface()` with dynamic resolution and frame rate (up to 60 FPS, 1080p).
  - SPS/PPS parameter extraction and Annex-B NAL formatting.
- **macOS Viewer (`AVSampleBufferDisplayLayer` + VideoToolbox):**
  - Dedicated viewer window (`MirrorViewerWindowController`) with custom aspect ratio constraints.
  - Zero-latency hardware decoding via `CMVideoFormatDescriptionCreateFromH264ParameterSets` and `CMSampleBuffer`.
  - Fullscreen, Always-On-Top, and dynamic rotation support.
- **Remote Touch & Navigation Input Injection:**
  - Coordinates normalized (0.0 to 1.0) and transformed on device display coordinates.
  - Handled by `SYNQAccessibilityService` using standard `dispatchGesture` (taps, drags, swipes, scrolls) and `performGlobalAction` (Back, Home, Recents) without root or external ADB binaries.
  - Two-finger scrolling anchored safely in the neutral viewport center with a 25px displacement deadzone, preventing accidental clicks on interactive UI elements under the cursor.
- **Audio Pipeline & Android OS Sandboxing Constraint:**
  - **Device Audio Playback:** Android internal audio is captured via `AudioPlaybackCaptureConfiguration` (Android 10+) and streamed to Mac `AVAudioEngine` with a calibrated 45ms jitter buffer for synchronized audio/video playback.
  - **Microphone Routing Constraint:** Non-rooted Android sandboxes audio recording, strictly restricting the hardware microphone HAL (`AudioRecord`) to a single foreground capturing application. Third-party Android apps cannot inject simulated microphone streams into other apps (e.g. WhatsApp, Phone, Google Meet) without root or system privileges. This is an architectural platform security restriction shared with official macOS iPhone Mirroring and scrcpy. Native phone calls in SYNQ are handled by Call Relay (`SYNQInCallService`), which routes audio directly through the telecom framework.

---

## 12. Two-Way Low-Battery Alerts

- **Threshold Management:**
  - Configurable Warning (default 20%) and Critical (default 10%) thresholds on both platforms.
- **Deduplication:**
  - State tracking per discharge cycle ensures only one notification is generated per threshold until charging resumes or level rises by >= 5%.
- **Quiet Mode Integration:**
  - Low-battery warnings are suppressed or silenced during Quiet Mode, while critical battery alerts reliably bypass Quiet Mode.

---

## 13. Live Call Duration Timer

- **Precise Call Connect Detection:**
  - Telecom `Call.STATE_ACTIVE` callback and telephony offhook detection trigger `call.connected_at` with true timestamp.
- **macOS Display:**
  - Live 1-second interval timer on macOS Call screen and floating call overlay.
  - Freezes upon call termination and excludes missed or rejected calls.
