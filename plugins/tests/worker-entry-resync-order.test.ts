// SPDX-License-Identifier: MIT
/**
 * channel.md §10: the Worker sends every mutation it already made on a path
 * before it sends `VFS_CONFLICT` for that path's VFS_RESYNC, so the host sees
 * the covering record first (wal.md §6.2).
 *
 * This drives the real `installSink` publication path and the real
 * `installResyncHandler` send path over the in-memory ordered channel pair. It
 * records the frames at the channel's `send` boundary, not in a hand-written
 * expectation: a mutation completed on the path during the resync handling is
 * published through the sink before the conflict frame leaves.
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  createMemoryChannelPair,
  decodeVfsMutation,
  encodeVfsResync,
  FrameType,
} from '../channel/src/index.ts'
import {
  installResyncHandler,
  installSink,
  type WorkerEntryMutation,
  type WorkerEntryVfs,
} from '../worker-entry/src/entry.ts'
import { eventually } from './test-channel.ts'

const PATH = '/dsh/workspace/conflict.txt'

test('resync: a completed same-path mutation is sent before VFS_CONFLICT', async () => {
  const pair = createMemoryChannelPair({ autoConnect: true })
  // Observe the real channel send calls in order. `send` is synchronous in
  // both the memory transport and the production WebSocket client, which is
  // exactly what makes "sink mutation first, conflict second" true.
  const sent: { type: FrameType; payload: Uint8Array }[] = []
  const originalSend = pair.worker.send.bind(pair.worker)
  pair.worker.send = (type, payload) => {
    sent.push({ type, payload })
    return originalSend(type, payload)
  }

  let listener: ((mutation: WorkerEntryMutation) => void) | undefined
  const finalBytes = new TextEncoder().encode('the completed write')
  const vfs: WorkerEntryVfs = {
    subscribe(next) {
      listener = next
      return () => { listener = undefined }
    },
    flush: async () => {},
    readFileSync() {
      // The runtime had already completed this same-path write when the
      // host's VFS_RESYNC arrived (for example a rename replaced the file).
      // It must reach the ordered channel through the real sink before the
      // handler can answer with VFS_CONFLICT.
      assert.notEqual(listener, undefined)
      listener?.({
        kind: 'write',
        path: PATH,
        bytes: finalBytes,
        mode: 0o644,
        entryChanged: true,
      } as WorkerEntryMutation)
      throw new Error(`${PATH} is no longer a regular file`)
    },
  }

  const disposeSink = installSink(vfs, pair.worker)
  const disposeResync = installResyncHandler(vfs, pair.worker)
  try {
    pair.host.send(FrameType.VFS_RESYNC, encodeVfsResync({ path: PATH }))
    await eventually(
      () => sent.some(frame => frame.type === FrameType.ERROR),
      3_000,
    )

    const mutationIndex = sent.findIndex(frame => frame.type === FrameType.VFS_MUT)
    const conflictIndex = sent.findIndex(frame => frame.type === FrameType.ERROR)
    assert.notEqual(mutationIndex, -1, 'the completed mutation must be on the wire')
    assert.notEqual(conflictIndex, -1, 'the Worker must answer with VFS_CONFLICT')
    assert.ok(
      mutationIndex < conflictIndex,
      `the covering mutation must precede VFS_CONFLICT (frame types: ${sent.map(f => f.type).join(', ')})`,
    )

    const mutation = decodeVfsMutation(sent[mutationIndex]!.payload)
    assert.equal(mutation.path, PATH)
    assert.equal(mutation.mflags & 0x02, 0, 'the cover is a complete write')
    assert.deepEqual([...mutation.data], [...finalBytes])

    const conflict = JSON.parse(new TextDecoder().decode(sent[conflictIndex]!.payload))
    assert.equal(conflict.code, 'VFS_CONFLICT')
  } finally {
    disposeResync()
    disposeSink()
    pair.setDrop(() => true)
    pair.disconnect()
  }
})
