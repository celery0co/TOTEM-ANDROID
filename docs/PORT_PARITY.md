# Android API parity and verification

## Current architecture

The current Android client is native Kotlin / Jetpack Compose. It does not use the hosted website as its chat or wallet UI. The app sends HTTPS requests to `https://beta.totemai.xyz/api` and opens an authenticated WebSocket for agent execution.

The Android API client is in `app/src/main/java/xyz/totemai/app/TotemApi.kt`. The UI is in `app/src/main/java/xyz/totemai/app/ui/screens/`.

## Routes checked against the public TOTEM server source

The route names below were compared with the public `fred-terzi/totem-llm` repository, branch `main-totem`, source snapshot commit `8d1cb870797c8e9f8eb6087cf2d7820c2b38eaec`.

| Android request | Server source |
| --- | --- |
| `GET /setup-complete` | `server/endpoints/system.js` — setup-complete route |
| `POST /request-token` | `server/endpoints/system.js` — request-token route |
| `GET /system/public-registration` | `server/endpoints/system.js` — public-registration route |
| `POST /system/new-user` | `server/endpoints/system.js` — new-user route |
| `POST /system/recover-account` | `server/endpoints/system.js` — recover-account route |
| `POST /system/reset-password` | `server/endpoints/system.js` — reset-password route |
| `GET /workspaces` | `server/endpoints/workspaces.js` |
| `GET /workspace/:slug/chats` | `server/endpoints/workspaces.js` |
| `POST /workspace/:slug/thread/new` | `server/endpoints/workspaceThreads.js` |
| `GET /workspace/:slug/threads` | `server/endpoints/workspaceThreads.js` |
| `GET /workspace/:slug/thread/:threadSlug/chats` | `server/endpoints/workspaceThreads.js` |
| `POST /workspace/:slug/stream-chat` | `server/endpoints/chat.js` |
| `POST /workspace/:slug/thread/:threadSlug/stream-chat` | `server/endpoints/chat.js` |
| `GET /credits` | `server/endpoints/credits.js`; mounted by `server/index.js` |
| `GET /wallet/balance` | `server/endpoints/wallet.js` |
| `WS /agent-invocation/:uuid` | `server/endpoints/agentWebsocket.js`; mounted under the API router |

The server mounts the API router at `/api`, so these routes are called under `/api`. The agent WebSocket therefore uses `wss://beta.totemai.xyz/api/agent-invocation/:uuid`.

The request and response shapes used by the Android client were also checked against the corresponding route handlers where the public source exposes them. This is a source-code comparison, not a claim that every authenticated request was exercised against the hosted production deployment.

## Deployment-specific inference options

The Android client optionally requests:

- `GET /workspace/:slug/inference-options`
- `POST /workspace/:slug/inference-option`

These two routes were **not found in the public source snapshot or its generated OpenAPI document** checked above. They may be specific to the hosted beta deployment. The UI already treats the options as optional: if the server does not return them, the selector is hidden. Do not describe these routes as verified until the hosted deployment or Fred confirms them.

The Android client only displays the options returned by the server and sends a selected key back to the server. It does not implement client-side E2EE, network anonymity or independent model routing.

## Agent tool approval protocol

The public server source defines the `toolApprovalRequest` WebSocket event with `requestId`, `skillName`, `payload`, `description` and `timeoutMs`. The client responds with `toolApprovalResponse`, the same `requestId` and an explicit boolean `approved`. The Android screen now exposes Approve and Reject controls and does not approve implicitly. If the UI cannot receive a prompt, the client fails closed and rejects that request.

## Verification limits

- Public source reference: [fred-terzi/totem-llm, main-totem](https://github.com/fred-terzi/totem-llm/tree/main-totem), snapshot `8d1cb870797c8e9f8eb6087cf2d7820c2b38eaec`.
- Live requests to `beta.totemai.xyz` could not be completed from the review environment because DNS resolution for the host failed. The live deployment was therefore **not** verified in this review.
- Authenticated routes also require a valid test account/session. No account credentials or tokens were available in the review environment.
- The inference-option routes remain deployment-specific and unverified against the public source snapshot.

Before a classroom demo or public hand-off, run the device checklist in `docs/TESTING.md` against a real TOTEM test account and confirm the deployment-specific inference routes with the hosted service owner.
