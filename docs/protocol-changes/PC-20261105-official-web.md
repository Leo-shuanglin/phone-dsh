# Official DeepSeek web tools on phones

```yaml
change: PC-20261105-official-web
contracts: [P05, P09, P16, P18, P20]
status: implemented
breaking: false
base: 6c040fe1fc56c0d263293faaf208105a5e8fc315
contract_revision: 3f349c2229be2e1bc01559b4309af33832c3b4f1
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
| New host → old Worker | The older Worker accepts the four provider entries. A cookie-authenticated Android launch can still bypass the gateway until the Worker image is updated. An older Harmony Rust host ignores the optional private flag and keeps search off. |
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


Android product shell follow-up:

- The Android launch already supplies the per-start token in the Worker environment
  and omits an explicit WebSocket bearer. The bridge now uses that same token
  on the existing gateway only for the canonical official web operations.
  The Android gateway already accepts it. No launch variables, endpoints or
  headers were added. Other requests keep their previous transport.
- Android process groups keep the complete first row ID. Two groups anchored
  on different tool calls remain distinct even when both event IDs end in
  `tool-call`. Row keys are local to the visible list and are not persisted.
- Acceptance uses the cookie-authenticated bridge regression, the Android
  projection regression and the rebuilt APK on a physical phone.

| Revision | Change |
| --- | --- |
| `01b003e3` | Froze the scoped bearer fallback and Android process row identity after reproducing both defects in the product shell. |


### iOS follow-model parity

Contract revision `3f349c22` extends the same official web grant to iOS.
The model settings capture the selected Keychain account and create a scoped
provider only when the official profile has a readable key. The app publishes
the existing Search/Fetch provider variables. The gateway checks the exact
HTTPS POST target before choosing that provider, strips caller credentials,
refuses redirects, and handles the response as sensitive.

The saved model and independent-search documents retain their existing formats.
The Worker already supports the Search/Fetch provider pair. The older iOS app
continues to read the same preferences after rollback and selects its previous
Messages search behavior. The Swift host and gateway rebuild together.

The shared gateway request fixture covers positive paths and invalid methods,
origins, queries, fragments and aliases. iOS gateway tests also cover a missing
Bearer at dispatch. App launch regressions cover official follow-model settings
and the independent key-clear path.
