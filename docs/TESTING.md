# Native Android device test matrix

Run on at least one recent Android device or emulator and, where practical, an Android 7–9 target. Use a non-production test account and do not use a wallet with meaningful funds for initial testing.

## Installation and account

1. Install the APK from the successful GitHub Actions `TOTEM-APK` artifact and launch it.
2. Confirm the TOTEM splash screen, Home screen and native navigation render without a crash.
3. Sign in with a test account. Confirm invalid credentials show a useful error and a valid session survives force-stop/relaunch when encrypted storage is available.
4. When public registration is enabled on the server, test registration with a unique test username, email and password. When disabled, confirm the sign-up entry is unavailable.
5. Test account recovery and password reset with test recovery codes only.
6. Sign out and verify the local session is cleared.

## Chat, threads and agent protocol

7. Open Chat and confirm the workspace, previous threads and their message history load.
8. Create a new thread, switch between threads, refresh the chat and verify the selected thread remains selected.
9. Send a harmless test message. Confirm the response streams and appears in history after refresh.
10. Trigger an agent tool that requires approval. Confirm the app displays the tool name and description, and that request payload details can be expanded.
11. Tap **Reject**. Confirm the app sends `toolApprovalResponse` with `approved: false`, displays the “Agent action denied” toast and lets the agent handle the rejection.
12. Trigger the same or another approval request and tap **Approve**. Confirm the app sends `toolApprovalResponse` with `approved: true` and the requested tool proceeds.
13. Trigger an approval request and do not respond. Confirm it expires according to the server timeout (currently 120 seconds in the public WebSocket plugin) and the server denies it.
14. Confirm the app never approves an agent tool request implicitly. If the approval UI cannot receive an event, the request must fail closed.

## Hosted service integrations

15. Open Wallet and confirm credits load from the hosted service. Confirm the read-only wallet balance displays either a balance, a no-wallet state or a clear unavailable state.
16. If the hosted deployment provides inference options, confirm the choices and selected value come from the server and that changing a selection persists. If the endpoint is unavailable, the selector should remain hidden without breaking chat. Do not infer E2EE or anonymity guarantees from the option label.
17. Test loss of network during sign-in, chat streaming and wallet loading. Confirm errors are understandable and retrying after network recovery works.

## UI, accessibility and security

18. Switch between system, light and dark appearance and check all native screens.
19. Open the keyboard in Chat. Confirm the composer stays visible, the message list scrolls and the bottom navigation behaves as expected.
20. Rotate the device during Home, sign-in, chat and wallet usage. Confirm there are no crashes or unexpected lost sessions.
21. Test on a small display and increase system font size. Confirm the approval card, payload details and Approve/Reject controls remain reachable.
22. Verify HTTPS-only networking, no TLS-error bypass, encrypted local session storage where supported, and no UI that requests a seed phrase or private key.

Keep test accounts, recovery codes, wallet addresses and personal messages out of screenshots and public bug reports.
