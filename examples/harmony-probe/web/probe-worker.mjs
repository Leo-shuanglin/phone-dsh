// SPDX-License-Identifier: MIT
let handle
let shellForwarded = false
const send = message => postMessage(message)
addEventListener('message', async event => {
  if (handle && (event.data?.t === 'req' || event.data?.t === 'abort')) {
    event.stopImmediatePropagation()
    handle.host.handleMessage(event.data)
    return
  }
  if (event.data?.t === 'shell-start' && !shellForwarded) {
    event.stopImmediatePropagation()
    await import('/bundle/worker-entry.js')
    shellForwarded = true
    dispatchEvent(new MessageEvent('message', { data: event.data }))
    return
  }
  if (event.data?.t !== 'probe-start') return
  event.stopImmediatePropagation()
  const { origin, token, runId, bootId } = event.data
  try {
    const { startPhoneWorker } = await import('/bundle/worker-entry.js')
    handle = await startPhoneWorker({
      image: origin + '/bundle/image.tar.gz',
      channelURL: origin.replace(/^http/, 'ws') + '/channel',
      token, bootId, sessionEpoch: 1,
      env: {
        PDSH_DSH_LAUNCH_TOKEN: token,
        DSH_TELEMETRY_DISABLED: '1',
        PDSH_MODEL_KEY_STATE: 'absent',
        PDSH_MODEL_ROUTE: 'none',
      },
      fence: { owner: 'phone', generation: 0n, wal_seq: 0n },
      pagePost: message => {
        if (message.t === 'pdsh-ready') send({ t: 'probe-stage', stage: 'worker-ready', status: 'pass', detail: message.t })
        if (message.t === 'pdsh-error') send({ t: 'probe-error', message: String(message.message) })
        if (message.t !== 'pdsh-ready' && message.t !== 'pdsh-error') send(message)
      },
    })
    send({ t: 'probe-stage', stage: 'hello', status: 'pass', detail: 'production WebSocketChannelClient connected' })
    const loader = handle.host.modules
    if (!loader?.createRequire) throw new Error('production host module loader is unavailable')
    const require = loader.createRequire('/dsh/package.json')
    const { spawn } = require('node:child_process')
    const expected = 'PDSH_HARMONY_' + runId
    const path = '/dsh/home/phonedsh-harmony-' + runId + '.txt'
    const child = spawn('bash', ['-c', `printf '%s\n' '${expected}' > '${path}'; cat '${path}'`], {
      cwd: '/dsh/home', env: { PATH: '/usr/bin:/bin:/dsh/bin', HOME: '/dsh/home' },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', data => { stdout += String(data) })
    child.stderr.on('data', data => { stderr += String(data) })
    const exitCode = await new Promise((resolve, reject) => {
      child.on('error', reject)
      child.on('close', resolve)
    })
    if (exitCode !== 0) throw new Error(`real shell exited ${exitCode}: ${stderr.slice(0, 1200)}`)
    if (stdout !== expected + '\n') throw new Error('shell output mismatch: ' + stdout.slice(0, 500))
    send({ t: 'probe-stage', stage: 'shell', status: 'pass', detail: { exitCode, stdout } })
    if (!handle.jq) throw new Error('production image has no jq WASI engine')
    let jqOutput = ''
    const jq = await handle.jq.toolbox.run({
      program: 'jq-wasi', argv: ['-n', '6 * 7'], cwd: '/dsh/home',
      maxStdoutBytes: 4096, maxStderrBytes: 4096,
      onStdout: bytes => { jqOutput += new TextDecoder().decode(bytes) },
      onStderr: bytes => { stderr += new TextDecoder().decode(bytes) },
    })
    if (jq.exitCode !== 0 || jqOutput !== '42\n') throw new Error('production jq WASI failed: ' + stderr.slice(0, 1000))
    stdout += jqOutput
    send({ t: 'probe-stage', stage: 'jq-wasi', status: 'pass', detail: { output: jqOutput, exitCode: jq.exitCode } })
    const bytes = await handle.vfs.promises.readFile(path)
    const readback = typeof bytes === 'string' ? bytes : new TextDecoder().decode(bytes)
    if (readback !== expected + '\n') throw new Error('VFS readback mismatch')
    await handle.vfs.flush()
    send({ t: 'probe-result', exitCode, stdout, readback, path, runId })
  } catch (error) {
    send({ t: 'probe-error', message: String(error.stack || error).slice(0, 2400) })
  }
})
