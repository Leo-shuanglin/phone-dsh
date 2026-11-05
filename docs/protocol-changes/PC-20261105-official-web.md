# Official DeepSeek web tools on phones

```yaml
change: PC-20261105-official-web
contracts: [P05, P09, P16, P18, P20]
status: implemented
breaking: false
base: 6c040fe1fc56c0d263293faaf208105a5e8fc315
contract_revision: 81764173ef7e76334abb3b7e7ea11c35c1749b77
```

The Android and Harmony hosts do not supply a search provider. With the new launch,
a supported official DeepSeek model profile with a readable saved key enables both
web tools. iOS independent mode publishes neither tool after its key is cleared
and the engine starts again. It keeps the endpoint and the selected mode.

| Boundary | Delta |
| --- | --- |
| P05 | The host grants two exact POST operations on the official DeepSeek origin. It uses the selected model key as Bearer authentication, resolves it at request time, redacts the response, and refuses redirects. Existing request limits, cancellation and failure handling apply. |
| P09 | The existing search settings page explains automatic reuse and engine restart. The page adds no key field or write action. |
| P16 | Native launch producers publish the existing Search/Fetch provider IDs, official base URL and placeholder only when configured. The existing Worker loader, image profile and both boot parsers consume the same environment object. iOS clears stale independent provider entries in a reused plan. |
| P18 | Harmony's private `model-launch.json` adds optional root boolean `officialWebTools`. Absent, false or non-boolean values disable the feature. Rust does not copy it into the public Worker provider document. No saved model profile or credential format changes. |
| P20 | The packed phone profile in this PR applies its existing web-tool flags to each bundled preset. Asset formats are unchanged. |

The gateway schema is unchanged. The owning [gateway clause](../../spec/v1/gateway-official-web.md)
and [shared route fixtures](../../spec/v1/golden/gateway/official-deepseek-web/cases.json)
define valid and invalid model bases and operations. The [Worker environment owner](../../Sources/PhoneDSH/PhoneDSH.swift)
records the reused launch entries.

| Producer → consumer | Behavior |
| --- | --- |
| Old host → new Worker | Absent IDs keep tools hidden. Older Harmony launch documents have no flag and remain readable. |
| New host → old Worker | The pinned Worker already accepts the four environment entries and routes external fetches through the existing gateway. An older Harmony Rust host ignores the optional private flag and keeps search off. |
| New host → new Worker | Both official operations use the selected stored model key. Missing, unreadable and nonofficial launch configurations leave the tools hidden. |
| Persisted data → new or rollback | Existing profiles and secrets remain readable. The host regenerates the private launch document at startup. A rollback drops the extra routes and flag. |

The coordinator owns the frozen contract, the iOS launch plan and integration.
The Android lane owns `android/`. The Harmony lane owns `harmony/`.
Acceptance uses the Android gateway and launch tests, Harmony route fixtures and Rust
boot tests, iPhone simulator launch and credential tests, and the pinned packed-image
regression. The full verification matrix is recorded on [PR #37](https://github.com/launsion-boop/phone-dsh/pull/37).

| Revision | Change |
| --- | --- |
| `eb8ac88b` | Frozen gateway scope, shared request cases and Worker launch contract. |
| `81764173` | Registered the same gateway clause in its own document to stay within the gateway spec line cap. The fixture still decodes to the same values. |
