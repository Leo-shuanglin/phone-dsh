// SPDX-License-Identifier: MIT
// Exercises the real helper source through esbuild. The RCP SDK is not mocked:
// these are callback-order invariants, not a claim about measured RCP timing.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
const source = readFileSync(new URL('../entry/src/main/ets/host/HostProxyStreamState.ets', import.meta.url), 'utf8');
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const { ProxyStreamState, proxyRejectMessage } = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

// No status means no head, even after headers or data-end: never guess 200.
{
  const state = new ProxyStreamState();
  assert.equal(state.headReady(), false);
  state.receiveHeaders();
  assert.equal(state.headReady(), false);
  state.noteDataEnd();
  assert.equal(state.readyToFinish(false), false);
  assert.equal(state.readyToFinish(true), true);
  assert.equal(state.success(), false);
}

// Status before headers: head waits for headers, then uses the actual status.
{
  const state = new ProxyStreamState();
  assert.equal(state.receiveStatus(200), true);
  assert.equal(state.headReady(), false);
  state.receiveHeaders();
  assert.equal(state.headReady(), true);
  state.markHeadSent();
  assert.equal(state.status, 200);
  assert.equal(state.headReady(), false);
}

// Headers before status: status unlocks the head.
{
  const state = new ProxyStreamState();
  state.receiveHeaders();
  assert.equal(state.headReady(), false);
  state.receiveStatus(201);
  assert.equal(state.headReady(), true);
  assert.equal(state.status, 201);
}

// A 1xx response is not final.
{
  const state = new ProxyStreamState();
  assert.equal(state.receiveStatus(103), false);
  assert.equal(state.statusKnown, false);
  state.receiveStatus(200);
  assert.equal(state.statusKnown, true);
  assert.equal(state.headReady(), false);
  state.receiveHeaders();
  assert.equal(state.headReady(), true);
}

// Data before status: bounded pending, no head, then stream once status lands.
{
  const state = new ProxyStreamState();
  state.noteData();
  assert.equal(state.queuePending(600000, 1048576), false);
  assert.equal(state.headReady(), false);
  assert.equal(state.queuePending(600000, 1048576), true);
  assert.equal(state.receiveStatus(200), true);
  assert.equal(state.headReady(), true);
  state.markHeadSent();
  assert.equal(state.pendingBytes, 0);
}

// Non-2xx is rejected, never streamed, and maps to the existing messages.
{
  const state = new ProxyStreamState();
  state.receiveStatus(404);
  assert.equal(state.rejected, true);
  assert.equal(state.success(), false);
  assert.equal(state.headReady(), false);
  assert.equal(state.queuePending(10, 1048576), false);
  assert.equal(proxyRejectMessage(state.status), 'upstream_status');
  const redirect = new ProxyStreamState();
  redirect.receiveStatus(302);
  assert.equal(proxyRejectMessage(redirect.status), 'upstream_redirect_refused');
}

// data-end can finish once status is known; fetch settlement finishes alone.
{
  const state = new ProxyStreamState();
  state.receiveStatus(200);
  state.noteDataEnd();
  assert.equal(state.readyToFinish(false), true);
  assert.equal(state.finish(), true);
  assert.equal(state.finish(), false);
  assert.equal(state.readyToFinish(true), false);
  assert.equal(state.receiveStatus(500), false);
}
