# Android ADB control from iPhone

Phone DSH can connect directly to a configured Android TCP/IP ADB endpoint on
the LAN. The iPhone app implements the ADB protocol with Network.framework;
there is no desktop ADB server in the control path and no executable in the
Worker. The `PhoneDSHADB` SwiftPM product owns the transport, RSA identity,
command harness, settings and MCP tool endpoint.

## Connect

1. Put both devices on the same reachable Wi-Fi network. Enable Android
   developer options and USB debugging.
2. Enable **TCP/IP ADB** on Android. If the device has no network-ADB switch,
   connect it to a computer once and run `adb tcpip 5555`. After this setup,
   the iPhone connects directly; the computer is not a relay. Android may
   require this setup again after reboot.
3. Open iPhone **Settings → Android ADB**, enable control, enter the Android
   IP/hostname and TCP/IP ADB port (usually `5555`), then save.
4. Tap **Test connection**, unlock Android and accept the iPhone's debugging
   key. A successful check returns the model, Android version and screen size.
5. Ask the agent, for example: “检查 Android 连接，截图，然后打开系统设置。”

This implementation supports the classic RSA-authenticated TCP/IP transport
and requires `shell_v2` (Android 7+). **The six-digit pairing-code/TLS transport
in Android 11+ Wireless debugging is not implemented.** Its pairing/connect
ports are not interchangeable with the TCP/IP port. See Google's
[ADB connection instructions](https://developer.android.com/tools/adb).

## Tools

The existing official DSH MCP client mounts the native endpoint under `phone`:

| Tool name suffix (`mcp__phone__…`) | Behavior |
| --- | --- |
| `android_status` | Model, OS release and display size |
| `android_screenshot` | PNG image content, preserving binary bytes |
| `android_tap` | Tap screenshot pixel coordinates |
| `android_swipe` | Swipe or long press; duration 1–10000 ms |
| `android_input_text` | Printable ASCII into the focused field |
| `android_key` | Home, Back, Enter, app switch, volume and numeric keys |
| `android_launch_app` | Launch an installed package |
| `android_ui_dump` | Accessible UI hierarchy and bounds |
| `android_shell` | Shell command as Android's ADB shell user, up to 120 s |
| `android_list_packages` | Installed packages, APK paths and installer; filter by name, system/third-party, enabled state and user |
| `android_package_info` | APK/split paths, versions, permissions and package metadata |
| `android_install_apk` | Install one APK from the iPhone workspace or an existing Android path |
| `android_uninstall_app` | Uninstall by package ID for a user; optional keep-data |
| `android_force_stop` | Stop an app without uninstalling it |
| `android_clear_app_data` | Delete the selected app's user data/cache, including login state |
| `android_set_app_enabled` | Enable or disable an app for a user |
| `android_install_existing_app` | Restore an on-device package for a user |
| `android_app_permission` | Grant/revoke a declared runtime permission |
| `android_list_users` | Users/profiles and foreground user |
| `android_open_uri` | Open a web URL or deep link with an Android VIEW intent |
| `android_logcat` | Bounded recent logs, buffer and priority filters |
| `android_device_info` | Model, OS/API, display, battery and storage |
| `android_list_apk_files` | Find APK installer files in an Android directory |
| `android_delete_apk_file` | Delete one APK installer in shared storage or `/data/local/tmp` |
| `android_command_help` | Read the actual device's help for package, activity, input, settings, files, logs or services |

Screenshot coordinates are **pixels**, not normalized coordinates. The active
model must declare image-input support for the official MCP client to project
images into its context; `android_ui_dump` also works with text-only models.
Protected Android screens can refuse capture or produce black images.
Unicode text injection needs a compatible Android IME; unsupported text is
rejected explicitly rather than silently dropped. No helper APK is installed.

## Applications and APK files

“有哪些 APK” can mean installed applications or downloaded installer files.
Use `android_list_packages` for the former and `android_list_apk_files` for the
latter. Uninstalling takes a **package ID**, not a filename. Deleting a downloaded
APK does not uninstall its app. Package tools default to the current foreground
Android user; pass `userId` for a particular profile. Removing a system app for
one user does not delete the APK in the read-only system image.

For example, model tool arguments (with the usual `mcp__phone__` name prefix):

```json
{"name":"android_list_packages","arguments":{"kind":"third_party"}}
{"name":"android_package_info","arguments":{"packageName":"com.example.app"}}
{"name":"android_install_apk","arguments":{"source":"device","apkPath":"/data/local/tmp/example.apk"}}
{"name":"android_uninstall_app","arguments":{"packageName":"com.example.app","keepData":false}}
```

To install an APK from the iPhone, import/download it into the DSH workspace,
compute `sha256sum /dsh/workspace/downloads/example.apk` using the Worker shell,
then call `android_install_apk` with `source: "workspace"`, that absolute
`apkPath`, and the 64-character digest in `sha256`. The native host reads only
its configured materialized workspace, rejects symlinks and checks the digest
before opening ADB. A missing/mismatching copy means the file changed or is not
yet synchronized: verify the workspace and digest, then issue a new call. No
Android command is sent on a local validation failure.

The APK is copied into a bounded native buffer and sent through shell_v2 stdin
to `pm install -S <size> -`; no desktop binary, base64 tool payload or Android
temporary installer is required. Maximum file size is 128 MiB, timeout 120 s.
Slow transfers may hit that deadline. The package manager checks the APK's
manifest, signature and compatibility; a ZIP header alone does not make it a
valid installable APK. Install/uninstall/clear succeed only with exit code zero
and an explicit `Success` response. Failures such as signature mismatch, version
downgrade, insufficient space or device policy are returned to the model.

This tool installs **one APK**. AAB, XAPK, APKS and split-APK sets are unsupported.
`replaceExisting` defaults to true (`-r`); false requests `-R`, which requires
device support. `allowTest` (`-t`) and `grantPermissions` (`-g`) default to false.
Android/OEM versions may reject particular flags; consult the device's help.

## More Android commands

`android_command_help` reads help from the connected device; `android_shell`
accepts the remote shell command **without** the desktop `adb shell` prefix.
Examples for capabilities beyond the structured tools:

| Purpose | Remote shell command |
| --- | --- |
| Inspect running activities | `dumpsys activity activities` |
| Inspect an app's memory | `dumpsys meminfo com.example.app` |
| Inspect permissions | `pm list permissions -g -d` |
| Discover supported device features | `pm list features` |
| Read system settings | `settings list system` |
| Read a global setting | `settings get global airplane_mode_on` |
| Inspect network addresses/routes | `ip addr; ip route` |
| Inspect available service commands | `cmd -l; dumpsys -l` |
| Inspect processes | `ps -A` |
| Inspect files/storage | `ls -la /sdcard/Download; df -h` |

ADB shell permissions still apply: private app directories, signature
permissions and privileged operations may require device-side support/root.
`adb connect`, `adb pair`, `adb push/pull`, port forwarding and reboot service
commands are desktop/protocol operations, not commands to paste into the
Android shell. This client does not implement those extra protocol services.

Command references: [Android's ADB/pm/am documentation](https://developer.android.com/tools/adb),
[AOSP package manager command implementation/help](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java),
[AOSP shell_v2 framing](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/android14-release/shell_protocol.h).

## Integration and execution

```swift
import PhoneDSHADB

configuration.nativeMCP = ADBMCPServer.shared
try await ADBMCPServer.shared.setWorkspaceDirectory(materializedWorkspaceURL)
```

The host publishes `/mcp/native` on its existing loopback origin and derives
`PDSH_PHONE_MCP_URL` and `PDSH_PHONE_MCP_TOKEN` from the bound listener. The
Worker fetch bridge preserves the bearer and journal idempotency header on
that exact same-origin path. The DSH `mcp-phone` profile row registers the
tools, with a 130-second tool timeout. Rebuild the Worker image after changing
the profile or fetch bridge; an old image does not contain this routing fix.

The Android endpoint comes from native settings, never tool arguments. The
2048-bit RSA private key stays in a device-only Keychain entry. Disabling
control takes effect on subsequent calls without restarting the engine.
One operation runs at a time; a concurrent call reports busy. Cancellation,
timeouts and host shutdown close the current ADB socket. Closing a socket
does not undo an input event or guarantee termination of a detached Android
process, so interrupted actions have an explicitly unknown outcome.

Journaled requests carry `X-PDSH-Idempotency-Key`. Before dispatch, the native
server writes a synchronized intent in Application Support under
`phone-dsh/android-adb-receipts`. Completed results are replayed; an unresolved
intent refuses to execute again after an app crash. Result bodies above
16 KiB (including screenshots) remain in the bounded in-memory cache, while
their persistent receipts retain a diagnostic instead of large media. Receipt
metadata is not automatically expired. A server instance admits at most 4096
distinct journaled calls; restarting clears the memory cache, not the durable
receipts. These receipts protect against process restart, not power-loss
transactions with Android. Unkeyed callers cannot be deduplicated.

## Verification

```sh
swift test --filter PhoneDSHADBTests
(cd plugins && node --test tests/llm-install.test.ts)
bash scripts/ci/smoke.sh
```

Tests cover ADB framing/checksums, RSA signatures and Android public-key encoding,
authorization fallback, binary shell output, real TCP timeout/cancellation,
MCP registration through the official DSH client, authenticated loopback access,
input validation, PNG content, serialization and restart deduplication. Package
tests cover command vectors, workspace confinement/digests, byte-exact APK stdin,
upload backpressure/early exit, and package-manager failure acknowledgements. The
macOS smoke asserts all 25 tools appear in the real WKWebView Worker.
These use simulated Android responses. Physical iPhone → Android operation
and the Android authorization dialog still require device acceptance testing.

Protocol references: [AOSP ADB protocol](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/android14-release/protocol.txt),
[AOSP public-key format](https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcrypto_utils/android_pubkey.cpp),
[MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports).
