# SYNQ v4.5.4 — Release Notes

Release v4.5.4 brings refined Apple Music and Spotify media control from Android, independent multi-source playback detection, interactive source switching on Android, lock-screen and notification shade `MediaSession` integration, dedicated macOS media access controls, and full ecosystem polish.

---

## What's New in v4.5.4

### Apple Music & Spotify Remote Control
- **Dedicated Player Integration:** Automatically discovers and controls media playback across Apple Music and Spotify on macOS with native AppleScript reliability.
- **Intelligent Playback Identification:** Accurately detects active playback across both players independently and automatically prioritizes genuinely playing tracks over paused sessions.
- **Rich Metadata & Artwork:** Extracts and forwards real-time track title, artist, album artwork thumbnails, duration, timeline position, and sound volume.

### Android Media Source Selector & Native Lock Screen
- **Now Playing Source Selector:** An interactive dropdown selector on the Android dashboard enables users to seamlessly switch between Apple Music and Spotify or select "Auto" to follow active playback.
- **Native Android MediaSession:** Integrates a system `MediaSession` that displays full artwork and media controls on the Android lock screen and notification shade via `Notification.MediaStyle`.
- **Full Transport Controls:** Play/Pause, Next, Previous, seeking, and volume changes issued from the lock screen, notification shade, or in-app card are piped directly to the selected Mac application over SYNQ's secure channel.

### Server-Side Media Access Control
- **Dedicated macOS Security Toggle:** Added a prominent setting in macOS Settings: *"Allow connected devices to control media — Let paired devices view and control media playing on this Mac"*.
- **Enforced Security:** Enabled by default, this permission is strictly enforced server-side on macOS. Disabling it immediately terminates media broadcasts, clears the Android remote lock-screen session, and rejects all incoming media control requests.

---

# SYNQ v3.5.4 — Release Notes

Release v3.5.4 introduces an Apple- and Material 3-inspired dark-first redesign, end-to-end cryptographic Over-The-Air (OTA) updates for Android and macOS, high-throughput pipelined sliding-window file streaming, instant QR-based pairing with anti-SSRF protections, direct Android-to-Android encrypted file transfers, sequential NSD resolve queues, and a dynamic menu-bar connection indicator.

---

## What's New in v3.5.4

### UI Redesign & Settings Architecture
- **Dark-First Modern Aesthetics:** Overhauled the Android dashboard, device list, and action surfaces with modern dark-first styling, balanced typography, and responsive touch states.
- **5-Section Settings Architecture:** Categorized preferences into five clear, dedicated sections:
  - **Sync:** Clipboard synchronization, automatic background clipboard sync, and notification forwarding.
  - **Calls & Contacts:** Contact directory sync, masked phone numbers (e.g., `Tony Stark (98765•••••)`) for privacy, and call permissions.
  - **Privacy & Local Security:** Local Wi-Fi encryption details, SAS confirmation code verification, and zero-telemetry assurance.
  - **General:** Device name customization, dark/light theme options, and battery optimization guides.
  - **About:** Version info, permission overview, GitHub links, and OTA Software Update.
- **Streamlined macOS Menu Bar Popover:** Restored the bottom toolbar to a clean 3-button layout (`Settings`, `Guest Share`, `Quit`), eliminating label truncation.

### Cryptographic In-App Over-The-Air (OTA) Updates
- **Android In-App Updates:** Integrated `SYNQUpdateManager` with background download progress, SHA-256 payload integrity validation, release signing certificate fingerprint verification, and seamless installation via Android `PackageInstaller`.
- **macOS Software Update Settings:** Added dedicated Software Update preferences in Settings with three distinct options:
  - **Download & Install:** Automatically downloads verified update packages, validates SHA-256 integrity, mounts the DMG volume, and launches the installer.
  - **Only Download:** Downloads and cryptographically verifies the update package, saving it directly to `~/Downloads` and revealing it in Finder.
  - **Check & Inform:** Checks the remote update manifest, compares semantic versions, and provides complete release details and notes without downloading.
- **Non-Intrusive Error Reporting:** Completely eliminated disruptive modal alert sheets; update status and network notices are displayed non-intrusively inline.

### Sequential NSD Discovery & QR Preview Fix
- **Sequential Resolve Queue:** Fixed Android Network Service Discovery (NSD) listener concurrency, preventing `FAILURE_ALREADY_ACTIVE` errors and eliminating duplicate phantom device listings on the home dashboard.
- **Aspect-Ratio Corrected QR Scanner:** Dynamically computes optimal Camera2 preview dimensions matching physical sensor output, eliminating camera feed stretching and providing smooth continuous auto-focus.

### High-Throughput Streaming File Transfers
- **Sliding-Window Pipelining:** Replaced synchronous stop-and-wait chunk acknowledgments with an unacknowledged sliding window (up to 64 in-flight chunks), eliminating per-chunk latency stalls.
- **24 KiB Framing with 64 KiB Receiver Buffering:** Senders transmit 24 KiB frames with cumulative block acknowledgments every 16 frames, while receiver buffers accommodate up to 64 KiB for forward compatibility.
- **Fast MediaStore & Socket Buffering:** Android transfers utilize 256 KiB buffered streams for direct MediaStore I/O and block-based JSON header parsing.
- **Integrity & Atomic Handling:** Preserved end-to-end SHA-256 integrity verification, safe temporary file isolation (`.part`), and automatic cancellation cleanup across both macOS and Android.

