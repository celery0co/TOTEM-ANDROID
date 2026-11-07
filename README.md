# TOTEM Android

A native Android application built with Kotlin and Jetpack Compose. The app provides native Home, sign-in, chat, chat history, credits/wallet and Profile screens and connects to the official hosted TOTEM service at **https://beta.totemai.xyz** over HTTPS.

- Application ID: `xyz.totemai.app`
- Version: `2.4` (`versionCode 6`)
- Minimum Android: 7.0 / API 24
- Target / compile SDK: 34
- Java: 17
- Gradle: 8.7
- Android Gradle Plugin: 8.3.2
- Kotlin: 1.9.24

## Features

- Native Jetpack Compose interface with dark and light themes
- Native username/password sign-in and public registration when enabled by the server
- Chat streaming and server-backed conversation history
- Workspace thread creation and selection
- Credits and read-only $TOTEM wallet balance display
- Server-provided inference option selector when the hosted deployment exposes it
- Agent tool approval prompts with explicit Approve and Reject actions
- Android Keystore-backed encrypted local session storage where supported

## Build an APK with GitHub Actions

1. Open the repository's **Actions** tab.
2. Select **Build TOTEM AI Android APK**.
3. Open a successful run from `main` or a `feature/**` branch.
4. Download the `TOTEM-APK` artifact and extract `app-debug.apk`.
5. Install the APK on Android.

The workflow installs JDK 17, Android SDK Platform 34 / Build Tools 34.0.0 and Gradle 8.7, then runs `:app:assembleDebug`. The APK is a debug build and is not Play Store signed.

**Debug signing note:** unless a persistent keystore is configured in CI, GitHub-hosted runners use a temporary debug signing key. Android may reject an update signed by a different run. Uninstalling the previous debug build can resolve a signature conflict, but it also removes the app's local session and preferences.

## Local build

Install JDK 17 and Android SDK Platform 34 / Build Tools 34.0.0, then run:

```bash
./gradlew :app:assembleDebug
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
```

The included `gradle-wrapper.jar` is a small project-local bootstrap launcher, not the upstream Gradle Wrapper JAR. Its Java source is included at `gradle/wrapper/GradleWrapperMain.java`. It downloads Gradle 8.7 over HTTPS, verifies its SHA-256 against `gradle-wrapper.properties`, and launches it. GitHub Actions installs Gradle directly and does not depend on this bootstrap launcher.

## Hosted API and security scope

The Android client sends ordinary HTTPS requests to the hosted TOTEM API. Authentication, model execution, inference routing, credits and wallet-balance reads are performed by the hosted service.

Inference choices are supplied by the server when the deployment exposes the relevant endpoints. The Android client displays the returned choices and sends the selected option to the server; it does **not** implement client-side end-to-end encryption, network anonymity or independent inference routing. The labels and privacy guarantees of a server-provided option must be documented by the hosted service itself.

Agent tool requests are not approved automatically. The app displays the requested tool, its description and available payload details, then sends the user's explicit approval or rejection to the agent WebSocket. Requests expire according to the server-provided timeout and are denied by the server when no response arrives.

The app does not contain a local AI backend, model runtime, credit ledger, payment processor or private-key manager. Wallet signing remains outside the app; do not enter a seed phrase or private key.

See `docs/PORT_PARITY.md`, `docs/TESTING.md`, `docs/BUILD_GUIDE.md` and `docs/SECURITY.md` for implementation details and verification limits.
