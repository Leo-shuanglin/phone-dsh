// SPDX-License-Identifier: MIT
// Focused queue model tests: the real parser, the exact updateQueue encoder and
// the local-echo/drift reconciliation rules. No mocks of the parser itself.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const directory = resolve(dirname(fileURLToPath(import.meta.url)), '../entry/src/main/ets/model');
const source = readFileSync(resolve(directory, 'QueueModel.ets'), 'utf8');
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const {
  QueueItemAction,
  QueueItemState,
  QueueOrigin,
  acknowledgeLocalQueuePrompt,
  admittedUserPromptRequestId,
  containsPromptRequestId,
  encodeQueueUpdate,
  failLocalQueuePrompt,
  localQueueEcho,
  mergeDurableQueue,
  parseQueueProjection,
  queueActionConfirmed,
  retireLocalQueueByDurable,
  retireLocalQueuePrompt,
  retireOwnedQueueRequest,
} = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

const userSource = (rpcId) => ({ kind: 'user', rpcId });

function projection(rows, extra = {}) {
  return {
    kind: 'sequenced',
    values: { inbox: { 'next-turn': rows } },
    ...extra,
  };
}

function plain(item) {
  return {
    id: item.id,
    origin: item.origin,
    workerSessionId: item.workerSessionId,
    promptRequestId: item.promptRequestId,
    text: item.text,
    lane: item.lane,
    state: item.state,
    canEdit: item.canEdit,
    canRemove: item.canRemove,
    canSteer: item.canSteer,
    busy: item.busy,
    action: item.action,
    failure: item.failure,
  };
}

test('parses only values.inbox[next-turn] as sequenced durable next-turn rows', () => {
  const parsed = parseQueueProjection('worker-1', projection([
    { id: 'q1', content: [{ type: 'text', text: 'first' }, { type: 'text', text: 'second' }], source: userSource('prompt-1') },
    { id: 'q2', content: [{ type: 'text', text: 'preview' }, { type: 'image', path: '/x.png' }], source: { kind: 'assistant', rpcId: 'not-user' } },
    { id: 'q3', content: [] },
  ]));
  assert.equal(parsed.failure, '');
  assert.equal(parsed.items.length, 3);
  assert.deepEqual(plain(parsed.items[0]), {
    id: 'q1', origin: 'durable', workerSessionId: 'worker-1', promptRequestId: 'prompt-1',
    text: 'first\nsecond', lane: 'next-turn', state: 'ready',
    canEdit: true, canRemove: true, canSteer: false, busy: false, action: '', failure: '',
  });
  // Mixed non-text content keeps the text preview but is not editable; a
  // non-user source never supplies a prompt correlation id.
  assert.equal(parsed.items[1].text, 'preview');
  assert.equal(parsed.items[1].canEdit, false);
  assert.equal(parsed.items[1].canRemove, true);
  assert.equal(parsed.items[1].promptRequestId, '');
  assert.equal(parsed.items[2].text, '');
  assert.equal(parsed.items[2].canEdit, false);
});

test('reads the same sequenced inbox from an ok/value envelope and inbox kind', () => {
  const wrapped = {
    ok: true,
    value: {
      values: {
        inbox: {
          kind: 'sequenced',
          'next-turn': [{ id: 'q1', content: [{ type: 'text', text: 'wrapped' }], source: userSource('p1') }],
        },
      },
    },
  };
  const parsed = parseQueueProjection('worker-1', wrapped);
  assert.equal(parsed.failure, '');
  assert.equal(parsed.items.length, 1);
  assert.equal(parsed.items[0].id, 'q1');
  assert.equal(parsed.items[0].text, 'wrapped');
  assert.equal(parsed.items[0].promptRequestId, 'p1');
});

test('an empty next-turn array is a known empty queue, distinct from Unknown', () => {
  const parsed = parseQueueProjection('worker-1', projection([]));
  assert.equal(parsed.failure, '');
  assert.deepEqual(parsed.items, []);
});

