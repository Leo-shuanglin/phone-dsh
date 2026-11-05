#!/usr/bin/env node
// SPDX-License-Identifier: MIT
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { randomBytes, createHash } from 'node:crypto'
import { readFileSync, writeFileSync, appendFileSync, mkdirSync, openSync, closeSync, fsyncSync, existsSync, realpathSync } from 'node:fs'
import { dirname, resolve, extname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { execFileSync } from 'node:child_process'
import { FrameDecoder, FrameType, encodeFrame } from '../../../plugins/channel/src/frames.ts'
import { makeHostHello } from '../../../plugins/channel/src/host-hello.ts'
import { canonicalStringify } from '../../../plugins/channel/src/canonical-json.ts'
import { decodeVfsMutation, decodeVfsBarrier, encodeVfsAck, decodeJournal, encodeJournalAck } from '../../../plugins/channel/src/vfs-mutation.ts'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const require = createRequire(resolve(root, 'plugins/package.json'))
const { WebSocketServer } = require('ws')
const { values } = parseArgs({ options: {
  port: { type: 'string', default: '18765' },
  evidence: { type: 'string', default: resolve(root, '.audit/harmony-probe/latest') },
} })
const evidence = resolve(values.evidence)
mkdirSync(evidence, { recursive: true })
const bundle = resolve(root, 'examples/probe-app/Bundle/worker')
const web = resolve(root, 'examples/harmony-probe/web')
if (!existsSync(resolve(bundle, 'worker-entry.js'))) throw new Error('production Worker bundle is missing')
const bundleRecord = JSON.parse(readFileSync(resolve(bundle, 'probe-bundle.json'), 'utf8'))
const checkout = process.env.PDSH_DSH_CHECKOUT || bundleRecord.image?.checkout
if (!checkout) throw new Error('Set PDSH_DSH_CHECKOUT to the pinned DSH checkout for the production tunnel client')
const tunnelSource = resolve(checkout, 'packages/experimental/webworker-runtime/src/client/client.ts')
const sourceCommit = execFileSync('git', ['-C', checkout, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
if (sourceCommit !== bundleRecord.image?.commit) throw new Error('DSH source commit does not match the production Worker image')
const generated = resolve(evidence, 'generated')
mkdirSync(generated, { recursive: true })
const tunnelClient = resolve(generated, 'tunnel-client.mjs')
require('esbuild').buildSync({ entryPoints: [tunnelSource], outfile: tunnelClient, bundle: true, format: 'esm', platform: 'browser', logLevel: 'silent' })
writeFileSync(resolve(generated, 'source.json'), serializableSource())
function serializableSource() {
  return JSON.stringify({ source: tunnelSource, sha256: createHash('sha256').update(readFileSync(tunnelSource)).digest('hex'), sourceCommit }, null, 2) + '\n'
}
const runId = randomBytes(8).toString('hex')
const bootId = randomBytes(16).toString('hex')
const token = randomBytes(32).toString('hex')
const expected = `PDSH_HARMONY_${runId}\n`
const connections = new Set()
let result = { status: 'not-run', runId, environment: 'unverified' }
const serializable = value => JSON.stringify(value, (_, item) => typeof item === 'bigint' ? item.toString() : item)
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.wasm': 'application/wasm', '.gz': 'application/gzip' }
function json(response, status, value) {
  response.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  response.end(serializable(value))
}
function wal(record) {
  const descriptor = openSync(resolve(evidence, 'host-wal.ndjson'), 'a', 0o600)
  try {
    appendFileSync(descriptor, serializable(record) + '\n')
    fsyncSync(descriptor)
  } finally { closeSync(descriptor) }
}
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1')
    if (url.pathname === '/favicon.ico') {
      response.writeHead(204)
      response.end()
      return
    }
    if (url.pathname === '/probe/tunnel-client.mjs' && request.method === 'GET') return serveFile(tunnelClient, generated, response)
    if (url.pathname === '/config' && request.method === 'GET') return json(response, 200, { runId, bootId, token })
    if (url.pathname === '/report' && request.method === 'GET') return json(response, 200, result)
    if (url.pathname === '/report' && request.method === 'POST') {
      if (request.headers.authorization !== `Bearer ${token}`) return json(response, 403, { error: 'unauthorized' })
      let size = 0
      const chunks = []
      for await (const chunk of request) {
        size += chunk.length
        if (size > 65536) throw new Error('report exceeds 64 KiB')
        chunks.push(chunk)
      }
      const page = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      const peer = [...connections].find(connection => connection.hello && connection.marker === expected)
      const stages = ['webassembly', 'worker-ready', 'hello', 'shell', 'jq-wasi', 'task', 'boot-api']
      const passed = page.runId === runId && page.result?.runId === runId
        && page.result.exitCode === 0 && page.result.stdout === expected + '42\n'
        && page.result.readback === expected && peer !== undefined
        && stages.every(stage => page.stages?.some(item => item.stage === stage && item.status === 'pass'))
      result = {
        status: passed ? 'pass' : 'fail', runId,
        environment: /ArkWeb|OpenHarmony|HarmonyOS/i.test(page.platform) ? 'arkweb-reported-by-user-agent' : 'desktop-control-only',
        platform: page.platform, hello: peer?.hello ?? false,
        walMarker: peer?.marker === expected, walLevel: 1,
        host: 'Mac diagnostic host; HarmonyOS native storage and JNI/FFI are untested',
        bundleSha256: createHash('sha256').update(readFileSync(resolve(bundle, 'worker-entry.js'))).digest('hex'),
        page,
      }
      writeFileSync(resolve(evidence, 'result.json'), serializable(result) + '\n')
      console.log(serializable({ event: 'probe-result', status: result.status, environment: result.environment, runId }))
      const { page: details, ...receipt } = result
      return json(response, passed ? 200 : 422, receipt)
    }
    if (request.method !== 'GET') return json(response, 405, { error: 'method not allowed' })
    const path = decodeURIComponent(url.pathname)
    const relative = path.replace(/^\/(probe|bundle)\//, '')
    const base = path.startsWith('/bundle/') ? bundle : web
    const target = resolve(base, path === '/' ? 'index.html' : relative)
    if (!target.startsWith(base + sep) || !existsSync(target)) {
      if (path.startsWith('/probe/') && relative !== '' && !relative.includes('..')) {
        const asset = resolve(bundle, relative)
        if (asset.startsWith(bundle + sep) && existsSync(asset)) return serveFile(asset, bundle, response)
      }
      console.warn(serializable({ event: 'asset-not-found', path }))
      return json(response, 404, { error: 'not found' })
    }
    return serveFile(target, base, response)
  } catch (error) { json(response, 500, { error: String(error.message) }) }
})
function serveFile(path, base, response) {
  const real = realpathSync(path)
  if (!real.startsWith(realpathSync(base) + sep)) return json(response, 403, { error: 'outside asset directory' })
  const content = readFileSync(real)
  response.writeHead(200, { 'content-type': mime[extname(real)] || 'application/octet-stream', 'content-length': content.length, 'cache-control': 'no-store' })
  response.end(content)
}
const sockets = new WebSocketServer({ noServer: true, maxPayload: 17 * 1024 * 1024 })
server.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url, 'http://127.0.0.1')
  if (url.pathname !== '/channel' || url.searchParams.get('token') !== token) return socket.destroy()
  sockets.handleUpgrade(request, socket, head, ws => sockets.emit('connection', ws))
})
sockets.on('connection', socket => {
  const peer = { hello: false, marker: undefined }
  connections.add(peer)
  socket.on('close', () => connections.delete(peer))
  const decoder = new FrameDecoder()
  let seq = 0n
  let inbound = 0n
  let acked = 0n
  const send = (type, payload) => socket.send(encodeFrame({ type, seq: ++seq, payload }))
  socket.on('message', data => {
    try {
      for (const frame of decoder.push(new Uint8Array(data))) {
        if (frame.seq !== ++inbound) throw new Error('out-of-order frame')
        if (!peer.hello) {
          if (frame.type !== FrameType.HELLO) throw new Error('first frame is not HELLO')
          const hello = JSON.parse(new TextDecoder().decode(frame.payload))
          if (hello.role !== 'worker' || hello.proto !== 1 || hello.boot_id !== bootId) throw new Error('Worker HELLO mismatch')
          wal({ type: 'worker-hello', hello })
          peer.hello = true
          send(FrameType.HELLO, new TextEncoder().encode(canonicalStringify(makeHostHello({ bootId }))))
          console.log(serializable({ event: 'worker-hello', runId }))
        } else if (frame.type === FrameType.VFS_MUT) {
          const mutation = decodeVfsMutation(frame.payload)
          if (mutation.mseq !== acked + 1n) throw new Error('non-contiguous mutation sequence')
          wal({ type: 'vfs', ...mutation, data: Buffer.from(mutation.data).toString('base64') })
          acked = mutation.mseq
          if (mutation.path.endsWith(`phonedsh-harmony-${runId}.txt`)) peer.marker = new TextDecoder().decode(mutation.data)
          send(FrameType.VFS_ACK, encodeVfsAck({ level: 1, uptoMseq: acked }))
        } else if (frame.type === FrameType.VFS_BARRIER) {
          const barrier = decodeVfsBarrier(frame.payload)
          if (barrier.wantLevel !== 1 || barrier.uptoMseq > acked) throw new Error('unsupported durability barrier')
          send(FrameType.VFS_ACK, encodeVfsAck({ level: 1, uptoMseq: acked }))
        } else if (frame.type === FrameType.JOURNAL) {
          const journal = decodeJournal(frame.payload)
          wal({ type: 'journal', ...journal, record: Buffer.from(journal.record).toString('base64') })
          send(FrameType.JOURNAL_ACK, encodeJournalAck({ level: 1, sessionId: journal.sessionId, uptoJseq: journal.jseq }))
        } else if (frame.type === FrameType.CONSOLE) {
          appendFileSync(resolve(evidence, 'worker-console.ndjson'), new TextDecoder().decode(frame.payload) + '\n')
        } else if (frame.type === FrameType.ERROR) {
          throw new Error('Worker protocol error: ' + new TextDecoder().decode(frame.payload))
        } else { throw new Error('unexpected frame ' + frame.type) }
      }
    } catch (error) {
      wal({ type: 'host-error', error: String(error.message) })
      console.error(String(error.message))
      socket.close(1011, 'probe host failure')
    }
  })
})
server.listen(Number(values.port), '127.0.0.1', () => {
  const port = server.address().port
  const url = `http://127.0.0.1:${port}/probe/index.html?autorun=1`
  writeFileSync(resolve(evidence, 'host.json'), serializable({ url, runId, pid: process.pid }) + '\n')
  console.log(serializable({ event: 'listening', url, evidence, runId }))
})
process.on('SIGTERM', () => { sockets.close(); server.close(); process.exit(0) })
