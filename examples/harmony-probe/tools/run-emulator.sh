#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
set -euo pipefail
PROBE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="${DEVECO_CLI:-devecocli}"
HDC="${HDC_BIN:-hdc}"
EVIDENCE="${PDSH_HARMONY_EVIDENCE:-$PROBE_ROOT/../../.audit/harmony-probe/emulator}"
mkdir -p "$EVIDENCE"
EVIDENCE="$(cd "$EVIDENCE" && pwd)"
command -v "$CLI" >/dev/null || { echo 'Set DEVECO_CLI to the devecocli executable.' >&2; exit 2; }
command -v "$HDC" >/dev/null || { echo 'Set HDC_BIN to the SDK hdc executable.' >&2; exit 2; }
node - <<'JS'
;(async () => {
  const response = await fetch('http://127.0.0.1:18765/report', { signal: AbortSignal.timeout(5000) })
  const result = await response.json()
  if (result.status !== 'not-run') throw new Error('Restart the diagnostic host with a fresh evidence directory before emulator verification')
})().catch(error => { console.error(error); process.exitCode = 1 })
JS
"$HDC" list targets > "$EVIDENCE/targets.txt"
"$HDC" shell param get const.ohos.fullname > "$EVIDENCE/os-version.txt"
"$HDC" shell param get const.ohos.apiversion > "$EVIDENCE/api-version.txt"
"$HDC" rport tcp:18765 tcp:18765
capture_evidence() {
  local outcome=$?
  "$HDC" shell hilog -x -T PhoneDSHProbe > "$EVIDENCE/hilog.txt" || true
  "$HDC" shell snapshot_display -f /data/local/tmp/phonedsh-harmony.jpeg > "$EVIDENCE/screenshot.log" 2>&1 || true
  "$HDC" file recv /data/local/tmp/phonedsh-harmony.jpeg "$EVIDENCE/emulator.jpeg" >> "$EVIDENCE/screenshot.log" 2>&1 || true
  return "$outcome"
}
trap capture_evidence EXIT
cd "$PROBE_ROOT/native"
"$CLI" check arkts
"$CLI" build
"$HDC" install "$PROBE_ROOT/native/entry/build/default/outputs/default/entry-default-unsigned.hap"
"$HDC" shell aa force-stop com.phonedsh.harmonyprobe
"$HDC" shell aa start -a EntryAbility -b com.phonedsh.harmonyprobe
node - "$EVIDENCE" <<'JS'
const fs = require('node:fs')
const path = require('node:path')
;(async () => {
  for (let attempt = 0; attempt < 240; attempt++) {
    const result = await (await fetch('http://127.0.0.1:18765/report')).json()
    if (result.status === 'pass' || result.status === 'fail') {
      fs.writeFileSync(path.join(process.argv[2], 'result.json'), JSON.stringify(result, null, 2))
      if (result.status !== 'pass' || result.environment !== 'arkweb-reported-by-user-agent') process.exitCode = 1
      console.log(JSON.stringify({ status: result.status, environment: result.environment }))
      return
    }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error('No completed ArkWeb result within 240 seconds')
})().catch(error => { console.error(error); process.exitCode = 1 })
JS
