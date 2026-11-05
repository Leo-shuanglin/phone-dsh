# pdsh-host — native phone-dsh host for HarmonyOS

This crate is the native Rust host of the HarmonyOS product shell. It is
standalone (`[workspace]` is declared here) and consumes the shared
`pdsh-wire`/`pdsh-wal` crates read-only through
`../../shell/crates/...`.

It binds one loopback origin on `127.0.0.1:<ephemeral>` and provides:

| Route | Description |
|---|---|
| `GET /app/*` | WebView assets from `assetsRoot/app` |
| `GET /assets/*` | Worker bundle assets from `assetsRoot/worker` |
| `GET /boot-config?token=...` | `imageURL`, `overlays`, and `xl` transport facts |
| `GET /overlay.tar.gz` | `gzip(ustar(home/..., workspace/...))` from `root/materialized` |
| `GET /health` | token-gated status for the verifier |
| `GET /channel` | token-verified binary WebSocket |
| `POST /v1/http` | callback-delegated raw HTTP gateway |
| `POST /chat/completions`, `/v1/chat/completions`, `/responses`, `/v1/messages` | callback-delegated model protocol routes |

Every route is token-gated. The token can arrive as `Authorization: Bearer`,
`?token=` (the launch page/channel spelling), or the `xl_launch_token` cookie.
All routes bind the `Host` header to `127.0.0.1:<bound port>`. The server never
logs a token, header, body or prompt.

The C ABI is in `include/pdsh_host.h`. All JSON strings returned by the ABI must
be freed with `pdsh_host_free`; panics are caught at every exported entry point.

## Build and test

```sh
cargo test --all-targets
cargo clippy --all-targets
cargo build --release --target aarch64-unknown-linux-ohos --lib
```

The OHOS cross-check is expected to pass with the toolchain that ships the
`aarch64-unknown-linux-ohos` target. The release artifact is
`target/aarch64-unknown-linux-ohos/release/libpdsh_host.a` (the crate declares `staticlib` and `rlib`; the C++ NAPI
module links the staticlib into `libpdshhost.so`).

`cargo run --bin pdsh-host-desktop -- --root DIR --assets DIR` starts the same
host on macOS/Linux for a local smoke run (no ArkTS callback, so the model
routes answer 503).

## Persistence

- `root/wal/<epoch:08d>.<segment:06d>.xlwal`: the shared `pdsh-wal` 40-byte
  header and `len|crc32c|mseq|body` records. The crate writes a new epoch on
  every start, rolls a segment before 8 MiB, and fsyncs the segment (and its
  directory) **before** emitting `VFS_ACK level 1`.
- `root/materialized/{home,workspace}`: the derived VFS. Mutations are
  idempotent; complete writes are temp-file+rename, appended writes use the
  shared D9 decision, and a base mismatch fails loudly. Symlinked scopes,
  ancestors and targets are refused.
- `root/journal/<hex(session_id)>.jnl`: length+CRC32C framed journal records,
  fsynced before `JOURNAL_ACK level 1`. Session ids are D6-validated and
  hex-encoded for the filename.

Recovery replays every retained segment from mseq 1. Only the newest segment's
valid torn tail is truncated (and counted in `truncatedTailBytes`); a CRC or
order failure anywhere else fails start loudly. There is no compaction and no
manifest checkpoint, so every acknowledged record is retained and replayed.

## Deliberate limitations (real errors, not placeholders)

- **No level-2 acknowledgement.** No power-loss level has been measured. A
  `VFS_BARRIER` asking for level 2 is answered with the level-1 truth
  (`VFS_ACK level 1`) and `durable_mseq` stays 0.
- **The exec bridge is not implemented.** Every `EXEC_REQ` (and `EXEC_CTL`)
  produces one terminal `EXEC_EVT` error with `code: "UNSUPPORTED"`; no process
  is started and no success is faked.
- **Console text is not forwarded.** `CONSOLE` frames are bounded and counted
  (`consoleBytes` / `lastConsoleLevel` in `/health`); the text is dropped so a
  prompt or header can never enter a log. The page runtime's own readiness
  handshake is outside this crate.
- **No TLS and no external sockets.** The listener is IPv4 loopback only.
- **Model routes need the ArkTS callback.** Without it they answer 503
  `host_callback_unavailable`; Rust holds no provider credential.
- **`pdsh_host_install_bundle` replaces the assets directory.** A running host
  captured its canonical static roots at start; restart it to serve the
  installed tree.
- **Overlay/WAL memory bounds.** An overlay is built in memory and refused over
  256 MiB or 200,000 entries. A single WAL segment is refused above 512 MiB at
  recovery. Static assets are streamed, not buffered.
- **Transport token in URLs.** `imageURL`, `overlays` and `channelURL` carry the
  per-start token query (the one launch credential spelling). The token is
  constant-time compared, never logged, and stripped from the path handed to
  the ArkTS HTTP callback.
