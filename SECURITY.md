# Security Policy & Cryptographic Model — SYNQ 4.5.4

SYNQ is designed from the ground up as a private, local-first companion application. There are no cloud relays, external databases, user accounts, or telemetry trackers. All data exchanged between your devices remains strictly within your local network.

---

## 1. Reporting a Vulnerability

If you discover a security vulnerability in SYNQ:

- **Do not open a public GitHub issue.**
- Submit your finding securely via **GitHub Private Vulnerability Reporting** on the SYNQ repository.
- Provide detailed reproduction steps: affected commit/version, platform details, proof of concept, and an impact assessment.
- Do not transmit sensitive private data (such as real personal clipboard text, passwords, or personal notifications) in reports; use synthetic or redacted test values.
- Inquiries can be directed to project lead **Sanket Bharadwaj**.

---

## 2. Threat Model & Assumptions

### Assumptions
- **The Local Network (LAN) is Untrusted:** Attackers on the same Wi-Fi or Ethernet network can inspect raw packets, inject arbitrary mDNS/DNS-SD discovery announcements, attempt man-in-the-middle (MITM) attacks, or replay intercepted traffic.
- **The Host Device OS is Trusted:** The local operating system, its application sandboxing, and its hardware-backed keystores (macOS Keychain and Android KeyStore) are assumed uncompromised.
- **Physical Display Verification:** The initial pairing ceremony relies on the user visually verifying the numeric confirmation code on both device displays.

### Security Objectives
1. **Mutual Peer Authentication:** Ensure both nodes verify each other's identity before exchanging sensitive data.
2. **Confidentiality & Integrity:** Encrypt all user data payloads in transit with authenticated symmetric encryption.
3. **Replay Resistance:** Reject replayed or duplicate messages across sessions and within active streams.
4. **Least Privilege & Local Control:** Provide granular feature toggles globally and per device.
5. **Prompt Trust Revocation:** Immediately destroy cryptographic trust, stored keys, and session states when a device is unpaired.

---

## 3. Cryptographic Implementation

### Initial Pairing Ceremony (SAS Protocol)
1. **Ephemeral Key Exchange:** During pairing, both devices generate ephemeral P-256 Elliptic Curve Diffie-Hellman (ECDH) key pairs and exchange public keys (`pairing.offer` / `pairing.answer`).
2. **Key Derivation (HKDF):** A shared secret `Z` is computed via ECDH. `HKDF-SHA-256` derives independent symmetric verification and session keys using salt and session identifiers.
3. **Short Authentication String (SAS):** A 6-digit confirmation code derived from the first 4 bytes of the verification key modulo 1,000,000 is rendered on both device screens.
4. **Mutual Confirmation Proof:** When the user confirms the code matches on both devices, peers exchange `pairing.confirm` records containing HMAC proofs and signatures from their long-term P-256 identity keys.
5. **Hardware-Backed Identity Storage:** Long-term identity keys are persisted in:
   - **macOS:** User Keychain (`dev.synq.identity`).
   - **Android:** Hardware-backed `AndroidKeyStore` (`p256-signing-v1`).
6. **Subsequent Reconnects:** Re-connecting peers verify identity using a challenge-response signature over the session transcript. SAS code comparison is not required for established pairings.

### Transport Security & Framing
- **Wire Format:** Persistent TCP stream using newline-delimited (`\n`) JSON frames.
- **Strict Frame Bound:** Enforced 65,536-byte (64 KiB) maximum frame limit prior to parsing. Oversized or malformed frames trigger immediate connection termination.
- **Payload Encryption:** Sensitive payloads (clipboard content, notification text, file chunks, telephony actions, media state) are encrypted with **AES-GCM-256** (12-byte unique nonces + 16-byte authentication tags). Outer framing attributes (`kind`, `sessionId`, `messageId`) remain in plaintext JSON for routing.

