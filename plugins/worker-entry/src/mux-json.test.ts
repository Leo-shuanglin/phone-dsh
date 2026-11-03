// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { canonicalStringify } from '../../channel/src/canonical-json.ts'
import { stringifyMuxJson } from './mux-json.ts'
import { encodeMuxFrameLine } from './mux-bridge.ts'

test('mux preserves finite JSON numbers in nested events, snapshots and errors', () => {
  const value = { temperature: 0.7, delayMs: 481.7056, providerRetryAfterMs: 1004.9999999999999,
    tool: { coords: [12.25, -3.75], ratio: 0.5 }, tiny: 1e-100, large: 1e100, text: '中文\n/x' }
  for (const frame of [
    { type: 'item' as const, streamId: 'follow', value: { event: { type: 'tool/result', data: value } } },
    { type: 'item' as const, streamId: 'follow', value: { records: [value], projections: value } },
    { type: 'error' as const, streamId: 'follow', error: { code: 'test', message: 'error', details: value } },
  ]) {
    assert.deepEqual(JSON.parse(new TextDecoder().decode(encodeMuxFrameLine(frame))), frame)
  }
  assert.equal(value.delayMs, 481.7056)
  assert.throws(() => canonicalStringify(value), /integers only/u, 'control JSON remains strict')
})

test('mux rejects non-finite and non-JSON values rather than corrupting data', () => {
  for (const value of [NaN, Infinity, -Infinity, undefined, 1n, () => {}]) {
    assert.throws(() => stringifyMuxJson({ nested: [value] }), TypeError)
  }
  const cyclic: Record<string, unknown> = {}
  cyclic['self'] = cyclic
  assert.throws(() => stringifyMuxJson(cyclic), /circular/u)
})

test('mux uses JSON negative-zero semantics and permits shared noncyclic objects', () => {
  const shared = { fraction: 0.25 }
  assert.deepEqual(JSON.parse(stringifyMuxJson([shared, shared, -0])), [shared, shared, 0])
})
