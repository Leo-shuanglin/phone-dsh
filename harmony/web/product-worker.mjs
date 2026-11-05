// SPDX-License-Identifier: MIT
let handle
let shellForwarded = false
let booting = false
const pending = []
const decoder = new TextDecoder()
const encoder = new TextEncoder()
// image-resource-4: frozen byte snapshots live only in the Worker. At most four
// readers, 60s TTL, 16 MiB frozen bytes and 256 KiB base64 chunks per reply.
const binaryReads = new Map()
const MAX_BINARY_SNAPSHOT_BYTES = 16 * 1024 * 1024
const BINARY_CHUNK_BYTES = 256 * 1024
const BINARY_SNAPSHOT_TTL_MS = 60 * 1000
const MAX_BINARY_READERS = 4
function binaryHandle() {
  return 'bin-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
}
function pruneBinaryReads(now) {
  for (const [id, snapshot] of binaryReads) {
    if (now - snapshot.openedAt > BINARY_SNAPSHOT_TTL_MS) {
      clearTimeout(snapshot.timer)
      binaryReads.delete(id)
    }
  }
}
function discardBinaryRead(id) {
  const snapshot = binaryReads.get(id)
  if (snapshot) clearTimeout(snapshot.timer)
  return binaryReads.delete(id)
}
function mimeFor(path) {
  const clean = path.split('?')[0].split('#')[0].toLowerCase()
  const dot = clean.lastIndexOf('.')
  const extension = dot < 0 ? '' : clean.substring(dot + 1)
  if (extension === 'png') return 'image/png'
  if (extension === 'jpg' || extension === 'jpeg') return 'image/jpeg'
  if (extension === 'webp') return 'image/webp'
  if (extension === 'gif') return 'image/gif'
  if (extension === 'bmp') return 'image/bmp'
  if (extension === 'heic' || extension === 'heif') return 'image/heic'
  return 'application/octet-stream'
}
function base64OfBytes(bytes) {
  let out = ''
  const step = 0x8000
  for (let offset = 0; offset < bytes.length; offset += step) {
    out += btoa(String.fromCharCode.apply(null, bytes.subarray(offset, offset + step)))
  }
  return out
}
function safePath(path) {
  if (typeof path !== 'string' || path.includes('\0') || path.split('/').includes('..') ||
      !['/dsh/home', '/dsh/workspace', '/dsh/tmp', '/tmp'].some(root => path === root || path.startsWith(root + '/'))) {
    throw new Error('Path is outside the Worker writable namespace')
  }
  return path
}
function fromBase64(text) {
  if (typeof text !== 'string' || text.length > 24 * 1024 * 1024) throw new Error('Attachment exceeds 16 MiB')
  return Uint8Array.from(atob(text), char => char.charCodeAt(0))
}
async function command(method, args) {
  const fs = handle.vfs.promises
  if (method === '$diagnose') return { booted: true, jq: Boolean(handle.jq), python: Boolean(handle.python) }
  if (method === '$writeFile') {
    const path = safePath(args.path)
    const bytes = fromBase64(args.base64)
    await fs.mkdir(path.slice(0, path.lastIndexOf('/')), { recursive: true })
    await fs.writeFile(path, bytes)
    await handle.vfs.flush()
    return { path, bytes: bytes.byteLength }
  }
  if (method === '$readFile') {
    const path = safePath(args.path)
    const stat = await fs.stat(path)
    if (stat.size > 4 * 1024 * 1024) throw new Error('Preview exceeds 4 MiB')
    const bytes = await fs.readFile(path)
    return { path, text: typeof bytes === 'string' ? bytes : decoder.decode(bytes), size: stat.size }
  }
  if (method === '$listFiles') {
    const path = safePath(args.path)
    const names = await fs.readdir(path)
    if (names.length > 2000) throw new Error('Directory has more than 2000 entries')
    return await Promise.all(names.map(async item => {
      const name = typeof item === 'string' ? item : item.name
      const full = path.replace(/\/$/, '') + '/' + name
      const stat = await fs.stat(full)
      return { name, path: full, directory: stat.isDirectory(), size: stat.size,
        modifiedAt: stat.mtimeMs || 0 }
    }))
  }
  if (method === '$openBinaryRead') {
    const path = safePath(args.path)
    const now = Date.now()
    pruneBinaryReads(now)
    if (binaryReads.size >= MAX_BINARY_READERS) throw new Error('Too many active binary readers')
    const stat = await fs.stat(path)
    if (stat.isDirectory()) throw new Error('Binary preview requires a file')
    if (stat.size > MAX_BINARY_SNAPSHOT_BYTES) throw new Error('Binary snapshot exceeds 16 MiB')
    const frozen = await fs.readFile(path)
    const bytes = typeof frozen === 'string' ? encoder.encode(frozen) : frozen
    if (bytes.byteLength > MAX_BINARY_SNAPSHOT_BYTES) throw new Error('Binary snapshot exceeds 16 MiB')
    const id = binaryHandle()
    const timer = setTimeout(() => discardBinaryRead(id), BINARY_SNAPSHOT_TTL_MS)
    binaryReads.set(id, { bytes, openedAt: now, size: bytes.byteLength, timer })
    return { id, size: bytes.byteLength, mime: mimeFor(path) }
  }
  if (method === '$readBinaryChunk') {
    const id = args.id
    const snapshot = typeof id === 'string' ? binaryReads.get(id) : undefined
    if (!snapshot) throw new Error('Binary snapshot is closed')
    if (Date.now() - snapshot.openedAt > BINARY_SNAPSHOT_TTL_MS) {
      discardBinaryRead(id)
      throw new Error('Binary snapshot expired')
    }
    const offset = args.offset
    if (!Number.isInteger(offset) || offset < 0 || offset > snapshot.size) throw new Error('Invalid binary chunk offset')
    const end = Math.min(offset + BINARY_CHUNK_BYTES, snapshot.size)
    return { id, size: snapshot.size, offset,
      base64: base64OfBytes(snapshot.bytes.subarray(offset, end)) }
  }
  if (method === '$closeBinaryRead') {
    const id = args.id
    const closed = typeof id === 'string' ? discardBinaryRead(id) : false
    return { closed }
  }
  if (method === '$command') {
    if (typeof args.command !== 'string' || args.command.length > 65536) throw new Error('Invalid shell command')
    const require = handle.host.modules.createRequire('/dsh/package.json')
    const { WorkerShell, VfsFileSystem, createWorkerCommands } = require('@phone-dsh/dsh-shell-ext')
    const shell = new WorkerShell({ fs: new VfsFileSystem({ root: '/' }), commands: createWorkerCommands() })
    const signal = AbortSignal.timeout(120_000)
    const result = await shell.run(['bash', '-c', args.command], {
      cwd: safePath(args.cwd || '/dsh/workspace'), signal,
      env: { HOME: '/dsh/home', TMPDIR: '/dsh/tmp', PATH: '/usr/bin:/bin:/dsh/bin' },
    })
    await handle.vfs.flush()
    return { exitCode: result.exitCode, stdout: result.stdout, stderr: result.stderr }
  }
  throw new Error('Unsupported native Worker method ' + method)
}
addEventListener('message', async event => {
  const frame = event.data
  if (frame?.t === 'shell-start' && !shellForwarded) {
    event.stopImmediatePropagation()
    await import('/assets/worker-entry.js')
    shellForwarded = true
    dispatchEvent(new MessageEvent('message', { data: frame }))
    return
  }
  if (frame?.t === 'pdsh-native-init') {
    event.stopImmediatePropagation()
    if (booting || handle) return
    booting = true
    try {
      const { startPhoneWorker } = await import('/assets/worker-entry.js')
      const xl = frame.xl || {}
      handle = await startPhoneWorker({ image: frame.image, overlays: frame.overlays || [],
        channelURL: xl.channelURL, token: xl.token, env: xl.env || {},
        bootId: xl.bootId, sessionEpoch: xl.sessionEpoch,
        fence: xl.fence ? { owner: xl.fence.owner, generation: BigInt(xl.fence.generation), wal_seq: BigInt(xl.fence.wal_seq) } : undefined,
        pagePost: message => postMessage(message),
      })
      for (const message of pending.splice(0)) handle.host.handleMessage(message)
    } catch (error) {
      let message = String(error.message || error)
      const token = frame.xl?.token
      if (token) message = message.split(token).join('[redacted]')
      postMessage({ t: 'pdsh-error', message: message.slice(0, 1500) })
    }
    return
  }
  if (frame?.t === 'pdsh-native-command') {
    event.stopImmediatePropagation()
    if (!handle) { postMessage({ t: 'pdsh-native-result', id: frame.id, ok: false, error: 'Worker is not ready' }); return }
    try {
      const value = await command(frame.method, frame.args || {})
      postMessage({ t: 'pdsh-native-result', id: frame.id, ok: true, value })
    } catch (error) { postMessage({ t: 'pdsh-native-result', id: frame.id, ok: false, error: String(error.message || error).slice(0, 1500) }) }
    return
  }
  if (frame && ['req', 'abort', 'stream-open', 'stream-abort', 'stream-uplink-item', 'stream-uplink-end', 'stream-uplink-error'].includes(frame.t)) {
    event.stopImmediatePropagation()
    if (handle) handle.host.handleMessage(frame)
    else pending.push(frame)
  }
})
