# SYNQ Technical Documentation

Welcome to the technical specifications, architectural designs, and security policies for **SYNQ 4.5.4**.

---

## Documentation Index

| Document | Description |
| :--- | :--- |
| **[Architecture Overview](architecture.md)** | System design, client architecture (macOS & Android), session lifecycle, framing, transport, and feature routing. |
| **[Protocol Specification](protocol.md)** | Wire format, newline-delimited JSON framing, P-256 ECDH, SAS 6-digit verification ceremony, and AES-GCM payload formats. |
| **[Remote Media Control](media.md)** | Dedicated guide covering Apple Music and Spotify playback controls, dual-source detection, artwork downsampling, and lock-screen integration. |
| **[Security Policy & Model](../SECURITY.md)** | Threat model, cryptographic primitives, hardware keystore storage, replay protection, and transparent limitations. |
| **[SMS & Telephony Policy](sms-call-policy.md)** | Google Play policy compliance, permission boundaries (`CALL_PHONE`), dialer confirmation, and SMS safeguards. |
| **[Testing Guide](testing.md)** | Automated test suites (130 macOS tests, 97 Android tests), cross-platform crypto vectors, and hardware verification. |
| **[Security Testing & Auditing](security-testing.md)** | CodeQL analysis, fuzzing, bounded frame validation, and protocol negative testing. |
| **[Release & Packaging](releasing.md)** | Build instructions, Developer ID signing, Apple notarization, Keystore signing, and distribution packaging. |

---

## Quick Navigation

- Return to **[Main Project README](../README.md)**
- Read the **[SYNQ 4.5.4 Release Notes](../RELEASE_NOTES_4.5.4.md)**
- Review the full **[Changelog](../CHANGELOG.md)**

