# Security Policy and Model — SYNQ

## Supported Versions

SYNQ security fixes are applied directly to the active `main` branch. Users and developers are encouraged to always run the latest build.

## Reporting a Vulnerability

If you discover a security vulnerability in SYNQ:

- **Do not open a public issue.**
- Submit your finding securely via GitHub Private Vulnerability Reporting on the SYNQ repository.
- Provide full reproduction details: platform version, affected commit/tag, step-by-step reproduction steps, proof of concept, and impact assessment.
- Do not transmit sensitive private data (such as real personal clipboard text, passwords, or personal notifications) in vulnerability reports; use synthetic or redacted test values.
- Inquiries can be directed to project lead **Sanket Bharadwaj**.

---

## Threat Model

### Assumptions
- **The Local Network (LAN) is Untrusted:** Attackers on the same Wi-Fi network can observe packets, craft arbitrary mDNS/DNS-SD discovery announcements, attempt man-in-the-middle (MITM) attacks, and replay intercepted traffic.
- **The Local Device OS is Trusted:** The local operating system, its sandboxing mechanisms, and its secure hardware keystores (macOS Keychain / Secure Enclave and Android KeyStore) are assumed uncompromised.
- **Physical Display Verification:** The initial pairing ceremony relies on the user visually verifying the numeric confirmation code on both device screens.

### Security Objectives
1. **Mutual Peer Authentication:** Ensure both nodes verify each other's identity before exchanging sensitive data.
2. **Confidentiality & Integrity:** Encrypt all user data payloads in transit with authenticated encryption.
3. **Replay Resistance:** Reject replayed or duplicate messages.
4. **Least Privilege & Local Control:** Provide granular feature toggles globally and per device.
5. **Prompt Trust Revocation:** Remove all cryptographic trust, stored keys, and session states immediately when a device is unpaired.

---

## Cryptographic Architecture

### Key Agreement & Pairing Ceremony
- **Identity Keys:** Each device generates an ECDH P-256 key pair on initial launch. Android keys are backed by the hardware-backed `AndroidKeyStore`. macOS keys are persisted securely in the user's Keychain.
- **Key Exchange:** Ephemeral P-256 ECDH generates a shared secret `Z`.
- **Key Derivation (HKDF):** HKDF-SHA-256 derives symmetric session keys using salt and session identifiers with domain-separated cryptographic context labels.
- **Mutual Numeric Confirmation:** A 6-digit confirmation code derived from HKDF output is displayed simultaneously on both devices. Pairing succeeds only after mutual confirmation on both screens.
- **Identity Binding:** The pairing handshake binds the persistent identity public keys of both devices to prevent impersonation on subsequent reconnects.

### Transport Security & Encryption
- **Framing:** Messages are transmitted over TCP with length-delimited JSON frames subject to a hard 64 KB per-frame limit prior to parsing.
- **Payload Encryption:** Sensitive payloads (clipboard content, notifications, file stream chunks, telephony numbers, and commands) are encrypted with **AES-GCM-256** (with 12-byte nonces and 16-byte authentication tags).
- **Session Continuity:** Subsequent reconnects perform an authentication challenge-response proof verifying the previously pinned identity key before any feature data is decrypted or processed.

### Replay & Rate Protections
- **Replay Caches:** Each connection maintains an in-memory sliding cache of verified message IDs. Duplicate IDs within a session are discarded immediately.
- **Monotonic Sequencing:** Quick actions and file transfer chunks enforce strictly ascending sequence counters.
- **Rate Limits & Backoff:** Reconnect attempts use exponential backoff with jitter to prevent local resource exhaustion.

---

## Platform-Specific Security Controls

### File Transfers
- **Path Traversal Protection:** All incoming file paths are sanitized. Directory traversal characters (`../`, `..\`) and absolute path prefixes are stripped.
- **Unique Atomic Destination:** Incoming files are written with unique temporary file names (`.part`) and verified against the sender's declared SHA-256 checksum before atomic rename into the destination folder.
- **User Confirmation:** Transfers require explicit user acceptance unless the sender is an authorized trusted paired device.

### Clipboard Synchronization
- **Payload Bounds:** Clipboard data exceeding the maximum size limit is truncated or rejected.
- **Sensitive Detection:** Password and sensitive marker tags from clipboard managers are respected to avoid unintentional forwarding.

### Notifications & OTP Detection
- **Local Filtering:** Android Notification Listener filters out system-internal notifications and user-blacklisted packages before transmission.
- **OTP Protection:** When an SMS contains an OTP, the code is extracted locally via heuristic regex patterns and forwarded with a distinct event type. The temporary prompt dismisses automatically after a short expiration window.

### Telephony & Calls
- **Permission Boundary:** Call initiation requires explicit permissions (`CALL_PHONE` / `READ_PHONE_STATE`).
- **Emergency Number Guard:** Emergency numbers (such as 911, 112, 999) are never dialed automatically; platform dialers require direct user action.
- **Confirmation Default:** Android prompts for user confirmation before placing calls initiated from macOS unless direct calling has been explicitly enabled in settings.

---

## Privacy Commitments

- **Zero Cloud Servers:** No data is ever transmitted to external servers. SYNQ runs entirely within your local network.
- **No Telemetry or Tracking:** SYNQ collects no analytics, device fingerprints, or crash telemetry.
- **Redacted Diagnostics:** Diagnostic exports and logs contain state event counters and error codes only. Personal data, contact names, notification bodies, and clipboard content are never written to disk logs.