### Replay & Rate Protections
- **In-Session Message Cache:** Receivers maintain a sliding memory cache of up to 256 verified `messageId` strings. Replayed IDs are discarded.
- **Monotonic Sequencing:** Quick action requests (`quick.request`) enforce strictly increasing sequence counters per session to defeat in-session replay attacks.
- **Exponential Backoff:** Reconnect attempts use randomized exponential backoff with jitter (1s base, 60s cap) to prevent connection storms.

---

## 4. Feature-Specific Security Controls

### File Transfers
- **Path Traversal Sanitization:** All incoming filenames are strictly sanitized. Directory traversal sequences (`../`, `..\`), slashes, and control characters are stripped.
- **Atomic Destination & Verification:** Incoming files are written to isolated temporary files (`.part`). The full file SHA-256 checksum is validated against the sender's declared hash before atomic rename into the destination directory.
- **Guest Share Isolation:** Guest transfers run an ephemeral local HTTP server bound to a temporary port with single-use tokens. Visitors cannot browse, read, or access any local files.

### Clipboard Synchronization
- **Payload Limits:** Clipboard payloads are strictly bounded to 32 KiB before encryption.
- **Password Manager Concealment:** Automatically inspects clipboard metadata and suppresses sync for items marked with sensitive flags (e.g. `org.nspasteboard.ConcealedType`, `com.agilebits.onepassword`).
- **Echo Suppression:** Calculates a sliding hash window to prevent infinite feedback loops between paired devices.

### Remote Media Control
- **Fixed Instruction Mapping:** Remote control actions map strictly to pre-compiled fixed AppleScript tokens (`playpause`, `pause`, `next track`, `previous track`) and validated integer operands (`seek`, `volume`). No remote code or shell script execution is possible.
- **Server-Side Enforcement:** macOS enforces the *"Allow connected devices to control media"* setting. When disabled, outgoing media broadcasts cease immediately and incoming commands are rejected with `accepted: false`.

### Notifications & Telephony
- **Local Notification Filtering:** Android notification listener ignores internal system notifications, user-blacklisted packages, and ongoing persistent alerts.
- **Call Safeguards:** Phone calls initiated from macOS trigger Android's system dialer with explicit user confirmation required by default. Emergency numbers (911, 112, 999) are never auto-dialed.
- **SMS Scope:** SYNQ does not declare `READ_SMS` or `READ_CALL_LOG`. It forwards outgoing SMS messages only upon explicit user composition from Mac.

### Over-The-Air (OTA) Updates
- **Payload Integrity:** All update packages (DMG on macOS, APK on Android) are validated against their declared SHA-256 hash prior to installation.
- **Certificate Pinning on Android:** Android's `SYNQUpdateManager` extracts the APK's signing certificate and verifies its SHA-256 fingerprint against hardcoded pinning (`ad2eeabb5612c89587df603ab55cc8d5870824adbb793602474913c2266a4484`) before dispatching to `PackageInstaller`.

---

## 5. Transparent Limitations & Open Considerations

In accordance with our commitment to honest security documentation, the following limitations should be noted:

1. **macOS Code Signing:** Unless built with an active Apple Developer ID certificate and notarized via `notarytool`, local builds use an ad-hoc signature (`codesign --sign -`). Users running self-built binaries may need to approve the application in macOS **System Settings → Privacy & Security**.
2. **Update Manifest Authenticity:** `release-manifest.json` is retrieved over standard HTTPS from GitHub Releases. While downloaded payloads are checked against SHA-256 checksums and Android certificate fingerprints, the manifest itself is not independently signed with a dedicated PGP/minisign key.
3. **mDNS Visibility on LAN:** Local mDNS/Bonjour service advertisements (`_synq._tcp.local.`) broadcast the device name and unique device ID in plaintext TXT records. While this contains no sensitive user content, devices are discoverable to other endpoints on the same local subnet.
4. **Physical Access to Unlocked Devices:** SYNQ relies on host OS security. If an attacker gains unlocked physical access to a paired device, they can trigger paired actions unless the pairing is removed in settings.
