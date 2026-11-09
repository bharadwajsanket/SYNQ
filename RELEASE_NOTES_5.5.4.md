# SYNQ 5.5.4 — Release Notes
## Mac + Android Integration Update

**Release Date:** November 9, 2026  
**Build:** macOS 5.5.4 (Build 55) · Android 5.5.4 (VersionCode 55)  
**License:** MIT  

SYNQ 5.5.4 delivers a milestone integration release, bringing native interactive **Android Screen Mirroring** to macOS with zero-latency hardware decoding and remote touch injection, **Two-Way Low-Battery Alerts** with configurable thresholds, **Live Call Duration** timers on Mac, and comprehensive reliability and UX polish across both platforms.

---

## 🌟 Release Highlights

1. **Native Interactive Screen Mirroring on Mac:**
   - Dedicated macOS viewer window (`MirrorViewerWindowController`) with hardware-accelerated H.264 video decoding via `AVSampleBufferDisplayLayer` and VideoToolbox.
   - Target 60 FPS smooth video streaming with dynamic resolution and bitrate adaptation (1080p60, 720p60, 720p30).
   - Resizable viewer with automatic aspect ratio locking and orientation handling for seamless portrait and landscape transitions.
   - Fullscreen and Always-On-Top floating window modes for distraction-free multi-tasking.
2. **Interactive Remote Touch & Navigation Control:**
   - Mouse-to-touch translation: clicks, drag gestures, and scroll wheel events are mapped to device display coordinates with rotation awareness.
   - Built-in navigation bar for Android Back (Esc), Home, Recents, and Notifications.
   - Driven natively on Android by `SYNQAccessibilityService` using `dispatchGesture` without requiring root, external tools, or command-line setup.
3. **Synchronized Mirror Audio:**
   - Stream Android audio directly to Mac with low latency using `AudioPlaybackCaptureConfiguration` and playback via `AVAudioEngine` with calibrated jitter buffering.
   - Platform constraint note: Audio injection into target apps' microphone inputs is prohibited on unrooted Android due to OS sandboxing (identical to Apple iPhone Mirroring and scrcpy); phone calls are handled natively by Call Relay.
4. **Two-Way Low-Battery Alerts:**
   - Configurable warning (default 20%) and critical (default 10%) battery thresholds on both macOS and Android.
   - Phone battery low → native macOS notification with sound and device name.
   - Mac battery low → native Android notification with high priority.
   - Per-discharge-cycle deduplication: alerts fire once per threshold and re-arm only after recharging or significant recovery.
   - Quiet Mode integration: warning alerts are silenced while critical alerts reliably punch through.
5. **Live Call Duration Timer on Mac:**
   - Live timer displayed in macOS Call screen and floating Call Overlay window.
   - Starts strictly when the call is answered (`call.connected_at`), updating every second.
   - Freezes when the call ends, and excludes missed or rejected calls.
6. **Full Ecosystem Reliability & Security:**
   - All mirror video frames, audio packets, and touch inputs are encrypted end-to-end with the existing AES-GCM session key.
   - MediaProjection consent dialog shown for every capture session in accordance with Android privacy standards.
   - Zero external scrcpy dependencies, zero cloud relays, and zero accounts required.

---

## 🖥️ Screen Mirroring Architecture

- **Android Encoder:** `ScreenMirrorService` runs as a foreground mediaProjection service. It configures `MediaCodec` for `video/avc` hardware encoding into a `VirtualDisplay`. Extracted Annex-B NAL units (SPS, PPS, IDR, P-frames) are transmitted over the encrypted connection.
- **macOS Decoder:** `MirrorViewerWindow` creates a `CMVideoFormatDescription` from received parameter sets and enqueues `CMSampleBuffer`s directly into an `AVSampleBufferDisplayLayer` for zero-copy GPU presentation.
- **Input Forwarding:** `MirrorFrameNSView` intercepts mouse down, dragged, up, and scroll events, computes normalized viewport coordinates (0.0 to 1.0, Y-flipped), and transmits `mirror.input` payloads.
- **Android Injection:** `SYNQAccessibilityService` translates normalized coordinates to actual screen pixels and invokes `dispatchGesture` for taps, drags, and swipes. Global actions handle system navigation.
- **Consent Flow:** `MirrorConsentActivity` provides a translucent prompt to request screen capture consent from the user per Android security guidelines.

---

## 🔋 Two-Way Low-Battery Alerts

- **Android Settings:** Configurable sliders in Settings under "Battery Alerts" for Warning (5%-50%) and Critical (1%-25%) thresholds.
- **macOS Settings:** In macOS Settings, thresholds can be adjusted with automatic clamping and persistence.
- **Deduplication Engine:** `BatteryAlertManager.kt` on Android and `BatteryAlertCoordinator.swift` on macOS track alert states (`hasAlertedWarning`, `hasAlertedCritical`) for each discharge cycle, resetting only when charging resumes or level rises by >= 5%.

---

## ⏱️ Live Call Duration

- **Trigger:** Sourced from Android Telecom `Call.STATE_ACTIVE` callback and telephony offhook detection in `SYNQInCallService` and `SYNQNotificationListenerService`.
- **Transmission:** Dispatches `call.connected_at` with exact millisecond timestamp.
- **macOS UI:** Displayed in `CallOverlayWindow` and main panel call card via `CallDurationView`, formatted as `mm:ss` or `hh:mm:ss`.

---

## 📦 Verification and Automated Tests

- **Android Unit Tests:** 40+ unit tests executed including `BatteryAlertManagerTest`, `ManifestPermissionsTest`, `SYNQSettingsTest`, `ForwardedNotificationRegistryTest`, `ReliabilityTest`. Result: **BUILD SUCCESSFUL, 0 failures**.
- **macOS Unit Tests:** 135 XCTest unit tests executed including `BatteryAlertCoordinatorTests`, `SYNQSettingsTests`, `CallRequestTests`, `ReliabilityTests`, `QuickActionsTests`. Result: **135 passed, 0 failures**.
- **macOS App Build:** Built cleanly with `./build-app.sh` into `.build/debug/SYNQ.app` with ad-hoc designated code signing requirement `identifier "dev.synq.mac"`.
- **Android APK Build:** Built cleanly with `./gradlew :app:assembleDebug` into `android/app/build/outputs/apk/debug/app-debug.apk`.

