# SYNQ 4.5.4 — Release Notes

**Release Date:** November 2, 2026  
**Build:** macOS 4.5.4 (Build 45) · Android 4.5.4 (VersionCode 45)  
**License:** MIT  

SYNQ 4.5.4 delivers a major media integration milestone, bringing native **Apple Music** and **Spotify** remote controls to Android, independent multi-source playback detection, lock-screen and notification shade `MediaSession` integration, a dedicated macOS server-side media access toggle, single-tap pairing verification, and full ecosystem polish across macOS and Android.

---

## 🌟 Release Highlights

1. **Native Apple Music & Spotify Remote Control:** Full real-time playback control, track titles, artists, high-resolution album artwork, timeline seeking, and volume adjustments over local network.
2. **Independent Dual-Source Playback Detection:** Automatically discovers Apple Music and Spotify instances independently, prioritizing active playback over paused sessions with an interactive manual switcher on Android.
3. **Android System MediaSession Integration:** Native lock-screen playback controls and `Notification.MediaStyle` card in the notification shade matching system media player aesthetics.
4. **Zero Phantom Media Notifications:** System media session cleanly activates when audio is playing/loaded and clears completely when stopped, preventing blank or placeholder cards.
5. **Dedicated macOS Media Access Control:** Prominent toggle in macOS Settings (*"Allow connected devices to control media"*) enforced server-side.
6. **Streamlined Single-Tap Pairing:** Resolved duplicate confirmation prompts between the onboarding window and menu bar popover with synchronized verification state.

---

## 🎵 Media Integration Deep-Dive

### Supported Players
- **Apple Music** (`com.apple.Music`)
- **Spotify** (`com.spotify.client`)

*(Note: Browser media playback and generic third-party players are explicitly deferred to upcoming releases to preserve rock-solid reliability on native music apps).*

### Key Capabilities
- **Dual Playback Arbitration:** If both Apple Music and Spotify are open, SYNQ queries both player states via asynchronous background tasks, detects which player is actively playing audio, and presents that active session to Android.
- **Android Source Selector:** An interactive dropdown on Android's `NowPlayingCard` allows users to select between "Auto-detect", "Music", and "Spotify".
- **High-Resolution Artwork Pipeline:**
  - **Apple Music:** Extracts raw artwork descriptor bytes from AppleScript and downsamples into a compact 128px JPEG thumbnail (<16 KB).
  - **Spotify:** Fetches the track's CDN album cover URL over an ephemeral HTTPS connection, converts it to a 128px JPEG thumbnail (<16 KB), and caches it per-track.
- **Full Playback Controls:**
  - Play / Pause toggle
  - Previous Track & Next Track
  - Interactive timeline seeking (scrub bar)
  - Volume slider adjustments
- **Lock-Screen & Notification Controls:** Uses Android's `MediaSession` and `Notification.MediaStyle` to display artwork and controls on the physical lock screen, notification center, and quick settings audio player.

---

## 🖥️ macOS Platform Updates

- **Menu Bar Popover Refinements:** Removed window frame resizing hacks that could cause popover creep over the menu bar. Standardized window bounds and padding (540pt for Call screen, 530pt for Guest Share) to eliminate clipping.
- **Pairing Coordinator Synchronization:** Added `isLocalConfirmed` tracking in `PairingCoordinator` and `isConfirmed` in `QRPairingManager`. Tapping "Codes match" on either the onboarding window or the menu bar popover instantly transitions to `"Confirmed. Waiting for [peer]…"` with a progress spinner.
- **Server-Side Media Permission Enforcement:**
  - Located under macOS **Settings → General → Media**: *"Allow connected devices to control media"*.
  - Enabled by default. When toggled off, macOS immediately drops outgoing media broadcasts, clears the remote Android lock screen, and rejects remote control commands.
- **130 Unit Tests Passing:** macOS test suite verified with 130 passing unit and integration tests (0 failures, 0 warnings).

---

## 📱 Android Platform Updates

- **Dynamic Now Playing Card:** Redesigned Now Playing dashboard component with real-time playback progress bar, player badges, artwork thumbnail, and playback transport buttons.
- **Lock-Screen Media Lifecycle:**
  - `RemoteMediaSessionManager` ensures the system `MediaSession` is only active when an actual track is loaded or playing (`state.title.isNotBlank()`).
  - Dismisses notifications immediately when playback is stopped or cleared, preventing `"No title / Media"` placeholders.
  - Ongoing status is bound to active playback (`.setOngoing(state.playing)`), allowing easy dismissal when paused.
- **Material 3 Expressive UI:** Updated text and labels to reflect supported media apps accurately: `"Open Music or Spotify on your Mac"`.
- **97 Unit Tests Passing:** Complete Android test suite verified with 97 passing unit and integration tests (0 failures).

---

## 🐛 Bug Fixes & Reliability Improvements

- **Fixed Phantom Media Notifications:** Addressed an issue where Android displayed an empty media notification on the lock screen when media was stopped or paused on Mac.
- **Fixed Spotify Album Artwork:** Implemented Spotify CDN artwork retrieval in `MediaController.swift`, resolving missing cover art on Android when playing Spotify tracks.
- **Fixed Duplicate "Codes Match" Prompt:** Unified pairing verification state across the Mac onboarding window and menu bar popover so confirming once completes the flow without a second prompt.
- **Fixed Menu Bar Popover Overlap:** Eliminated programmatic window frame resizing that caused the menu bar popover to creep over the macOS status bar.
- **Fixed Thread Deadlock in Media Controller:** Replaced blocking `group.wait()` calls on serial queues with non-blocking `DispatchQueue.global(qos: .userInitiated)` and `group.notify`.

---

## 💻 Compatibility & Requirements

| Platform | Minimum OS | Architecture |
| :--- | :--- | :--- |
| **macOS** | macOS 13.0 (Ventura) or newer | Apple Silicon (M1/M2/M3/M4) & Intel x86_64 |
| **Android** | Android 8.0 (Oreo, API 26) or newer | ARM64 (`arm64-v8a`), ARMv7 (`armeabi-v7a`), x86_64 |

---

## ⚠️ Known Limitations & Boundaries

- **Supported Media Players:** Media remote controls currently support **Apple Music** and **Spotify** only. Browser-based media players (YouTube, web audio) and standalone third-party video players (IINA, VLC) are explicitly deferred to upcoming releases.
- **Local Network Required:** Both devices must be connected to the same local Wi-Fi or Ethernet network. Cloud relays or remote connections outside the LAN are not supported.
- **Call Audio:** Calls initiated from Mac are dialed by Android and require user confirmation on the phone by default. Mac microphone/speaker calling is not supported.

---

## 📦 Release Artifacts & Verification

All official release artifacts are cryptographically hashed:

```text
523609c6286a6e4a2c2850c5b686d2612c00a3c1a5efce17f493e0b3242b0abd  SYNQ-macOS.dmg
75c83f8431fb4e7b3d99e54d0307afcf8e0f51369e264f39b08266461c83d847  SYNQ-macOS.zip
d3df60e04625d850f7974beec267d5c41bceccd0ddc3b92627917888e19e4840  SYNQ-Android.apk
```

### Upgrading
- **In-App OTA:** If you have SYNQ installed, open **Settings → Software Update** on macOS or **Settings → About** on Android to check and install the update.
- **Manual Install:** Download `SYNQ-macOS.dmg` for Mac and `SYNQ-Android.apk` for Android, replacing previous installations. Existing pairings and settings are automatically preserved.

