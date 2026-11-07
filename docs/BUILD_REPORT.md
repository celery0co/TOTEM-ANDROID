# TOTEM Android build and verification report

## Current implementation

The current app is a native Android client built with Kotlin and Jetpack Compose.

- Native screens: Home, sign-in, Chat, thread history, Wallet/Credits and Profile.
- Hosted service: `https://beta.totemai.xyz` over HTTPS.
- Chat: server-backed workspace and thread history, streaming responses and an agent WebSocket.
- Agent approvals: explicit Approve/Reject UI; no automatic approval.
- Session: Android Keystore-backed encrypted preferences where available.
- Build: GitHub Actions runs `:app:assembleDebug` with JDK 17, Android SDK 34 and Gradle 8.7.

## Scope

The app is a mobile client, not a standalone AI runtime. It does not host a model, implement a credit ledger or payment processor, manage private keys, or implement client-side E2EE or network anonymity. Inference options are provided by the hosted service when available.

## API source review

The Android route paths were compared against the public `fred-terzi/totem-llm` source snapshot `main-totem` at commit `8d1cb870797c8e9f8eb6087cf2d7820c2b38eaec`. The reviewed route list and caveats are in `docs/PORT_PARITY.md`.

Live hosted API verification could not be completed in the review environment because DNS resolution for `beta.totemai.xyz` failed. Authenticated requests still need to be tested with a non-production account on a real device.

## Build and device-test status

This document does not certify a successful Android build or real-device test by itself. The GitHub Actions run linked from the repository's **Actions** tab is the source of truth for the compile/package result. The device matrix in `docs/TESTING.md` must be completed before treating login, streaming, wallet integration or agent approvals as fully runtime-verified.

## Signing

Unless CI is given the persistent keystore environment variables described in `app/build.gradle`, the debug APK uses the runner's temporary debug signing key. A later APK may not install as an update over an APK signed by a different key. Uninstalling the earlier app may clear local encrypted session data and preferences.
