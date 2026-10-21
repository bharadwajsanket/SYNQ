# Architecture

## Core Architectural Design

The project is structured as a monorepo containing native clients for macOS (Swift) and Android (Kotlin) communicating via a platform-neutral protocol over the local network (LAN).

### Current Implemented Architecture (v0.5 / v0.6)

Clients connect directly over local Wi-Fi / Ethernet without intermediate cloud servers, relays, or external accounts.

```text
Native macOS App / Android App
          │
  PairingCoordinator (Session Lifecycle & Feature Gating)
          │
  Raw TCP Socket Layer (Newline-delimited JSON Framing, 64 KiB max frame)
          │
  Application-Layer Cryptography
   ├── Ephemeral P-256 ECDH + HKDF-SHA-256 key agreement
   ├── 6-Digit SAS verification code
   ├── Stored P-256 identity keys (Keychain / Android KeyStore)
   └── AES-GCM encrypted payloads (with base64 nonce + ciphertext)
          │
  Feature Domains
   ├── Clipboard (Rich HTML + plain-text fallback, 32 KiB cap)
   ├── File Transfer (24 KiB chunks, 64-chunk sliding window, SHA-256)
   ├── Notification Forwarding (App filtering, transient 64-char action tokens)
   ├── Phone Calls (Validated tel: numbers, local dialer confirmation)
   └── Quick Actions & Media (Media control, link handoff, ping, battery)
```

> **Note on Future Architectural Proposals:** Earlier drafts outlined a persistent WebSocket over TLS transport using a generic JSON envelope router (`core.hello`). That design has not been implemented. The production wire format is raw TCP sockets using newline-delimited `PairingMessage` JSON structures and application-layer AES-GCM encryption.

---

## Core Boundaries

### Discovery
- **Mechanism**: Publishes and browses DNS-SD / mDNS service type `_synq._tcp.local.`.
- **Advisory TXT Records**: Contain hint metadata only (`id`, `name`, `version`, `platform`). Port is determined by the SRV record.
- **Trust Boundary**: Discovery records are completely untrusted. Discovery never automatically pairs or establishes trust. A peer is authenticated only after completing cryptographic proof of its stored long-term identity key.

### Device Registry & Secure Storage
- Stores the stable local device ID and paired peer records.
- Peer records associate device ID with display name, long-term public identity key, and granted feature permissions.
- **Key Storage**: Android stores private key material in Android KeyStore (`p256-signing-v1`); macOS stores private key material in macOS Keychain (`dev.synq.identity`).
- Discovery caches remain ephemeral and separate from the trust store.

### Transport
- **Implementation**: Native TCP sockets (macOS `NWConnection` / `NWListener`, Android `Socket` / `ServerSocket`).
- **Framing**: UTF-8 newline-delimited (`\n`) JSON frames with an enforced 65,536-byte maximum frame limit. Oversized or malformed frames trigger immediate session termination.
- **Outer vs. Inner Metadata**:
  - Outer message attributes are transmitted in plaintext JSON: `kind`, `sessionId`, `messageId`, `transferId`, `sequence`.
  - Sensitive feature payloads (clipboard content, file chunks, notification text, phone numbers, command details) are encapsulated inside `nonce` and `ciphertext` using AES-GCM authenticated encryption.
- **Idempotency & Replay**: Handled via session-local bounded message ID sets (`seenMessageIDs`, capacity 256).

### Feature Policies & Feature State Negotiation
- Features are independently toggled per paired device and globally.
- After session verification, peers exchange their effective policy via an encrypted `features.update` message.
- A feature is enabled only if *both* local and remote policies permit it.

### Plugins

A plugin registers a capability string, message type prefix, lifecycle hooks,
and handler. Plugins are independently enabled per paired device. Unknown
capabilities and message types are ignored or answered with `unsupported_type`.