test('missing/invalid/migration-required projection data stays Unknown', () => {
  const missingValues = parseQueueProjection('worker-1', { kind: 'sequenced', inbox: {} });
  assert.equal(missingValues.items, null);
  assert.match(missingValues.failure, /values/);

  const missingInbox = parseQueueProjection('worker-1', { kind: 'sequenced', values: {} });
  assert.equal(missingInbox.items, null);
  assert.match(missingInbox.failure, /inbox/);

  const camelCase = parseQueueProjection('worker-1', {
    kind: 'sequenced', values: { inbox: { nextTurn: [] } },
  });
  assert.equal(camelCase.items, null);
  assert.match(camelCase.failure, /next-turn/);

  const badKind = parseQueueProjection('worker-1', {
    kind: 'snapshot', values: { inbox: { 'next-turn': [] } },
  });
  assert.equal(badKind.items, null);
  assert.match(badKind.failure, /sequenced/);

  const migration = parseQueueProjection('worker-1', projection([], { 'migration-required': true }));
  assert.equal(migration.items, null);
  assert.match(migration.failure, /migration-required/);

  const malformedLane = parseQueueProjection('worker-1', {
    kind: 'sequenced', values: { inbox: { 'next-turn': {} } },
  });
  assert.equal(malformedLane.items, null);

  const missingId = parseQueueProjection('worker-1', projection([{ content: [{ type: 'text', text: 'x' }] }]));
  assert.equal(missingId.items, null);
  assert.match(missingId.failure, /无效/);

  const duplicate = parseQueueProjection('worker-1', projection([
    { id: 'same', content: [] }, { id: 'same', content: [] },
  ]));
  assert.equal(duplicate.items, null);
  assert.match(duplicate.failure, /重复/);
});

test('encodes the exact existing session/updateQueue request shapes', () => {
  const edit = encodeQueueUpdate('worker-1', 'q1', QueueItemAction.Edit, 'new text');
  assert.deepEqual(edit, {
    request: {
      sessionId: 'worker-1',
      itemId: 'q1',
      action: { kind: 'edit', content: [{ type: 'text', text: 'new text' }] },
    },
  });
  assert.deepEqual(encodeQueueUpdate('worker-1', 'q1', QueueItemAction.Remove), {
    request: { sessionId: 'worker-1', itemId: 'q1', action: { kind: 'remove' } },
  });
  assert.deepEqual(encodeQueueUpdate('worker-1', 'q1', QueueItemAction.Steer), {
    request: { sessionId: 'worker-1', itemId: 'q1', action: { kind: 'steer' } },
  });
  // Blank edit and empty durable identity are refused before the wire.
  assert.equal(encodeQueueUpdate('worker-1', 'q1', QueueItemAction.Edit, '   '), null);
  assert.equal(encodeQueueUpdate('', 'q1', QueueItemAction.Remove), null);
  assert.equal(encodeQueueUpdate('worker-1', '', QueueItemAction.Remove), null);
});

test('readback confirms edit content, remove absence and a real steer leaving next-turn', () => {
  const items = parseQueueProjection('worker-1', projection([
    { id: 'q1', content: [{ type: 'text', text: 'old' }], source: userSource('p1') },
  ])).items;
  assert.equal(queueActionConfirmed(QueueItemAction.Edit, 'q1', 'next-turn', 'old', items), true);
  assert.equal(queueActionConfirmed(QueueItemAction.Edit, 'q1', 'next-turn', 'new', items), false);
  assert.equal(queueActionConfirmed(QueueItemAction.Remove, 'q1', 'next-turn', '', items), false);
  assert.equal(queueActionConfirmed(QueueItemAction.Remove, 'q1', 'next-turn', '', []), true);
  assert.equal(queueActionConfirmed(QueueItemAction.Steer, 'q1', 'next-turn', '', items), false);
  assert.equal(queueActionConfirmed(QueueItemAction.Steer, 'q1', 'next-turn', '', []), true);
});

