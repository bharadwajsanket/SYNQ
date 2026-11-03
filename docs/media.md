# Remote Media Control — SYNQ 4.5.4

SYNQ 4.5.4 provides native remote media control of **Apple Music** and **Spotify** running on macOS directly from paired Android devices.

---

## 1. Supported Applications

In SYNQ 4.5.4, media control is strictly focused on:

| Application | Bundle Identifier | Native Automation Method |
| :--- | :--- | :--- |
| **Apple Music** | `com.apple.Music` | AppleScript automation via `osascript` |
| **Spotify** | `com.spotify.client` | AppleScript automation via `osascript` |

> **Browser & Third-Party Player Integration Deferred:**  
> Integration with web browsers (Safari, Google Chrome, Arc, Crest, Microsoft Edge, Mozilla Firefox) and generic media players (IINA, VLC, QuickTime) is deliberately deferred to future releases. This ensures rock-solid stability, zero CPU overhead, and native reliability on macOS. SYNQ does not display generic placeholder cards for unsupported media sources.

---

## 2. Media Discovery & Playback Arbitration

macOS queries running media applications asynchronously on a background queue (`DispatchQueue.global(qos: .userInitiated)`) using non-blocking dispatch groups:

1. **Detection:** SYNQ checks whether `com.apple.Music` or `com.spotify.client` are active using `NSRunningApplication`.
2. **State Query:** If running, an AppleScript query extracts:
   - Track title (truncated at 256 characters)
   - Artist name (truncated at 256 characters)
   - Playback state (`playing`, `paused`, `stopped`)
   - Current playback position in seconds
   - Total track duration in seconds
   - Player sound volume (0–100)
3. **Dual-Source Arbitration:**
   - If both Apple Music and Spotify are running, SYNQ evaluates their playback states.
   - Any player that is **actively playing audio** (`playing == true`) takes priority over paused or stopped instances.
   - If both or neither are playing, SYNQ respects the user's manual selection from Android or defaults to the last active track.
4. **Android Source Selector:**
   - The Android `NowPlayingCard` displays a source chip. Users can manually switch between "Auto-detect", "Music", and "Spotify".
   - Manual selections route all subsequent commands strictly to that target application.

---

## 3. Album Artwork Pipeline

High-resolution artwork is delivered efficiently over local encrypted connections:

```text
┌───────────────────────────────────────────────┐
│              macOS MediaController            │
│                                               │
│  Apple Music: Raw descriptor data             │
│  Spotify:     HTTPS CDN Artwork URL           │
│                       │                       │
│                       ▼                       │
│  CGImageSourceCreateThumbnailAtIndex          │
│  - Max Pixel Size: 128×128                    │
│  - JPEG Compression: Quality 0.7              │
│  - Strict Payload Cap: ≤ 16,384 Bytes         │
│                       │                       │
│                       ▼                       │
│  Base64-Encoded Thumbnail String              │
│  Cached in-memory: key = "id|title|artist"    │
└───────────────────────┬───────────────────────┘
                        │ media.state frame
                        ▼
┌───────────────────────────────────────────────┐
│         Android RemoteMediaSessionManager     │
│                                               │
│  Base64.decode -> BitmapFactory.decodeBytes   │
│  - MediaMetadata.METADATA_KEY_ALBUM_ART       │
│  - MediaMetadata.METADATA_KEY_ART             │
│  - Notification.Builder.setLargeIcon          │
└───────────────────────────────────────────────┘
```

- **Apple Music:** Artwork is extracted as raw event descriptor data (`«data ...»`) and parsed into native image data without disk I/O.
- **Spotify:** Modern Spotify builds deprecate raw AppleScript image descriptors in favor of `artwork url of current track`. SYNQ fetches the cover image from Spotify's CDN over an ephemeral HTTPS session with a 2.5s timeout.
- **Payload Bound:** All thumbnails are downsampled to 128×128 JPEG images strictly capped at 16 KiB, preventing socket congestion.
- **In-Memory Cache:** Cached by compound key (`id|title|artist`) so artwork is only fetched and compressed once per track.

---

## 4. Playback Controls & Timeline Seeking

All transport actions are transmitted as AES-GCM encrypted `quick.request` frames:

| Action | Payload Value | AppleScript Execution on macOS |
| :--- | :--- | :--- |
| `toggle` | `""` | `playpause` |
| `pause` | `""` | `pause` |
| `next` | `""` | `next track` |
| `previous` | `""` | `previous track` |
| `seek` | `"<seconds>"` | `set player position to <seconds>` (validated 0–604800) |
| `volume` | `"<0-100>"` | `set sound volume to <volume>` (validated 0–100) |
| `select_source` | `"<bundle_id>"` | Changes active target application |

- **Security Enforcement:** Incoming commands map exclusively to pre-compiled fixed AppleScript tokens and validated integer operands. Remote arbitrary source code or shell commands are never executed.

---

## 5. Android System Integration (Lock Screen & Notification Shade)

Android's `RemoteMediaSessionManager` binds the remote Mac media state to Android's native multimedia framework:

- **System `MediaSession`:** Exposes playback state, track duration, current position, and artwork to the Android OS.
- **Lock-Screen Player:** Physical Android devices display the native media player widget with real-time scrub bar and play/pause/skip buttons directly on the lock screen.
- **Notification Shade:** Posts a `Notification.MediaStyle` notification with compact action controls (Previous, Play/Pause, Next) and album art.
- **Lifecycle & Clean Dismissal:**
  - The `MediaSession` and notification are created only when a valid track is loaded (`state.title.isNotBlank()`).
  - When media stops or is cleared on Mac, `clear()` is invoked immediately, releasing the session and canceling the notification.
  - Ongoing status is bound to playback (`.setOngoing(state.playing)`), allowing users to dismiss paused media notifications if desired.

---

## 6. macOS Media Access Control Setting

To protect user privacy and local network boundaries, macOS includes a server-side permission guard:

- **Setting:** macOS Settings → General → Media → *"Allow connected devices to control media"*.
- **Default:** Enabled.
- **Server-Side Enforcement:**
  - When toggled off, `MediaController` immediately invalidates playback timers, clears internal snapshots, drops outgoing `media.state` broadcasts, and commands Android to release its remote session.
  - Any subsequent incoming remote control commands received by the Mac are rejected with `accepted: false`.

