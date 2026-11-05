// SPDX-License-Identifier: MIT
const output = document.querySelector('#status')
const button = document.querySelector('#start')
const verdict = document.querySelector('#verdict')
const checks = document.querySelector('#checks')
const labels = { webassembly: 'WebAssembly 执行', 'worker-ready': 'phoneDSH Worker 就绪', hello: '有序通道 HELLO', shell: '子 Worker Shell 写入', 'jq-wasi': '生产 jq WASI 计算', task: '任务结果与文件回读', 'boot-api': 'DSH 页面启动 API', 'host-wal': '主机写入记录核对', failure: '失败原因' }
let worker
const report = { platform: navigator.userAgent, status: 'not-run', stages: [] }
globalThis.__PDSH_PROBE_RESULT__ = report

function record(stage, status, detail) {
  report.stages.push({ stage, status, detail, at: new Date().toISOString() })
  const row = document.createElement('li')
  row.className = status
  row.textContent = (status === 'pass' ? '通过 · ' : '失败 · ') + (labels[stage] || stage) + (stage === 'failure' ? ' · ' + detail : '')
  checks.append(row)
  output.textContent = JSON.stringify(report, null, 2)
  console.info('PDSH_PROBE ' + JSON.stringify({ stage, status, detail }))
}

async function run() {
  button.disabled = true
  report.status = 'running'
  report.stages.length = 0
  checks.replaceChildren()
  verdict.textContent = '验证进行中，最长等待 240 秒。'
  let timer
  let config
  try {
    const response = await fetch('/config', { cache: 'no-store' })
    if (!response.ok) throw new Error('probe host configuration unavailable')
    config = await response.json()
    report.runId = config.runId
    const wasm = await WebAssembly.instantiate(new Uint8Array([
      0,97,115,109,1,0,0,0,1,7,1,96,2,127,127,1,127,3,2,1,0,
      7,7,1,3,97,100,100,0,0,10,9,1,7,0,32,0,32,1,106,11,
    ]))
    if (wasm.instance.exports.add(19,23) !== 42) throw new Error('WASM arithmetic failed')
    record('webassembly', 'pass', 'add(19,23) = 42')
    worker = new Worker('./probe-worker.mjs', { type: 'module' })
    const result = await new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error('Worker boot/task exceeded 240 seconds')), 240_000)
      worker.onerror = event => reject(new Error(event.message || 'Worker load failed'))
      worker.onmessage = event => {
        const message = event.data
        if (message.t === 'probe-stage') record(message.stage, message.status, message.detail)
        if (message.t === 'probe-result') resolve(message)
        if (message.t === 'probe-error') reject(new Error(message.message))
      }
      worker.postMessage({ t: 'probe-start', ...config, origin: location.origin })
    })
    record('task', 'pass', result)
    const { WorkerTunnel } = await import('./tunnel-client.mjs')
    const tunnel = new WorkerTunnel(worker)
    const bootResponse = await tunnel.fetch('/__boot__', { signal: AbortSignal.timeout(15_000) })
    if (!bootResponse.ok) throw new Error('real DSH /__boot__ returned ' + bootResponse.status)
    const boot = await bootResponse.json()
    if (!boot || typeof boot !== 'object' || !('injections' in boot)) throw new Error('DSH boot payload has no injections')
    record('boot-api', 'pass', { status: bootResponse.status, keys: Object.keys(boot) })
    const saved = await fetch('/report', {
      method: 'POST', headers: { 'content-type': 'application/json', 'authorization': `Bearer ${config.token}` },
      body: JSON.stringify({ ...report, result }),
    })
    const verified = await saved.json()
    if (!saved.ok || verified.status !== 'pass') throw new Error(verified.error || 'host evidence check failed')
    report.status = 'pass'
    report.hostEvidence = verified
    record('host-wal', 'pass', verified)
    verdict.textContent = verified.environment === 'desktop-control-only'
      ? '核心链路通过。当前为桌面校准，尚不能证明鸿蒙兼容。'
      : '核心链路通过。ArkWeb 身份需结合设备日志核对。'
  } catch (error) {
    report.status = 'fail'
    verdict.textContent = '核心链路未通过，请查看失败原因。'
    record('failure', 'fail', String(error.message || error))
    if (config) {
      try {
        await fetch('/report', {
          method: 'POST', headers: { 'content-type': 'application/json', 'authorization': `Bearer ${config.token}` },
          body: JSON.stringify(report),
        })
      } catch { console.error('PDSH_PROBE could not save failure report') }
    }
  } finally {
    clearTimeout(timer)
    worker?.terminate()
    output.textContent = JSON.stringify(report, null, 2)
    document.title = 'PDSH probe ' + report.status
    button.disabled = false
  }
}
button.addEventListener('click', run)
if (new URLSearchParams(location.search).get('autorun') === '1') void run()
