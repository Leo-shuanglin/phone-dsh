// SPDX-License-Identifier: MIT
// Regression: a connection-scoped failure must not poison the client forever.
//
// On device the host restarts the frozen Worker, the fresh Worker's first
// inbound frame can race the host's own HELLO, and the real fix target is that
// one PROTO_MISMATCH must not turn the whole harness into a permanently failed
// state. These tests pin the failure-scope split in `ws-client.ts`:
//
// - connection scope: protocol violations, peer ERRORs other than FENCE_STALE,
//   and RING_TIMEOUT reject the current attempt, but a later connection starts
//   from a fresh decoder, seq space and HELLO exchange and can succeed;
// - terminal: FENCE_STALE and close() stay poisoned for the client lifetime.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canonicalStringify } from './canonical-json.ts'
import { decodeUtf8, encodeUtf8 } from './bytes.ts'
import { FrameDecoder, FrameType, encodeFrame, type Frame } from './frames.ts'
import type { HelloHostPayload } from './payloads.ts'
import {
  decodeVfsMutation, encodeJournal, encodeVfsAck, encodeVfsMutation, VfsOp,
} from './vfs-mutation.ts'
import {
  ChannelClosedError, ChannelPeerError, WebSocketChannelClient,
  type WebSocketChannelOptions, type WebSocketLike,
} from './ws-client.ts'

const BOOT_ID = '00112233445566778899aabbccddeeff'
const HOST_BOOT_ID = 'ffeeddccbbaa99887766554433221100'
const FENCE = { owner: 'phone', generation: 3n, wal_seq: 41n } as const

class MockWebSocket implements WebSocketLike {
  readonly url: string
  readonly sent: Uint8Array[] = []
  readonly closeCalls: { code: number | undefined; reason: string | undefined }[] = []
  readyState = 0
  binaryType = 'arraybuffer'
  private readonly listeners = new Map<string, Set<(event: unknown) => void>>()

  constructor(url: string) {
    this.url = url
  }

  send(data: ArrayBuffer | ArrayBufferView | string): void {
    if (this.readyState !== 1) throw new Error('mock WebSocket: send() before open')
    if (typeof data === 'string') throw new Error('mock WebSocket: the channel sends binary frames only')
    const bytes = data instanceof ArrayBuffer
      ? new Uint8Array(data)
      : new Uint8Array(data.buffer, data.byteOffset, data.byteLength)
    this.sent.push(bytes.slice())
  }

  close(code?: number, reason?: string): void {
    this.closeCalls.push({ code, reason })
    if (this.readyState === 3) return
    this.readyState = 3
    this.emit('close', {})
  }

  addEventListener(type: string, listener: (event: unknown) => void): void {
    const set = this.listeners.get(type) ?? new Set()
    set.add(listener)
    this.listeners.set(type, set)
  }

  open(): void {
    if (this.readyState !== 0) return
    this.readyState = 1
    this.emit('open', {})
  }

  deliver(frame: Frame): void {
    this.emit('message', { data: encodeFrame(frame) })
  }

  deliverText(text: string): void {
    this.emit('message', { data: text })
  }

  deliverBytes(bytes: Uint8Array): void {
    this.emit('message', { data: bytes })
  }

  private emit(type: string, event: unknown): void {
    for (const listener of [...(this.listeners.get(type) ?? [])]) listener(event)
  }
}

/** Host-side seq counter and encoder for one mock connection. */
class MockHost {
  private seq = 0n

  frame(type: FrameType, payload: Uint8Array): Frame {
    this.seq += 1n
    return { type, seq: this.seq, payload }
  }

  hello(overrides: Partial<HelloHostPayload> = {}): Frame {
    const payload: Record<string, unknown> = {
      proto: 1,
      role: 'host',
      boot_id: HOST_BOOT_ID,
      session_epoch: 7,
      fence: { owner: 'phone', generation: 3, wal_seq: 41 },
      caps: ['channel/1', 'wal/1', 'exec/1', 'accelerator:rg'],
      next_mseq: 42,
      acked_mseq: 41,
      durable_mseq: 40,
      ...overrides,
    }
    return this.frame(FrameType.HELLO, encodeUtf8(canonicalStringify(payload)))
  }

