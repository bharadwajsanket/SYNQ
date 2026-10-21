# SYNQ v1.5.4 — Release Notes

Complete native, local-first companion suite connecting macOS and Android devices.

SYNQ is developed and maintained by **Sanket Bharadwaj**.

---

## Highlights

- **SYNQ**: Modern unified design featuring authentic Apple Liquid Glass menu bar aesthetics on macOS and Material 3 Expressive on Android.
- **Top-Pinned Menu Bar Popover**: Custom AppKit window pinner ensuring the menu bar panel anchors stably to the top menu bar status item without vertical jumping when views expand.
- **Two-Way Contacts Sync & Favorite Contacts**: Synchronize contacts from Android to macOS with search, full contact directory browsing, and customizable pinned favorite contacts.
- **Local Guest Transfers**: Send and receive files with unverified nearby peers via an ephemeral local HTTP server and QR code pairing without permanent pairing requirements.
- **Zero-Cloud Privacy**: Direct peer-to-peer communication over local Wi-Fi with AES-GCM-256 encryption. No accounts, telemetry, cloud servers, or third-party services.

---

## Core Implemented Capabilities

- **Bidirectional Clipboard Synchronization**: Plain-text clipboard sharing with size limits and sensitive data checks.
- **Streaming File Transfers**: Fast, reliable chunked file transfers with progress reporting, SHA-256 integrity verification, and safe path normalization.
- **Notification Mirroring & Inline Actions**: Native notification forwarding from Android to macOS Notification Center with support for quick replies and remote dismissal.
- **SMS OTP Quick-Copy**: Automated detection of one-time authentication codes in incoming text messages with an actionable copy prompt.
- **Telephony & Call Integration**: Initiate phone calls from macOS using a numeric keypad, contact selection, macOS Services context menu, or system `tel://` URLs.
- **Media Controls**: Control Music and Spotify on macOS from Android (play/pause, next/previous, player volume, and track information).
- **Call Auto-Pause**: Automatically pauses Mac media playback when a phone call rings or becomes active on the paired phone.
- **Battery Status & Alerts**: Live reporting of battery level and charging state with low-battery alerts.
- **Ping & Find Device**: Audible ring or silent ping between paired devices across the local network.
- **Quick Settings Tile**: Android Quick Settings tile for immediate clipboard capture.

---

## Security & Reliability Improvements

- Bounded TCP message framing with strict 64 KB pre-parse limits.
- Strict P-256 ECDH ephemeral key exchange and domain-separated HKDF key derivation.
- Monotonic sequence validation on quick actions to prevent packet replay.
- Fully isolated component intents on Android preventing broadcast hijacking.
- Comprehensive unit test coverage across macOS and Android platforms.

---

## Artifacts & Packaging

- **macOS:** `SYNQ.app` / `SYNQ-macOS.dmg` / `SYNQ-macOS.zip` (Requires macOS 13.0+).
- **Android:** `app-debug.apk` / `SYNQ-Android.apk` (Requires Android 8.0+ / API 26+).

---

## Known Limitations

- **Local Network Required:** Both nodes must reside on the same reachable local network subnet; wide-area network traversal and cloud relays are not supported.
- **Ad-Hoc macOS Signing:** Official builds without a paid Apple Developer certificate use ad-hoc code signing. macOS Gatekeeper may prompt the user on first launch (`xattr -d com.apple.quarantine /Applications/SYNQ.app`).
- **Background Android Restrictions:** Android battery optimizations may require whitelisting SYNQ to maintain persistent Wi-Fi connectivity in standby.