The current clients persist a global feature policy plus per-device overrides.
The global switch always takes precedence. After authentication, peers exchange
their effective feature state through an encrypted `features.update` message.
A feature is exposed only when both peers enable it; UI actions and dependent
status such as battery data update immediately. Incoming side effects still
check local policy independently. Active accepted file transfers are allowed to
complete so changing a setting cannot strand a partial transfer.

## Connection lifecycle

```text
idle -> discovering -> connecting -> authenticating -> connected
  ^          |             |               |              |
  `----------+-------------+---------------+--- backoff ---'
```

Network-path changes and wake events restart discovery because peer IPs are not
stable. One deterministic dialer is selected by lexicographically comparing the
authenticated device IDs; the lower ID initiates, while either side may accept.
Duplicate connections are closed after authentication. Backoff uses full jitter
with a 1 second base and 60 second cap, and resets after 30 seconds of stability.

## MVP plugin behavior

- **Battery:** Android publishes percentage and charging state after an
  authenticated connection and on system battery changes. macOS displays the
  latest value only while that peer remains connected.
- **Clipboard:** UTF-8 text plus optional HTML with a mandatory plain-text
  fallback and a 32 KiB content limit. Every encrypted update carries a unique
  message ID and requires a delivery acknowledgement. Android background
  clipboard access is not assumed; reading and sending requires an explicit
  user action.
- **Files:** metadata is announced in JSON, then bytes are streamed in bounded
  chunks. macOS users choose a persistent receive directory; Android writes to
  its scoped `Download/SYNQ` collection. Hash verification,
  progress, cancellation, and partial-file cleanup are mandatory.
- **Notifications:** Android's `NotificationListenerService` emits sanitized
  metadata. SYNQ's own notifications and secret/silent categories are
  filtered. Users can disable forwarding per observed Android package before
  content enters the transport. macOS posts through `UserNotifications` and
  returns only the bounded action tokens supplied by Android. Optional local
  history is off by default, retains at most 200 items for seven days, and is
  stored with owner-only permissions. Ongoing `CATEGORY_CALL` notifications are
  the sole exception to the ongoing-notification filter: their bounded call
  type drives a temporary macOS call card. With the separate call-integration
  opt-in, Android's telephony state corrects ambiguous OEM notification state
  and authenticated action tokens invoke the platform Answer, Decline, and
  Hang Up operations. Without it, SYNQ forwards only actions supplied by
  the phone application.
- **Calls from Mac:** macOS sends only a strictly validated number over the
  authenticated encrypted session. Android defaults to a notification that
  opens the system dialer for user confirmation. Call status and controls are
  a separate opt-in backed by narrow runtime Phone permissions; SYNQ does
  not request contacts or call-log access, and emergency numbers never use the
  immediate path.
- **macOS call presentation:** call notifications use a compact non-activating
  floating panel so Answer, Decline, and Hang Up remain directly visible rather
  than being collapsed into the system notification action menu. Ordinary
  mirrored notifications continue to use `UserNotifications`.

## Platform lifecycle

Android uses a visible foreground service only while the user has enabled a
persistent connection. It listens to connectivity changes and respects Doze;
there are no hidden keep-alive tricks. macOS uses a menu-bar scene and network
path/wake notifications. Both clients persist state before UI teardown.

Android Back navigation closes only the activity UI and intentionally leaves
the enabled connection service running. `Turn off SYNQ`, available both in
the app and its foreground notification, is the explicit lifecycle boundary:
it stops discovery and transport, removes the notification, and persists the
disabled state. Launching the main-profile app explicitly enables it again.

The Android MVP activates networking only for the system/main user. Samsung
Secure Folder and managed-profile copies have isolated app storage and would
otherwise publish different device identities from the same physical phone.
Those copies show an explanatory screen and do not start discovery or the
foreground connection service.

## Delivery sequence

1. Repository, protocol/security specifications, buildable shells, discovery.
2. Identity, pairing transcript, secure storage, and test vectors.
3. pinned-TLS WebSocket transport and reconnection state machine.
4. Clipboard, streaming files, then notification forwarding.
5. Hardening, interoperability tests, accessibility, and release packaging.