  /** The one frame a racing host can send before its HELLO. */
  lifecycle(): Frame {
    return this.frame(FrameType.LIFECYCLE, encodeUtf8(canonicalStringify({
      state: 'run', budget: 0, admission: 'open', reason: 'foreground',
    })))
  }
}

function decodeSent(bytes: Uint8Array): Frame {
  const frames = new FrameDecoder().push(bytes)
  assert.equal(frames.length, 1)
  return frames[0]!
}

function sentFrames(socket: MockWebSocket): Frame[] {
  return socket.sent.map(decodeSent)
}

function errorCodeOf(socket: MockWebSocket): string | undefined {
  const frame = sentFrames(socket).find(candidate => candidate.type === FrameType.ERROR)
  if (frame === undefined) return undefined
  return (JSON.parse(decodeUtf8(frame.payload)) as { code: string }).code
}

async function waitFor(condition: () => boolean, message = 'condition', timeoutMs = 2_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!condition()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${message}`)
    await new Promise<void>((resolve) => setTimeout(resolve, 1))
  }
}

interface Harness {
  readonly client: WebSocketChannelClient
  readonly sockets: MockWebSocket[]
}

function makeClient(overrides: Partial<WebSocketChannelOptions> = {}): Harness {
  const sockets: MockWebSocket[] = []
  const client = new WebSocketChannelClient({
    url: 'ws://127.0.0.1:1234/channel',
    bootId: BOOT_ID,
    sessionEpoch: 7n,
    fence: FENCE,
    lastMseq: 41n,
    backoffMinMs: 1,
    backoffMaxMs: 4,
    createSocket: (url) => {
      const socket = new MockWebSocket(url)
      sockets.push(socket)
      return socket
    },
    ...overrides,
  })
  return { client, sockets }
}

/** Connect one client through a complete, successful HELLO exchange. */
async function connectClient(
  overrides: Partial<WebSocketChannelOptions> = {},
  lastMseq = 41n,
): Promise<Harness & { readonly socket: MockWebSocket; readonly host: MockHost }> {
  const harness = makeClient({ lastMseq, ...overrides })
  const connecting = harness.client.connect()
  await waitFor(() => harness.sockets.length === 1, 'the first socket')
  const socket = harness.sockets[0]!
  socket.open()
  assert.equal(socket.sent.length, 1, 'the worker HELLO is the first frame')
  const host = new MockHost()
  socket.deliver(host.hello({ next_mseq: lastMseq + 1n, acked_mseq: lastMseq, durable_mseq: 0n }))
  await connecting
  return { ...harness, socket, host }
}

function mutation(mseq: bigint, path: string, data: Uint8Array): Uint8Array {
  return encodeVfsMutation({ mseq, op: VfsOp.WRITE, mode: 0o644, mflags: 0, appendFrom: 0n, path, data })
}

function journalPayload(jseq = 1n): Uint8Array {
  return encodeJournal({ jseq, sessionId: 'session.1', record: encodeUtf8('{"event":1}') })
}

test('connect() defaults to two retries with a 100 ms first delay', () => {
  const client = new WebSocketChannelClient({
    url: 'ws://127.0.0.1:1234/channel',
    bootId: BOOT_ID,
    sessionEpoch: 7n,
    fence: FENCE,
  })
  const effective = (client as unknown as {
    options: { connectRetries: number; connectRetryDelayMs: number }
  }).options
  assert.equal(effective.connectRetries, 2)
  assert.equal(effective.connectRetryDelayMs, 100)

  const make = (extra: Partial<WebSocketChannelOptions>): WebSocketChannelClient =>
    new WebSocketChannelClient({
      url: 'ws://127.0.0.1:1234/channel',
      bootId: BOOT_ID,
      sessionEpoch: 7n,
      fence: FENCE,
      ...extra,
    })
  assert.throws(() => make({ connectRetries: -1 }), RangeError)
  assert.throws(() => make({ connectRetries: 1.5 }), RangeError)
  assert.throws(() => make({ connectRetryDelayMs: 0 }), RangeError)
})

test('a first frame that is not HELLO is retried and the fresh connection completes', async () => {
  const { client, sockets } = makeClient({ connectRetryDelayMs: 5 })
  try {
    const connecting = client.connect()
    await waitFor(() => sockets.length === 1, 'the first socket')
    const first = sockets[0]!
    first.open()
    assert.equal(first.sent.length, 1, 'the worker HELLO on the failed attempt')
    const firstHost = new MockHost()
    // The bug from the device log: the fresh Worker's first inbound frame is
    // not the host HELLO (a race after the host restarted the Worker).
    first.deliver(firstHost.lifecycle())
    await waitFor(() => first.closeCalls.length > 0, 'the protocol close')
    assert.equal(errorCodeOf(first), 'PROTO_MISMATCH')

    // The same connect() call must try again on a brand-new connection.
    await waitFor(() => sockets.length === 2, 'the connect retry')
    const second = sockets[1]!
    second.open()
    assert.equal(second.sent.length, 1, 'the retry sends its own HELLO')
    const hello = decodeSent(second.sent[0]!)
    assert.equal(hello.type, FrameType.HELLO)
    assert.equal(hello.seq, 1n, 'the retry restarts the outbound seq space')

    const secondHost = new MockHost()
    second.deliver(secondHost.hello({ next_mseq: 43n, acked_mseq: 42n, durable_mseq: 41n }))
    await connecting

    assert.equal(client.hostHello?.boot_id, HOST_BOOT_ID)
    assert.equal(client.nextMseq(), 43n)
    assert.equal(client.admissionOpen, true)
    // The connection is usable: the next outbound frame is seq 2.
    assert.equal(client.send(FrameType.JOURNAL, journalPayload()), 2n)
  } finally {
    await client.close()
  }
})

test('decoder bytes and caps from the failed attempt do not leak into the retry', async () => {
  const { client, sockets } = makeClient({ connectRetryDelayMs: 5 })
  try {
    const connecting = client.connect()
    await waitFor(() => sockets.length === 1, 'the first socket')
    const first = sockets[0]!
    first.open()
    // Leave a partial frame in the decoder, then fail the connection with a
    // text message. A reused decoder would mis-frame the retry's HELLO.
    const helloBytes = encodeFrame(new MockHost().hello())
    first.deliverBytes(helloBytes.subarray(0, 7))
    first.deliverText('not a frame')
    await waitFor(() => first.closeCalls.length > 0, 'the BAD_PAYLOAD close')
    assert.equal(errorCodeOf(first), 'BAD_PAYLOAD')
    // The first connection never parsed a host HELLO, so there are no caps to
    // carry over; the retry must not see stale ones either.
    assert.equal(client.hostHello === undefined, true, 'no caps from the failed attempt')

    await waitFor(() => sockets.length === 2, 'the connect retry')
    const second = sockets[1]!
    second.open()
    const secondHost = new MockHost()
    second.deliver(secondHost.hello({
      next_mseq: 43n, acked_mseq: 42n, durable_mseq: 41n,
      caps: ['channel/1', 'wal/1', 'exec/1', 'accelerator:jaq'],
    }))
    await connecting
    assert.deepEqual(client.hostHello?.caps, ['channel/1', 'wal/1', 'exec/1', 'accelerator:jaq'])

    // Inbound seq also restarted at 1: LIFECYCLE at seq 2 is accepted without a
    // BAD_SEQ failure on the recovered connection.
    second.deliver(secondHost.lifecycle())
    assert.equal(second.closeCalls.length, 0)
    assert.equal(errorCodeOf(second), undefined)
    assert.deepEqual(sentFrames(second).map(frame => frame.type), [FrameType.HELLO])
  } finally {
    await client.close()
  }
})

test('connect() spends its bounded retries and a later call starts a fresh budget', async () => {
  const { client, sockets } = makeClient({ connectRetries: 2, connectRetryDelayMs: 1 })
  try {
    const connecting = client.connect()
    for (let attempt = 0; attempt < 3; attempt++) {
      await waitFor(() => sockets.length === attempt + 1, `socket ${String(attempt + 1)}`)
      const socket = sockets[attempt]!
      socket.open()
      assert.equal(socket.sent.length, 1)
      socket.deliver(new MockHost().lifecycle())
    }
    await assert.rejects(connecting, (error: unknown) => {
      assert.ok(error instanceof ChannelPeerError)
      assert.equal(error.code, 'PROTO_MISMATCH')
      assert.equal(error.scope, 'connection', 'a protocol violation is not terminal')
      return true
    })
    assert.equal(sockets.length, 3, 'one initial attempt plus two retries')

    // The next openSocket() must be able to try again, and must start from
    // clean seq/HELLO state rather than the failed attempt's.
    const retrying = client.connect()
    await waitFor(() => sockets.length === 4, 'the explicit retry socket')
    const recovered = sockets[3]!
    recovered.open()
    assert.equal(decodeSent(recovered.sent[0]!).seq, 1n)
    recovered.deliver(new MockHost().hello())
    await retrying
    assert.equal(client.hostHello?.role, 'host')
    assert.equal(client.admissionOpen, true)
  } finally {
    await client.close()
  }
})

test('connectRetries: 0 makes the first connection-scoped failure final for that call', async () => {
  const { client, sockets } = makeClient({ connectRetries: 0 })
  try {
    const connecting = client.connect()
    await waitFor(() => sockets.length === 1, 'the first socket')
    const socket = sockets[0]!
    socket.open()
    socket.deliver(new MockHost().lifecycle())
    await assert.rejects(connecting, (error: unknown) =>
      error instanceof ChannelPeerError && error.code === 'PROTO_MISMATCH')
    assert.equal(sockets.length, 1)
    assert.equal(client.admissionOpen, false)
  } finally {
    await client.close()
  }
})

test('FENCE_STALE is terminal: no retry, no reconnect, and the error is sticky', async () => {
  const { client, sockets, socket, host } = await connectClient()
  try {
    socket.deliver(host.frame(FrameType.ERROR, encodeUtf8(canonicalStringify({
      code: 'FENCE_STALE',
      message: 'the worker fence generation is below the host generation',
    }))))
    await waitFor(() => socket.closeCalls.length > 0, 'the peer-error close')
    assert.equal(socket.closeCalls[0]?.code, 1002)
    assert.equal(client.admissionOpen, false)

    // No reconnect timer may have been armed: a fence that is already stale
    // cannot be fixed by reconnecting with the same HELLO.
    await new Promise<void>((resolve) => setTimeout(resolve, 30))
    assert.equal(sockets.length, 1, 'FENCE_STALE must never reconnect')

    assert.throws(
      () => client.send(FrameType.JOURNAL, journalPayload()),
      (error: unknown) => {
        assert.ok(error instanceof ChannelPeerError)
        assert.equal(error.code, 'FENCE_STALE')
        assert.equal(error.scope, 'terminal')
        return true
      },
    )
    await assert.rejects(client.connect(), (error: unknown) => {
      assert.ok(error instanceof ChannelPeerError)
      assert.equal(error.code, 'FENCE_STALE')
      assert.equal(error.scope, 'terminal')
      return true
    })
    await new Promise<void>((resolve) => setTimeout(resolve, 20))
    assert.equal(sockets.length, 1, 'a later connect() must not open a socket either')
    assert.equal(client.admissionOpen, false)
  } finally {
    await client.close()
  }
})

test('a non-fence peer ERROR is connection-scoped: a reconnect resumes the channel', async () => {
  const { client, sockets, socket, host } = await connectClient({}, 0n)
  try {
    client.send(FrameType.VFS_MUT, mutation(1n, '/dsh/workspace/a.bin', encodeUtf8('x')))
    socket.deliver(host.frame(FrameType.ERROR, encodeUtf8(canonicalStringify({
      code: 'BAD_MSEQ',
      message: 'expected mseq 1, got 2',
      ref_seq: 2,
    }))))
    await waitFor(() => socket.closeCalls.length > 0, 'the peer-error close')
    assert.equal(client.admissionOpen, false)

    await waitFor(() => sockets.length === 2, 'the reconnect socket')
    const second = sockets[1]!
    second.open()
    const secondHost = new MockHost()
    second.deliver(secondHost.hello({ next_mseq: 1n, acked_mseq: 0n, durable_mseq: 0n }))
    await waitFor(() => client.hostHello !== undefined, 'the fresh host HELLO')
    assert.equal(client.admissionOpen, true)

    // D2/D3 replay: the unacked mutation keeps its original mseq.
    await waitFor(
      () => sentFrames(second).filter(frame => frame.type === FrameType.VFS_MUT).length === 1,
      'the VFS_MUT replay',
    )
    const replayed = sentFrames(second).find(frame => frame.type === FrameType.VFS_MUT)!
    assert.equal(decodeVfsMutation(replayed.payload).mseq, 1n)

    second.deliver(secondHost.frame(FrameType.VFS_ACK, encodeVfsAck({ level: 1, uptoMseq: 1n })))
    await client.waitAck(1, 1n)
  } finally {
    await client.close()
  }
})

test('RING_TIMEOUT is connection-scoped: admission reopens after the replay', async () => {
  const ringTimeoutMs = 5
  const { client, sockets, socket } = await connectClient({
    maxUnackedVfsBytes: 500, ringTimeoutMs,
  }, 0n)
  try {
    client.send(FrameType.VFS_MUT, mutation(1n, '/dsh/workspace/a', new Uint8Array(300)))
    client.send(FrameType.VFS_MUT, mutation(2n, '/dsh/workspace/b', new Uint8Array(300)))
    await waitFor(() => socket.closeCalls.length > 0, 'the ring-timeout close')
    assert.equal(errorCodeOf(socket), 'RING_TIMEOUT')
    assert.equal(client.admissionOpen, false)

    await waitFor(() => sockets.length === 2, 'the reconnect socket')
    const second = sockets[1]!
    second.open()
    const secondHost = new MockHost()
    second.deliver(secondHost.hello({ next_mseq: 1n, acked_mseq: 0n, durable_mseq: 0n }))
    // The replay starts filling the window again; ack it promptly so the test
    // exercises recovery rather than a second ring timeout.
    assert.deepEqual(
      sentFrames(second)
        .filter(frame => frame.type === FrameType.VFS_MUT)
        .map(frame => decodeVfsMutation(frame.payload).mseq),
      [1n],
    )
    second.deliver(secondHost.frame(FrameType.VFS_ACK, encodeVfsAck({ level: 1, uptoMseq: 1n })))
    assert.deepEqual(
      sentFrames(second)
        .filter(frame => frame.type === FrameType.VFS_MUT)
        .map(frame => decodeVfsMutation(frame.payload).mseq),
      [1n, 2n],
    )
    const secondAck = client.waitAck(1, 2n)
    second.deliver(secondHost.frame(FrameType.VFS_ACK, encodeVfsAck({ level: 1, uptoMseq: 2n })))
    await secondAck

    assert.equal(client.hostHello !== undefined, true)
    assert.equal(client.admissionOpen, true, 'the ring timeout no longer poisons admission')
    // The recovered connection accepts new work.
    assert.equal(client.send(FrameType.JOURNAL, journalPayload()), 4n)
  } finally {
    await client.close()
  }
})

test('close() is terminal: connect() and send() stay rejected and no attempt is made', async () => {
  const { client, sockets } = await connectClient()
  await client.close()
  await assert.rejects(client.connect(), ChannelClosedError)
  assert.throws(() => client.send(FrameType.JOURNAL, journalPayload()), ChannelClosedError)
  await new Promise<void>((resolve) => setTimeout(resolve, 20))
  assert.equal(sockets.length, 1, 'a closed client never opens another socket')
  assert.equal(client.admissionOpen, false)
})
