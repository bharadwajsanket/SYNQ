# SYNQ v2.5.4 — Release Notes

Release v2.5.4 brings comprehensive UI polish, navigation and layout fixes, in-panel call and device finding feedback, an expanded Android permissions overview, and reliability enhancements across macOS and Android.

SYNQ is developed and maintained by **Sanket Bharadwaj**.

---

## What's New in v2.5.4

### macOS Popover & UI Polish
- **Consistent Action Button Styling:** The "Find Device" action now shares the exact rounded pill capsule styling and height of all other primary grid buttons, maintaining uniform aesthetics across the menu bar popover.
- **In-App Ring & Ping Feedback:** Replaced system notification banners for Ring and Ping with native, real-time in-panel status indicators inside SYNQ (`Ringing Android…`, `Phone is ringing`, `Ping delivered`, `Ringing stopped`, with connection and timeout alerts).
- **Pill-Style Call Back Button:** Aligned the Call from Mac back button with Guest Share's capsule design (`chevron.left` pill).
- **Cleaner Contact Rows & Safe SMS:** Removed redundant dialer icons from favourite contact chips and directory rows (since tapping the contact directly dials). Isolated the SMS button tap handling so sending an SMS never triggers an accidental call.
- **Non-Dismissing Call Confirmation:** Replaced the window-modal alert sheet with an in-panel confirmation overlay. Pressing Cancel, pressing Escape, or tapping the backdrop dismisses only the confirmation dialog and returns cleanly to the Call screen without closing the SYNQ popover.
- **Modern Blip/AirDrop Transfers HUD:** Completely overhauled the File Transfers window into a modern, compact HUD with rounded translucent glass styling, large hero preview card with image thumbnail generation, prominent green circular completion badge, accented filename pill, "Received from [Device]" metadata, and instant-action pill buttons (`Copy` directly to clipboard with image paste support, and `Open ⌄` split button). Supports direct drag-and-drop from the preview card into Finder, Desktop, or apps, and automatically clears the popover transfer badge when closed.
- **Reliable SMS Messaging from Mac:** Compose and send SMS text messages directly to any contact or phone number from the Mac menu bar. Fixed asynchronous carrier delivery acknowledgments so cellular dispatch confirms immediately without false timeout errors.

### Android Navigation & Settings Polish
- **Bottom Navigation Layout Fix:** Material 3 `NavigationBar` anchored properly to system and gesture navigation insets, eliminating empty vertical gaps and selected-state clipping.
- **Find Mac Dropdown Layout:** Redesigned Find Mac control with a standardized `OutlinedButton`, rounded dropdown menu (`16.dp`), leading icons (`Notifications` for alarm, `PlayArrow` for ping), two-line action descriptions, and clean item dividers.
- **About → Permissions Page:** Added a dedicated Permissions & Privacy page explaining each permission used by SYNQ (`INTERNET`, `POST_NOTIFICATIONS`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `CALL_PHONE`, `READ_CONTACTS`, `SEND_SMS`, Storage Access Framework), what feature uses it, whether it is required or optional, and providing a quick link to Android app settings.
- **Settings Footer:** Added a subtle footer in settings reading “Made with ♥ by Sanket” linking directly to [https://github.com/bharadwajsanket](https://github.com/bharadwajsanket).
- **Smart Link Detection & Ack:** Clean bidirectional link delivery with neutral status and peer opened acknowledgments.

### Reliability & Parity
- Reconnection state convergence and automatic listener restarts after network transitions.
- All 113 macOS unit tests and 54 Android unit tests pass with zero regressions.

---

# SYNQ v1.5.4 — Release Notes

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
