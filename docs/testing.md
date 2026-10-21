# Testing Guide — SYNQ

This document details the automated test suites, integration fixtures, and manual hardware verification procedures for SYNQ across macOS and Android.

---

## 1. Automated Test Suites

### macOS Unit & Integration Tests

The macOS test suite is built on XCTest and Swift Testing, covering protocol serialization, cryptographic key derivation, menu bar layouts, settings persistence, and service providers.

- **Working Directory:** `macos/`
- **Execution Command:**
  ```bash
  swift test
  ```
- **Verified Coverage (92 unit tests):**
  - `DiscoveryTXTRecordTests`: DNS-SD record parsing and safety checks.
  - `ProtocolCryptoIntegrationTests`: Cross-platform crypto test vectors against `crypto-v1.properties`.
  - `ClipboardPayloadTests` & `ClipboardAutoSyncTests`: Bounded payload decoding and automatic synchronization.
  - `FileShareTests` & `FileTransferAutoDismissTests`: Chunked file transfer, cancellation, and auto-dismiss behavior.
  - `NotificationHistoryTests` & `NotificationIdentityTests`: Idempotency, retention, and content hashing.
  - `NotificationCallTests` & `CallRequestTests`: Call notification parsing, state changes, and service provider actions.
  - `PhoneURLHandlerTests`: Validation and queuing of system `tel://` URLs.
  - `QuickActionsTests`: Replay attack rejection, sequence monotonicity, link handling, and media commands.
  - `ReliabilityTests`: Reconnect exponential backoff, malformed frame rejection, and partial file cleanup.
  - `SYNQSettingsTests` & `SYNQDiagnosticsTests`: Feature flags, overrides, and privacy-preserving diagnostics.
  - `MenuBarPanelLayoutTests`: Window positioning, sizing bounds, and top-pinning geometry.

### Android Unit & Integration Tests

The Android test suite runs on the JVM via Gradle and Robolectric/JUnit, exercising background services, discovery parsing, permissions, and cryptographic parity.

- **Working Directory:** `android/`
- **Execution Command:**
  ```bash
  ./gradlew testDebugUnitTest
  ```
- **Verified Coverage (57 unit tests):**
  - `ProtocolCryptoIntegrationTest`: Cross-platform verification of P-256 ECDH, HKDF session keys, and AES-GCM-256.
  - `NearbyDeviceDeduplicationTest`: Multi-interface discovery deduplication.
  - `ClipboardPayloadTest`: Payload size bounds, text formatting, and HTML stripping.
  - `ForwardedNotificationRegistryTest`: Deduplication and expiration of forwarded notification tokens.
  - `NotificationSyncFilterTest`: Application blacklist filtering, self-notification filtering, and category checks.
  - `QuickActionsTest`: Inbound action dispatching and sequence checking.
  - `ReliabilityTest`: Stream interruption handling and temporary file cleanup.
  - `RemoteCallRequestTest`: Intent dispatch safety and confirmation boundaries.
  - `SYNQSettingsTest` & `SYNQDiagnosticsTest`: Device-specific feature policies and sanitized diagnostic exports.
  - `ManifestPermissionsTest` & `MainActivityManifestTest`: Manifest permission boundary checks.

---

## 2. Cross-Platform Cryptographic Vectors

Both macOS and Android implementations are tested against the shared test vector file at `protocol/test-vectors/crypto-v1.properties`:

- **Identity Key Pairs:** Known P-256 private and public keys.
- **Shared Secret:** Ephemeral ECDH shared secret computation.
- **Derived Keys:** Session encryption keys, IVs, and SAS verification codes.
- **Payload Encryption:** AES-GCM ciphertext vectors with 12-byte nonces and 16-byte authentication tags.

Running `swift test` and `./gradlew testDebugUnitTest` validates that both platforms produce identical cryptographic outputs for identical inputs.

---

## 3. Manual Device Acceptance Testing

Physical hardware testing verifies interactions that cannot be simulated entirely on CI (such as Bluetooth/Wi-Fi transitions, audio playback, and system phone dialers).

### Prerequisites
- macOS machine running macOS 13.0+ on local Wi-Fi.
- Physical Android phone running Android 8.0+ connected to the same Wi-Fi network.

### Checklist

| Domain | Action | Expected Behavior |
| :--- | :--- | :--- |
| **Pairing** | Tap Pair on macOS when Android is discovered | 6-digit confirmation code matches on both screens; session establishes upon mutual acceptance. |
| **Top-Pinned Popover** | Click the SYNQ menu bar icon; open the Contact Picker or Call Dialer | Popover expands downward smoothly; top edge remains firmly anchored to the status item without jumping. |
| **Clipboard Sync** | Copy text on macOS | Notification or direct update on Android with the copied text; reverse direction works via Quick Settings tile. |
| **File Transfer** | Drag file onto Mac popover or use Android Share | Live progress bar displayed; file saved to destination; SHA-256 verified upon completion. |
| **Phone Calls** | Enter phone number or select contact on macOS and click Call | Android prompts with dialer or initiates call; active call UI appears on macOS. |
| **Contacts Sync** | Enable Contacts Sync in Android settings | Full contacts list syncs to macOS; favorite contacts can be pinned and unpinned. |
| **Notifications** | Trigger an app notification on Android | macOS displays native notification; quick reply sends text back to the Android app. |
| **SMS OTP** | Receive a login SMS with a verification code | Dedicated OTP prompt appears on macOS with a single-click "Copy Code" button. |
| **Media Controls** | Start track in Music or Spotify on macOS | Android displays now-playing title and artist; play/pause and volume controls respond. |
| **Call Auto-Pause** | Receive phone call while media is playing | Mac media pauses immediately; stays paused after the call ends. |
| **Find Device & Ping**| Trigger Ping or Ring from either device | Remote device plays alert sound or displays ping banner; cancels cleanly. |
| **Lifecycle** | Toggle Wi-Fi or restart app | Device reconnects automatically using pinned identity keys without re-pairing. |