test('local echo is tied to the real prompt request id and never becomes text matching', () => {
  const echo = localQueueEcho('worker-1', 'prompt-real', 'same text', 'next-turn');
  assert.equal(echo.id, 'local:prompt-real');
  assert.equal(echo.origin, QueueOrigin.Local);
  assert.equal(echo.promptRequestId, 'prompt-real');
  assert.equal(echo.state, QueueItemState.Pending);
  assert.equal(echo.busy, true);
  assert.equal(echo.canEdit, false);
  assert.equal(echo.canRemove, false);
  assert.equal(echo.canSteer, false);

  const durable = parseQueueProjection('worker-1', projection([
    { id: 'q1', content: [{ type: 'text', text: 'same text' }], source: userSource('prompt-real') },
  ])).items;
  assert.deepEqual(retireLocalQueueByDurable([echo], durable), []);
  // Same text with a different rpcId cannot retire the echo.
  const other = parseQueueProjection('worker-1', projection([
    { id: 'q2', content: [{ type: 'text', text: 'same text' }], source: userSource('prompt-other') },
  ])).items;
  assert.equal(retireLocalQueueByDurable([echo], other).length, 1);
  // A failed read is not evidence of acceptance.
  assert.equal(retireLocalQueueByDurable([echo], null).length, 1);
  // The admitted user/message event uses the same actual rpcId.
  assert.equal(admittedUserPromptRequestId({
    data: { message: { source: userSource('prompt-real') } },
  }), 'prompt-real');
  assert.equal(admittedUserPromptRequestId({ data: { message: { source: { kind: 'assistant', rpcId: 'x' } } } }), '');
  assert.deepEqual(retireLocalQueuePrompt([echo], 'prompt-real'), []);
});

test('a failed local echo is actionable and keeps its actual request identity', () => {
  const echo = acknowledgeLocalQueuePrompt(
    [localQueueEcho('worker-1', 'prompt-real', 'typed', 'next-turn')], 'prompt-real')[0];
  assert.equal(echo.busy, false);
  assert.equal(echo.state, QueueItemState.Pending);
  const failed = failLocalQueuePrompt([echo], 'prompt-real', 'lost response')[0];
  assert.equal(failed.busy, false);
  assert.equal(failed.state, QueueItemState.Failed);
  assert.equal(failed.failure, 'lost response');
  assert.equal(failed.promptRequestId, 'prompt-real');
});

test('removing an owned pending request decrements only one correlation id once', () => {
  const first = retireOwnedQueueRequest(['p1', 'p2', 'p1'], 'p1');
  assert.equal(first.removed, true);
  assert.deepEqual(first.remaining, ['p2', 'p1']);
  const again = retireOwnedQueueRequest(first.remaining, 'p1');
  assert.equal(again.removed, true);
  assert.deepEqual(again.remaining, ['p2']);
  const absent = retireOwnedQueueRequest(again.remaining, 'p1');
  assert.equal(absent.removed, false);
  assert.deepEqual(absent.remaining, ['p2']);
  const steer = retireOwnedQueueRequest(['p2'], '');
  assert.equal(steer.removed, false);
  assert.deepEqual(steer.remaining, ['p2']);
});

test('a projection merge preserves an in-flight durable action marker', () => {
  const current = parseQueueProjection('worker-1', projection([
    { id: 'q1', content: [{ type: 'text', text: 'running edit' }], source: userSource('p1') },
  ])).items;
  current[0].busy = true;
  current[0].action = QueueItemAction.Edit;
  current[0].failure = '';
  const measured = parseQueueProjection('worker-1', projection([
    { id: 'q1', content: [{ type: 'text', text: 'measured text' }], source: userSource('p1') },
    { id: 'q2', content: [{ type: 'text', text: 'new row' }], source: userSource('p2') },
  ])).items;
  const merged = mergeDurableQueue(current, measured);
  assert.equal(merged[0].text, 'measured text');
  assert.equal(merged[0].busy, true);
  assert.equal(merged[0].action, 'edit');
  assert.equal(merged[1].id, 'q2');
  assert.equal(mergeDurableQueue(current, null), null);
  assert.equal(containsPromptRequestId(merged, 'p2'), true);
  assert.equal(containsPromptRequestId(null, 'p2'), false);
});
