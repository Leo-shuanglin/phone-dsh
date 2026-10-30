# Native Android MCP endpoint

The optional `PhoneDSHConfiguration.nativeMCP` serves `/mcp/native` directly on
the existing authenticated origin. This additive HTTP endpoint changes no
binary channel frames. `PDSH_PHONE_MCP_URL` and `PDSH_PHONE_MCP_TOKEN` select
the existing official `mcp-phone` Worker plugin. Only this exact same-origin
path bypasses `/v1/http`; authorization and `X-PDSH-Idempotency-Key` are preserved.

The native server accepts single JSON-RPC POST requests (`initialize`, `ping`,
`tools/list`, `tools/call`) and notifications (`initialized`, `cancelled`).
Notifications receive empty 202 responses; GET receives 405 (no SSE stream).
Tool calls are rejected with 503 when admission is closed; cancellation remains
available while draining. Host shutdown cancels and settles pending native work.

`PhoneDSHADB` serializes Android operations and uses a dedicated persistent
receipt store, rather than the exec ledger, keyed by the journal's opaque
`X-PDSH-Idempotency-Key` header. It synchronizes an intent before device dispatch,
replays completed results and refuses unresolved/evicted outcomes without
repeating side effects. The fingerprint binds tool name, arguments and native
endpoint. JSON-RPC IDs are used only for response correlation and cancellation.
The existing tool journal remains the Worker/session recovery authority.
This is crash recovery, not an atomic transaction with the Android device.

The HTTP golden example is [golden/native-mcp/tap.json](golden/native-mcp/tap.json).
Package management vectors are [golden/native-mcp/package-management.json](golden/native-mcp/package-management.json).
The tool catalog contains 25 Android tools. Application mutations use the
current foreground Android user unless an explicit numeric `userId` is supplied.
`android_install_apk` requires `source=workspace|device` and an absolute `apkPath`.
Workspace paths are confined to `/dsh/workspace`, with no symlinks; their required
SHA-256 must match the copied host bytes before any ADB connection is opened.
This content check rejects stale materialization without claiming a WAL barrier.
Workspace files are regular APK/ZIP files, 4 bytes–128 MiB. Their bytes bypass
JSON and stream as shell_v2 stdin into `pm install -S <size> -`, with ADB WRTE
backpressure, a final close-stdin frame and a 120-second execution deadline.
The JSON request remains limited to 16 KiB. Remote-device paths never resolve
against the iPhone workspace. Install, uninstall and clear require both a zero
exit status and the package manager's `Success` acknowledgement.
See [Android ADB](../../docs/android-adb.md) for limits and tools.
