// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

function engineHarness() {
  const source = readFileSync(new URL('../web/engine.mjs', import.meta.url), 'utf8');
  const boot = "\ntry {\n  const response = await fetch('/boot-config'";
  assert.equal(source.split(boot).length, 2, 'one actual browser bootstrap boundary');
  const beforeBoot = source.slice(0, source.indexOf(boot));
  const importLine = "import { WorkerTunnel } from './tunnel-client.mjs'\n";
  assert.ok(beforeBoot.includes(importLine));
  const context = vm.createContext({ setTimeout, clearTimeout, AbortSignal,
    crypto: { randomUUID: () => 'routing-test-id' }, nativeCalls: [], rpcCalls: [] });
  vm.runInContext(beforeBoot.replace(importLine, '') + `
    worker = { postMessage(message) {
      nativeCalls.push(message);
      const pending = calls.get(message.id);
      clearTimeout(pending.timer);
      calls.delete(message.id);
      pending.resolve({ native: true });
    }};
    tunnel = { async fetch(path, options) {
      const frame = JSON.parse(options.body);
      rpcCalls.push({ path, frame });
      return { ok: true, async json() {
        return { type: 'server-response', rpcId: frame.rpcId,
          result: { ok: true, value: { accepted: true } } };
      }};
    }};
    readyResolve();
    globalThis.dispatch = call;
  `, context);
  return context;
}

test('the actual page dispatcher routes event answers to DSH RPC', async () => {
  const h = engineHarness();
  const args = { clientId: 'client-1', eventId: 'event-1',
    outcome: { kind: 'result', value: { answers: [{ id: 'q1', selected: ['A'] }] } } };
  await h.dispatch('$events/result', args);
  assert.equal(h.nativeCalls.length, 0);
  assert.equal(h.rpcCalls[0].path, '/api/$events/result');
  assert.deepEqual(JSON.parse(JSON.stringify(h.rpcCalls[0].frame.payload.args)), args);
});

test('all real private Worker commands keep the local paired route', async () => {
  const worker = readFileSync(new URL('../web/product-worker.mjs', import.meta.url), 'utf8');
  const names = [...worker.matchAll(/if \(method === '(\$[^']+)'\)/g)].map(m => m[1]);
  assert.equal(names.length, 8);
  const h = engineHarness();
  for (const method of names) await h.dispatch(method, { path: '/dsh/workspace/test.txt' });
  assert.equal(h.rpcCalls.length, 0);
  assert.deepEqual(Array.from(h.nativeCalls, f => f.method), names);
});

test('ordinary RPC keeps its envelope and invalid method paths are refused', async () => {
  const h = engineHarness();
  await h.dispatch('session/projections', { request: { sessionId: 's1' } });
  assert.equal(h.rpcCalls[0].path, '/api/session/projections');
  await assert.rejects(h.dispatch('../unsafe', {}), /Invalid DSH method/);
  assert.equal(h.rpcCalls.length, 1);
});
