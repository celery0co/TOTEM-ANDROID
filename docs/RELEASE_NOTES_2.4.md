# TOTEM Android 2.4

A native Android client for the hosted TOTEM service.

## Highlights

- Native Kotlin and Jetpack Compose interface
- Native sign-in and chat history
- Streaming conversations and workspace threads
- Credits and read-only wallet balance integration
- Explicit agent tool approval prompts with Approve and Reject controls
- Dark and light appearance support
- Encrypted local session storage where supported

## Agent safety

Agent tool requests show the tool name and available description/payload details. The user must explicitly approve or reject each request. The Android app does not approve tool actions implicitly. Unanswered requests are denied by the hosted server when the approval timeout expires.

## Hosted service and inference options

The app connects to the official hosted TOTEM API over HTTPS. Inference options, when provided by the host, are selected by the server. The Android client does not implement client-side end-to-end encryption, network anonymity or independent inference routing; those guarantees must be established by the hosted service.

## Developer

**Android app:** [celery0co](https://github.com/celery0co)

**Original TOTEM LLM:** [fred-terzi](https://github.com/fred-terzi/totem-llm)

## Version

`2.4`

## Platform

Android
