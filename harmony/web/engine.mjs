// SPDX-License-Identifier: MIT
import { WorkerTunnel } from './tunnel-client.mjs'

const streams = new Map()
const calls = new Map()
let nextId = 0
let worker
let tunnel
let launchToken = ''
let readyResolve
let readyReject
const ready = new Promise((resolve, reject) => { readyResolve = resolve; readyReject = reject })
ready.catch(() => {})
function detail(error) {
  let text = String(error?.message || error)
  if (launchToken) text = text.split(launchToken).join('[redacted]')
  return text.replace(/Bearer\s+[^\s,;]+/gi, 'Bearer [redacted]').slice(0, 1500)
}
function emit(event) {
  if (!globalThis.PdshNative || typeof PdshNative.postMessage !== 'function') throw new Error('Native page bridge is unavailable')
  PdshNative.postMessage(JSON.stringify(event))
}
function fail(error) {
  const message = detail(error)
  document.querySelector('#runtime-status').textContent = message
  readyReject(new Error(message))
  emit({ type: 'error', message })
}
async function rpc(method, args) {
  if (!/^[\w$./-]+$/.test(method) || method.includes('..')) throw new Error('Invalid DSH method')
  const rpcId = 'harmony-' + crypto.randomUUID()
  const response = await tunnel.fetch('/api/' + method, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ type: 'client-request', rpcId, method, payload: { args: args || {} } }),
    signal: AbortSignal.timeout(60_000),
  })
  if (!response.ok) throw new Error('DSH API HTTP ' + response.status)
  const envelope = await response.json()
  if (envelope.type !== 'server-response' || envelope.rpcId !== rpcId) throw new Error('DSH reply identity mismatch')
  if (envelope.result?.ok !== true) throw new Error(envelope.result?.error?.code + ': ' + envelope.result?.error?.message)
  return envelope.result.value
}
function openStream(streamId, endpoint, payload) {
  streams.get(streamId)?.abort()
  const abort = new AbortController()
  streams.set(streamId, abort)
  void (async () => {
    try {
      for await (const value of tunnel.open(endpoint, payload, abort.signal)) {
        emit({ type: 'event', streamId, value })
      }
      emit({ type: 'stream-end', streamId, error: '' })
    } catch (error) {
      emit({ type: 'stream-end', streamId, error: abort.signal.aborted ? 'cancelled' : detail(error) })
    } finally { if (streams.get(streamId) === abort) streams.delete(streamId) }
  })()
}
function workerCommand(method, args) {
  const id = ++nextId
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { calls.delete(id); reject(new Error('Worker command timed out')) }, 120_000)
    calls.set(id, { resolve, reject, timer })
    worker.postMessage({ t: 'pdsh-native-command', id, method, args })
  })
}
async function call(method, args) {
  await ready
  if (method === '$follow') {
    const streamId = args.streamId || args.sessionId
    openStream(streamId, 'session/follow', { args: { request: {
      address: { kind: 'session', sessionId: args.sessionId }, maxMessages: 64, assistantStream: true,
    } } })
    return { started: true, streamId }
  }
  if (method === '$events') {
    openStream(args.streamId || 'events', '$events', { args: {} })
    return { started: true }
  }
  if (method === '$close') {
    streams.get(args.streamId)?.abort()
    return { closed: true }
  }
  if (method.startsWith('$')) return await workerCommand(method, args || {})
  return await rpc(method, args || {})
}
globalThis.__PDSH_NATIVE_CALL__ = frame => {
  if (!frame || typeof frame.id !== 'number' || typeof frame.method !== 'string') return
  void call(frame.method, frame.args).then(
    value => emit({ type: 'reply', id: frame.id, ok: true, value }),
    error => emit({ type: 'reply', id: frame.id, ok: false, error: detail(error) }),
  )
}
try {
  const response = await fetch('/boot-config', { cache: 'no-store', credentials: 'same-origin' })
  if (!response.ok) throw new Error('Native boot configuration refused: ' + response.status)
  const config = await response.json()
  launchToken = config.xl?.token || ''
  if (!launchToken) throw new Error('Authenticated native boot configuration has no channel token')
  history.replaceState(null, '', location.pathname)
  worker = new Worker('./product-worker.mjs', { type: 'module' })
  tunnel = new WorkerTunnel({
    postMessage: (message, transfer) => worker.postMessage(message, transfer || []),
    addEventListener: (type, listener) => worker.addEventListener(type, event => {
      if (type === 'message' && ['pdsh-ready', 'pdsh-error', 'init-error', 'pdsh-native-result'].includes(event.data?.t)) return
      listener(event)
    }),
  })
  worker.addEventListener('message', event => {
    const message = event.data
    if (message?.t === 'pdsh-ready') {
      document.querySelector('#runtime-status').textContent = '本地运行时已就绪'
      readyResolve()
      emit({ type: 'ready' })
    } else if (message?.t === 'pdsh-error' || message?.t === 'init-error') {
      fail(new Error(message.message || 'Worker initialization failed'))
    } else if (message?.t === 'pdsh-native-result') {
      const pending = calls.get(message.id)
      if (!pending) return
      clearTimeout(pending.timer)
      calls.delete(message.id)
      if (message.ok) pending.resolve(message.value)
      else pending.reject(new Error(message.error))
    }
  })
  worker.addEventListener('error', event => fail(new Error(event.message || 'Worker failed')))
  worker.postMessage({ t: 'pdsh-native-init', image: config.image || config.imageURL,
    overlays: config.overlays || [], xl: config.xl })
  emit({ type: 'page-attached' })
} catch (error) { fail(error) }
