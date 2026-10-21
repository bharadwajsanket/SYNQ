# Release and Packaging Workflow — SYNQ

This document describes how release artifacts are built, signed, packaged, and verified for SYNQ on macOS and Android.

---

## 1. Local Build & Packaging

### macOS
The macOS build script compiles the release binary with SwiftPM, structures the `.app` bundle, injects localized resources and icon assets, and applies code signing:

```bash
cd macos
CONFIGURATION=release ./build-app.sh
```

To create a distributable disk image (`.dmg`) and zip archive with SHA-256 checksums:
```bash
cd macos
./package-release.sh
```
Generated artifacts are placed in `macos/dist/`:
- `SYNQ-macOS.dmg`
- `SYNQ-macOS.zip`
- `SHA256SUMS-macOS.txt`

### Android
The Android release build compiles the optimized APK with ProGuard/R8 rules:

```bash
cd android
./gradlew assembleRelease
```
Generated APK:
- `android/app/build/outputs/apk/release/app-release-unsigned.apk` (or signed if keystore variables are configured).

---

## 2. Signing & Notarization Configuration

### Android Keystore Signing
For automated release signing, configure the following environment variables or GitHub Secrets:

- `SYNQ_ANDROID_KEYSTORE_BASE64` (or `ANDROID_KEYSTORE_BASE64`): Base64 string of the release `.jks` file.
- `SYNQ_ANDROID_STORE_PASSWORD` (or `ANDROID_STORE_PASSWORD`): Keystore password.
- `SYNQ_ANDROID_KEY_ALIAS` (or `ANDROID_KEY_ALIAS`): Key alias name.
- `SYNQ_ANDROID_KEY_PASSWORD` (or `ANDROID_KEY_PASSWORD`): Key password.

### macOS Developer ID & Notarization
Without Apple credentials, `build-app.sh` automatically signs the app with an ad-hoc signature (`codesign --sign -`) using a stable designated requirement (`identifier "dev.synq.mac"`).

For official Developer ID distribution and Apple Notarization, set:
- `MACOS_SIGNING_IDENTITY`: Full identity name, e.g. `Developer ID Application: Your Name (TEAMID)`.
- `APPLE_API_KEY_PATH`: Path to the App Store Connect API Key (`AuthKey_....p8`).
- `APPLE_API_KEY_ID`: 10-character Key ID.
- `APPLE_API_ISSUER_ID`: Issuer UUID from App Store Connect.

When these are provided, `package-release.sh` submits the DMG to Apple via `notarytool`, staples the notarization ticket, and validates the staple.

---

## 3. Release Checklist

1. Verify that all automated unit and integration tests pass cleanly:
   - `swift test` (macOS)
   - `./gradlew testDebugUnitTest` (Android)
2. Verify cross-platform cryptographic parity against `protocol/test-vectors/crypto-v1.properties`.
3. Update version metadata:
   - `macos/Resources/Info.plist`: `CFBundleShortVersionString` and `CFBundleVersion`.
   - `android/app/build.gradle.kts`: `versionName` and `versionCode`.
4. Review and update `RELEASE_NOTES.md` with relevant user-facing changes.
5. Create and push a semver git tag (e.g., `v1.0.0`).
