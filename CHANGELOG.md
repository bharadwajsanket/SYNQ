# Changelog

All notable changes to **SYNQ** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [3.5.4] - 2026-11-01

### Added
- **Apple & Material 3 Dark-First Redesign:** Overhauled Android dashboard and Settings navigation to match the Apple-inspired modern dark theme. Categorized Settings into 5 dedicated sections (Sync, Calls & Contacts, Privacy & Local Security, General, About) with masked phone numbers for privacy.
- **In-App Over-the-Air (OTA) Updates:**
  - Android: `SYNQUpdateManager` with cryptographic SHA-256 validation, release signing certificate fingerprint verification, background download, and direct PackageInstaller session dispatch.
  - macOS: Dedicated Software Update view in Settings with 3 distinct options (*Download & Install*, *Only Download* to `~/Downloads` with Finder reveal, and *Check & Inform*), non-intrusive inline status reporting, and automatic SHA-256 verification.
- **Pipelined Sliding-Window File Streaming:** Upgraded transfer protocol to support up to 64 in-flight unacknowledged chunks with cumulative block ACKs every 16 chunks, eliminating synchronous stop-and-wait round-trips.
- **24 KiB Streaming with 64 KiB Receiver Support & 256 KiB Buffering:** Streamlined transmission sizing and MediaStore streaming buffers for smooth local-network transfers.
- **QR Code Pairing on macOS:** Added menu-bar QR code generator publishing temporary pairing invitations (`synq://pair?...`).
- **Live QR Scanner on Android:** Camera2-powered live scanner with animated reticle, permission handling, camera aspect-ratio correction, and manual URI fallback.
- **Security & Anti-SSRF Protection:** Restricted pairing hosts to RFC 1918 private IPv4, link-local IPv6, and `.local` mDNS domains. Added 120-second session expiration and single-use token consumption.
- **Direct Android-to-Android File Transfers:** Enabled direct peer discovery, key agreement, and encrypted file transfers between two Android devices over local Wi-Fi.
- **Strict Android Companion Isolation:** Explicitly prohibited phone-companion features (calls, SMS, notifications, clipboard auto-sync, Ring/Ping) between Android devices to enforce file-transfer-only scoping.
- **Multi-File Batch Selection:** Upgraded Android file picker to `OpenMultipleDocuments`.
- **Dynamic Menu Bar Connection Indicator:** Added three-state visual indicator on macOS (solid bridge line for Connected, pulsing dashed line for Connecting/Reconnecting, hidden bridge line for Disconnected) with zero idle CPU overhead and full support for system reduced-motion accessibility.
- **Animated First-Launch Welcome Card:** Interactive animated connection line and travelling pulse for first-time onboarding.

### Changed
- Refactored `PairingCoordinator` and `Pairing.swift` to identify peer platforms (`android` vs `macos`) and negotiate capabilities symmetrically.
- Optimized Android protocol line reader with block-based buffering.
- Standardized cross-platform confirmation proofs and SAS 6-digit verification across macOS and Android.
- Squeezed popover footer restored to clean 3-button layout (`Settings`, `Guest Share`, `Quit`) removing label truncation.

### Fixed
- Fixed sequential NSD discovery resolve queue on Android, preventing `FAILURE_ALREADY_ACTIVE` and phantom duplicate peer listings.
- Fixed QR Scanner distorted/stretched camera preview by dynamically calculating optimal aspect-ratio matching sensor output.
- Fixed missing `invalidateSession(reason:)` method on macOS `QRPairingManager`.
- Consolidated `uniqueDestinationURL` implementation in `Reliability.swift` to prevent symbol fragmentation across file transfer components.
- Corrected heartbeat timeout documentation in test assertions to reflect actual 30s threshold.

---

## [2.5.4] - 2026-10-23

### Added
- Native in-panel Ring and Ping feedback replacing system alerts.
- Modern AirDrop-style file transfers HUD on macOS with image previews and drag-and-drop.
- In-panel call confirmation overlay.
- Permissions & Privacy overview screen on Android.

### Fixed
- Fixed carrier delivery acknowledgments for SMS messages sent from Mac.
- Fixed Android bottom navigation bar insets and selected-item clipping.

---

## [1.5.4] - 2026-10-15

### Added
- Initial public release of SYNQ.
- P-256 ECDH + AES-GCM local-network pairing.
- Bidirectional clipboard sync, notification mirroring, contacts sync, and remote file browsing.
- Guest file sharing via ephemeral QR code.

