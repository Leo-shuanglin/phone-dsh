# Phone DSH for HarmonyOS

Native ArkUI client with an on-device Rust host and the production DSH Worker in
ArkWeb. This is a development HAP, verified on the official HarmonyOS 6 / API 20
ARM64 phone emulator. Full Android parity and physical-device validation remain
open. See [verification](../docs/design/harmony/VERIFICATION.md) for measured
behavior and remaining differences.

## Build

Install the official Huawei Command Line Tools, API 20 SDK, DevEco CLI, Node,
Cargo and the `aarch64-unknown-linux-ohos` Rust target. Install the repository's
`plugins/` dependencies and prepare the pinned Android Worker bundle at
`examples/probe-app/Bundle/worker` using the existing repository packer. The
bundle must include `probe-bundle.json`; the packer and DSH checkout commits must
match. Build-machine metadata is excluded from the HAP.

```sh
export DEVECO_CLI_CLT_PATH='<official-command-line-tools-root>'
export DEVECO_CLI='<devecocli-executable>'
export PDSH_DSH_CHECKOUT='<pinned-dsh-checkout>'
bash harmony/tools/build.sh
```

The output is `entry/build/default/outputs/default/entry-default-unsigned.hap`.
The local development emulator accepts it with its normal debug install flow.
Distribution signing is not configured.

```sh
hdc install -r harmony/entry/build/default/outputs/default/entry-default-unsigned.hap
hdc shell aa start -a EntryAbility -b com.phonedsh.app
node --test harmony/tests/*.test.mjs
cargo test --manifest-path harmony/host-rust/Cargo.toml
```

## Model service

Open Settings → Model service and add the provider address, model, protocol and
key. Keys are stored in the OS AssetStore and are injected only by the native
model proxy. They are excluded from Worker configuration, WAL and source.
DeepSeek's Messages endpoint uses an `/anthropic` base address and the `deepseek`
encoder. Selecting or editing a provider requires an engine restart to change
the active route; saving a key to the same active account changes subsequent
requests. The connection test checks the saved profile separately.

## Boundaries

Rust owns HTTP/WS, shared wire/WAL codecs and materialized storage. C++ provides
NAPI async bindings. ArkTS owns the UI, metadata, AssetStore and OS networking.
A stable hidden ArkWeb runs the original Worker and WorkerTunnel; local paired
adapters provide UI RPC and bounded VFS reads without patching DSH source.
The app runs without a Mac server or port forwarding.

[Protocol registration](../docs/protocol-changes/PC-20261104-harmony-host.md)
records the paired private APIs and partial conformance. Font and renderer
notices ship in the HAP and are listed in [THIRD_PARTY.md](THIRD_PARTY.md).