### Direct Android-to-Android File Transfer
- **Direct Peer-to-Peer Transfer:** Two Android phones on the same local Wi-Fi network can discover, pair, and transfer multiple files directly to each other without needing a Mac, cloud server, or relay.
- **Strict Companion Feature Isolation:** To preserve device security and architectural clarity, phone-companion features (calls, SMS, notifications, clipboard auto-sync, Ring/Ping) are strictly disabled for Android-to-Android connections. Only high-speed encrypted file transfer (`SYNQFeature.FILES`) is permitted.
- **Multi-File Batch Selection:** Upgraded Android file picker to `OpenMultipleDocuments`, allowing multi-file batch transfers in a single action.
- **Adaptive UI:** Android dashboard and device list dynamically distinguish between connected Mac computers and Android peers, updating icons, action titles, and status dialogs accordingly.

### Dynamic Menu Bar Connection Indicator
- **Three-State Visual Indicator:**
  - **Connected:** Both device icons rendered with a solid bridge line and circular endpoint nodes.
  - **Connecting / Reconnecting:** Bridge rendered as a pulsing dashed line, indicating active discovery or handshake in progress.
  - **Disconnected:** Connection bridge line and endpoint nodes are completely hidden, leaving distinct unlinked device silhouettes.
- **Zero Idle Overhead & Accessibility:** The menu-bar pulse animation runs only during active connecting or verifying states and is completely invalidated when connected or disconnected. Automatically respects system reduced-motion accessibility preferences.

---

# SYNQ v2.5.4 — Release Notes

Release v2.5.4 brings comprehensive UI polish, navigation and layout fixes, in-panel call and device finding feedback, an expanded Android permissions overview, and reliability enhancements across macOS and Android.

---

## What's New in v2.5.4

### macOS Popover & UI Polish
- **Consistent Action Button Styling:** The "Find Device" action shares the exact rounded pill capsule styling and height of all other primary grid buttons, maintaining uniform aesthetics across the menu bar popover.
- **In-App Ring & Ping Feedback:** Replaced system notification banners for Ring and Ping with native, real-time in-panel status indicators inside SYNQ (`Ringing Android…`, `Phone is ringing`, `Ping delivered`, `Ringing stopped`, with connection and timeout alerts).
- **Pill-Style Call Back Button:** Aligned the Call from Mac back button with Guest Share's capsule design (`chevron.left` pill).
- **Cleaner Contact Rows & Safe SMS:** Example contact chips (e.g. `Tony Stark: 9876543210`) allow direct dialing or composing SMS without accidental calls.
- **Non-Dismissing Call Confirmation:** Replaced the window-modal alert sheet with an in-panel confirmation overlay. Pressing Cancel, pressing Escape, or tapping the backdrop dismisses only the confirmation dialog and returns cleanly to the Call screen without closing the SYNQ popover.
- **Modern Transfers HUD:** Overhauled the File Transfers window into a modern, compact HUD with rounded translucent glass styling, hero preview cards, image thumbnail generation, prominent completion badges, and instant-action pill buttons (`Copy` directly to clipboard and `Open ⌄` split button). Supports direct drag-and-drop into Finder.
- **Reliable SMS Messaging from Mac:** Compose and send SMS text messages directly to any contact or phone number from the Mac menu bar with instant asynchronous delivery feedback.

### Android Navigation & Settings Polish
- **Bottom Navigation Layout Fix:** Material 3 `NavigationBar` anchored properly to system and gesture navigation insets, eliminating vertical gaps and selected-state clipping.
- **Find Mac Dropdown Layout:** Standardized `OutlinedButton`, rounded dropdown menu (`16.dp`), leading icons, two-line action descriptions, and clean dividers.
- **About → Permissions Page:** Dedicated Permissions & Privacy page explaining each permission used by SYNQ (`INTERNET`, `POST_NOTIFICATIONS`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `CALL_PHONE`, `READ_CONTACTS`, `SEND_SMS`, Storage Access Framework).

---

# SYNQ v1.5.4 — Release Notes

## Highlights
- **SYNQ**: Modern unified design featuring Apple Liquid Glass menu bar aesthetics on macOS and Material 3 Expressive on Android.
- **Top-Pinned Menu Bar Popover**: Custom AppKit window pinner ensuring the menu bar panel anchors stably to the top menu bar status item without vertical jumping when views expand.
- **Two-Way Contacts Sync & Favorite Contacts**: Synchronize contacts from Android to macOS with search, directory browsing, and customizable pinned favorites.
- **Local Guest Transfers**: Send and receive files with unverified nearby peers via an ephemeral local HTTP server and QR code pairing without permanent pairing requirements.
- **Zero-Cloud Privacy**: Direct peer-to-peer communication over local Wi-Fi with AES-GCM-256 encryption. No accounts, telemetry, cloud servers, or third-party services.

