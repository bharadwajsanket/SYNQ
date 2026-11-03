# Changelog

All notable changes to **SYNQ** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [4.5.4] - 2026-11-02

### Added
- **Remote Media Control for Apple Music & Spotify:** Real-time playback control, track metadata, artist info, album artwork thumbnails, timeline progress, and volume control across Apple Music and Spotify on macOS.
- **Independent Dual-Source Playback Detection:** Automatically queries Apple Music and Spotify states in parallel, intelligently preferring the actively playing source over paused sessions.
- **Android Media Source Selector:** Added interactive source selector chip and dropdown menu directly on Android's `NowPlayingCard`, enabling manual source selection between Apple Music and Spotify or automatic playback-following.
- **Android System MediaSession & Lock Screen:** Synchronized system `MediaSession` with `MediaStyle` notification shade controls and lock-screen playback controls (title, creator, artwork, play/pause, next, previous, seek) mirroring active Mac playback.
- **Spotify CDN Album Artwork Pipeline:** Added automated HTTPS cover image retrieval for Spotify tracks, converting CDN images into optimized 128px JPEG thumbnails (<16 KB) for local transfer to Android.
- **macOS Media Access Control Toggle:** Added dedicated security toggle in macOS Settings under General → Media (*"Allow connected devices to control media — Let paired devices view and control media playing on this Mac"*), enabled by default and enforced server-side.
- **Synchronized Pairing Confirmation:** Added `isLocalConfirmed` in `PairingCoordinator` and `isConfirmed` in `QRPairingManager` to unify verification state across the onboarding window and menu bar popover.

### Improved
- **Clean Lock-Screen Notification Lifecycle:** `RemoteMediaSessionManager` ensures the system `MediaSession` is only active when an actual track is loaded or playing (`state.title.isNotBlank()`). Stopped or cleared states cleanly dismiss the notification.
- **Scoped UI Copy:** Updated all empty-state labels on macOS and Android to explicitly reflect the supported media scope: `"Open Music or Spotify on your Mac"`.
- **Menu Bar Extra Geometry:** Removed programmatic window frame mutations that caused the popover to creep over the macOS menu bar; clamped window top boundary strictly to `visibleFrame.maxY`.
- **Menu Bar Popover Sizing:** Added adequate height (540pt for Call, 530pt for Guest Share) and top padding (18pt) to prevent truncation of titles, back buttons, and bottom action bars.

### Fixed
- **Fixed Phantom Lock-Screen Notifications:** Resolved an issue where Android displayed an empty media notification on the lock screen with `"No title"` and placeholder controls when no track was playing on Mac.
- **Fixed Missing Spotify Thumbnails:** Resolved issue where Spotify tracks displayed without album artwork on Android by adding Spotify CDN image extraction and caching to `MediaController.swift`.
- **Fixed Duplicate "Codes Match" Prompt:** Resolved issue where confirming the 6-digit verification code in the Welcome onboarding window caused the popover to ask for confirmation a second time.
- **Fixed Concurrency Deadlock in Media Gathering:** Replaced blocking `group.wait()` calls on serial queues with non-blocking `DispatchQueue.global(qos: .userInitiated)` and `group.notify`.
- **Fixed MainActor Concurrency Warnings in Media Controller:** Safely isolated artwork cache mutations to `@MainActor` without cross-actor reference warnings.

### Performance
- **Ephemeral Artwork Caching:** Media artwork is cached in-memory using compound keys (`id|title|artist`). Subsequent state updates for the same track bypass re-compression and network downloads.
- **Bounded Artwork Payloads:** All album artwork is strictly downsampled to 128px JPEG thumbnails capped at 16 KB, preventing local socket congestion.

### Security and Reliability
- **Server-Side Media Gating:** Toggling off media control in macOS Settings immediately terminates outgoing `media.state` broadcasts, clears the Android remote lock-screen session, and rejects all incoming media control requests server-side.
- **Single-Source Enforcement:** The selected media source is consistently maintained across Android Home, system `MediaSession`, lock screen, and notification shade.

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
