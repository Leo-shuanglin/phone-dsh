// SPDX-License-Identifier: MIT
// Focused tests for the pure Worker `session/follow` projection.
//
// Fixtures use the exact envelopes measured from the Android host and the
// pinned Worker source:
//   * opening snapshot: `{ type:'snapshot', header, cursor, records:[
//       { type:'event', event:{ type, seq, time, data } } ] }`
//   * live frame: `{ type:'event', event:{ ... } }`
//   * surface payloads from `packages/core/session/src/types.ts`
//     (`user/message` = UserMessage, `assistant/message` = { turn, step, message },
//     `tool/call` = { turn, step, callId, name, arguments },
//     `tool/result` = { turn, step, message, isError?, error? }).
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import test from 'node:test';

const ROOT = new URL('..', import.meta.url).pathname;
const ENTRY = join(ROOT, 'entry/src/main/ets/host/TranscriptProjection.ets');

// The isolated benchmark workspace has no `plugins/` checkout; the canonical
// phone tree does. Keep the lookup explicit so a missing toolchain fails loudly.
const ESBUILD_REQUIRES = [join(ROOT, '../plugins/package.json')];

function loadEsbuild() {
  for (const marker of ESBUILD_REQUIRES) {
    try {
      return createRequire(marker)('esbuild');
    } catch (error) {
      // next candidate
    }
  }
  throw new Error('esbuild was not found; set one of the ESBUILD_REQUIRES markers');
}

async function loadProjection() {
  const esbuild = loadEsbuild();
  const built = esbuild.buildSync({
    entryPoints: [ENTRY],
    absWorkingDir: ROOT,
    bundle: true,
    write: false,
    format: 'esm',
    platform: 'neutral',
    target: 'es2022',
    loader: { '.ets': 'ts' },
    resolveExtensions: ['.ets', '.ts', '.js'],
  });
  const code = built.outputFiles[0].text;
  const url = 'data:text/javascript;base64,' + Buffer.from(code).toString('base64');
  return await import(url);
}

const projection = await loadProjection();
const {
  mergeTranscriptRows,
  projectEvent,
  projectFollowSnapshot,
  sanitizeWorkerDetail,
  turnEndDetailOf,
  turnEndReasonOf,
} = projection;

// RowKind values from model/Contracts.ets; the bundle is the projection entry,
// so the enum is not a public export of the module under test.
const K = { User: 'user', Assistant: 'assistant', Thinking: 'thinking', Tool: 'tool', Error: 'error', System: 'system' };

function event(type, seq, data, time = 1000 + seq) {
  return { type, seq, time, data };
}

function record(value) {
  return { type: 'event', event: value };
}

function followSnapshot(records, cursor = 10) {
  return {
    type: 'snapshot',
    header: { version: 3, id: 'sess_abc', createdAt: 900, isSeeded: false },
    cursor,
    records,
    hasMore: false,
    projections: {
      asOfSeq: cursor,
      values: {
        title: 'CSV 检查',
        modelSelection: { next: { provider: 'deepseek', model: 'deepseek-chat' } },
      },
    },
  };
}

function userEvent(text, seq = 1, source = { kind: 'user', rpcId: 'pdsh-prompt-1' }) {
  return event('user/message', seq, {
    role: 'user',
    id: `msg-user-${seq}`,
    source,
    content: [{ type: 'text', text }],
  });
}

function assistantEvent(seq, text, reasoning) {
  const content = [];
  if (reasoning) {
    content.push({ type: 'reasoning', text: reasoning });
  }
  content.push({ type: 'text', text });
  return event('assistant/message', seq, {
    turn: 1,
    step: 1,
    message: {
      role: 'assistant',
      id: `msg-assistant-${seq}`,
      source: { kind: 'model', provider: 'deepseek', model: 'deepseek-chat' },
      content,
    },
    stream: [],
    usage: { input_tokens: 10, output_tokens: 5 },
  });
}

function toolCallEvent(seq, callId = 'toolu_1', name = 'bash') {
  return event('tool/call', seq, {
    turn: 1,
    step: 1,
    callId,
    name,
    arguments: '{"command":"cat a.csv"}',
  });
}

function toolResultEvent(seq, callId = 'toolu_1', text = 'a,b\n1,2') {
  return event('tool/result', seq, {
    turn: 1,
    step: 1,
    message: {
      role: 'tool',
      id: `msg-tool-${seq}`,
      source: { kind: 'tool', callId },
      toolCallId: callId,
      isError: false,
      content: [{ type: 'text', text }],
    },
  });
}

function standardRecords() {
  return [
    record(event('turn/start', 0, { turn: 1 })),
    record(userEvent('读 CSV', 1)),
    record(toolCallEvent(2)),
    record(toolResultEvent(3)),
    record(assistantEvent(4, 'CSV 有 2 行。', '先读取表格')),
    record(event('turn/end', 5, { turn: 1, reason: { kind: 'completed' } })),
  ];
}

test('opening snapshot projects the real Worker record envelope with stable seq ids', () => {
  const snap = projectFollowSnapshot(followSnapshot(standardRecords(), 5), 'sess_abc');
  assert.equal(snap.present, true);
  assert.equal(snap.cursor, 5);
  assert.equal(snap.recordCount, 6);
  assert.equal(snap.rows.length, 4, 'user + folded tool + reasoning + assistant');

  const user = snap.rows.find((row) => row.kind === K.User);
  assert.equal(user.text, '读 CSV');
  assert.equal(user.id, 'worker:sess_abc:1:user');
  assert.equal(user.status, 'done');
  assert.equal(user.fromSnapshot, true);

  const tool = snap.rows.find((row) => row.kind === K.Tool);
  assert.equal(tool.tool, 'bash');
  assert.equal(tool.path, 'toolu_1');
  assert.equal(tool.status, 'done');
  assert.equal(tool.id, 'worker:sess_abc:tool:toolu_1');
  assert.equal(tool.text, 'a,b');
  assert.match(tool.detail, /1,2/);

  const thinking = snap.rows.find((row) => row.kind === K.Thinking);
  assert.equal(thinking.text, '先读取表格');
  assert.equal(thinking.id, 'worker:sess_abc:4:thinking');

  const assistant = snap.rows.find((row) => row.kind === K.Assistant);
  assert.equal(assistant.text, 'CSV 有 2 行。');
  assert.equal(assistant.id, 'worker:sess_abc:4:assistant');
  assert.equal(assistant.createdAt, 1004);

  assert.equal(snap.rows.some((row) => row.kind === K.Error), false,
    'a completed turn/end has no failure row');
});

test('replaying the same snapshot is idempotent', () => {
  const snap = projectFollowSnapshot(followSnapshot(standardRecords(), 5), 'sess_abc');
  const first = mergeTranscriptRows([], snap.rows);
  assert.equal(first.appended, 4);
  assert.equal(first.adopted, 0);
  assert.equal(first.rows.length, 4);

  const second = mergeTranscriptRows(first.rows, snap.rows);
  assert.equal(second.appended, 0);
  assert.equal(second.adopted, 4);
  assert.equal(second.rows.length, 4);
});

test('a live event and its snapshot record are the same row', () => {
  const snap = projectFollowSnapshot(followSnapshot(standardRecords(), 5), 'sess_abc');
  const first = mergeTranscriptRows([], snap.rows);
  const live = projectEvent(assistantEvent(4, 'CSV 有 2 行。', '先读取表格'), 'sess_abc');
  const merged = mergeTranscriptRows(first.rows, live);
  assert.equal(merged.appended, 0);
  assert.equal(merged.adopted, 2);
  assert.equal(merged.rows.length, 4);
});

test('an optimistic user row is replaced in place, never duplicated', () => {
  const pending = {
    id: 'row-optimistic-1',
    kind: K.User,
    text: '读 CSV',
    detail: '',
    tool: '',
    status: 'running',
    path: '',
    createdAt: 1200,
  };
  const projected = projectEvent(userEvent('读 CSV', 7), 'sess_abc');
  const merged = mergeTranscriptRows([pending], projected);
  assert.equal(merged.appended, 0);
  assert.equal(merged.adopted, 1);
  assert.equal(merged.rows.length, 1);
  assert.equal(merged.rows[0].id, 'worker:sess_abc:7:user');
  assert.equal(merged.rows[0].status, 'done');
});

test('the optimistic prompt reconciles when HostService appends attachment paths', () => {
  const pending = {
    id: 'row-optimistic-2',
    kind: K.User,
    text: '读 CSV',
    detail: '',
    tool: '',
    status: 'running',
    path: '',
    createdAt: 1200,
  };
  const durable = userEvent('读 CSV\n\n[附件] /dsh/workspace/a.csv', 8);
  const merged = mergeTranscriptRows([pending], projectEvent(durable, 'sess_abc'));
  assert.equal(merged.rows.length, 1);
  assert.equal(merged.adopted, 1);
  assert.match(merged.rows[0].text, /\[附件\]/);
});

test('a snapshot cannot consume the in-flight optimistic row', () => {
  const pending = {
    id: 'row-optimistic-3',
    kind: K.User,
    text: '读 CSV',
    detail: '',
    tool: '',
    status: 'running',
    path: '',
    createdAt: 1200,
  };
  const snap = projectFollowSnapshot(followSnapshot([record(userEvent('读 CSV', 1))], 1), 'sess_abc');
  const merged = mergeTranscriptRows([pending], snap.rows);
  assert.equal(merged.rows.length, 2, 'the new pending row and the earlier durable row');
  assert.equal(merged.rows[0].id, 'row-optimistic-3');
  assert.equal(merged.rows[0].status, 'running');
  assert.equal(merged.rows[1].id, 'worker:sess_abc:1:user');

  // The durable echo of the new send adopts the pending row, not the earlier one.
  const echoed = mergeTranscriptRows(merged.rows, projectEvent(userEvent('读 CSV', 9), 'sess_abc'));
  assert.equal(echoed.rows.length, 2);
  assert.equal(echoed.rows[0].id, 'worker:sess_abc:9:user');
  assert.equal(echoed.rows[1].id, 'worker:sess_abc:1:user');
});

test('tool call and result fold into one stable tool row', () => {
  const call = mergeTranscriptRows([], projectEvent(toolCallEvent(2), 'sess_abc'));
  assert.equal(call.rows.length, 1);
  assert.equal(call.rows[0].status, 'running');
  assert.equal(call.rows[0].tool, 'bash');

  const result = mergeTranscriptRows(call.rows, projectEvent(toolResultEvent(3), 'sess_abc'));
  assert.equal(result.rows.length, 1);
  assert.equal(result.appended, 0);
  assert.equal(result.rows[0].id, 'worker:sess_abc:tool:toolu_1');
  assert.equal(result.rows[0].status, 'done');
  assert.equal(result.rows[0].tool, 'bash', 'the result must not erase the call name');
  assert.equal(result.rows[0].text, 'a,b');
});

test('a streamed final message is adopted by the durable assistant row', () => {
  const streaming = {
    id: 'row-streamed-final',
    kind: K.Assistant,
    text: 'CSV 有 2 行。',
    detail: '',
    tool: '',
    status: 'interrupted',
    path: '',
    createdAt: 1100,
  };
  const merged = mergeTranscriptRows([streaming], projectEvent(assistantEvent(4, 'CSV 有 2 行。', ''), 'sess_abc'));
  assert.equal(merged.rows.length, 1);
  assert.equal(merged.appended, 0);
  assert.equal(merged.adopted, 1);
  assert.equal(merged.rows[0].id, 'worker:sess_abc:4:assistant');
  assert.equal(merged.rows[0].status, 'done');
});

test('legacy random-id rows are adopted instead of duplicated', () => {
  const legacy = {
    id: 'row-1710000000-ab12',
    kind: K.Assistant,
    text: 'CSV 有 2 行。',
    detail: '',
    tool: '',
    status: 'done',
    path: '',
    createdAt: 1004,
  };
  const snap = projectFollowSnapshot(followSnapshot([record(assistantEvent(4, 'CSV 有 2 行。', ''))], 4), 'sess_abc');
  const merged = mergeTranscriptRows([legacy], snap.rows);
  assert.equal(merged.rows.length, 1);
  assert.equal(merged.adopted, 1);
  assert.equal(merged.rows[0].id, 'worker:sess_abc:4:assistant');
});

test('an interrupted turn/end row carries the sanitized provider failure', () => {
  const failed = event('turn/end', 9, {
    turn: 1,
    reason: {
      kind: 'error',
      error: {
        code: 'AUTH_FAILED',
        status: 401,
        message: 'request failed: Authorization: Bearer sk-live-secret123456 '
          + 'url=https://api.example.com/v1/messages?api_key=query-secret',
      },
    },
  });
  assert.equal(turnEndReasonOf(failed), 'error');
  const detail = turnEndDetailOf(failed);
  assert.match(detail, /AUTH_FAILED/);
  assert.match(detail, /HTTP 401/);
  assert.match(detail, /request failed/);
  assert.doesNotMatch(detail, /sk-live-secret/);
  assert.doesNotMatch(detail, /query-secret/);
  assert.doesNotMatch(detail, /Bearer sk-/);

  const rows = projectEvent(failed, 'sess_abc');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].kind, K.Error);
  assert.match(rows[0].text, /^本轮结束：error · AUTH_FAILED · HTTP 401/);
  assert.match(rows[0].detail, /AUTH_FAILED/);
  assert.doesNotMatch(rows[0].text, /query-secret/);
  assert.doesNotMatch(rows[0].detail, /query-secret/);
});

test('completed and unknown turn/end rows stay out of the transcript', () => {
  assert.equal(projectEvent(event('turn/end', 5, { turn: 1, reason: { kind: 'completed' } }), 's').length, 0);
  assert.equal(projectEvent(event('turn/end', 5, { turn: 1 }), 's').length, 0);
});

test('assistant reasoning is projected separately and flat text is still read', () => {
  const rows = projectEvent(assistantEvent(4, 'visible', 'secret'), 'sess_abc');
  const assistant = rows.find((row) => row.kind === K.Assistant);
  const thinking = rows.find((row) => row.kind === K.Thinking);
  assert.equal(assistant.text, 'visible');
  assert.equal(thinking.text, 'secret');

  const flat = projectEvent({ type: 'assistant/message', seq: 2, time: 1, data: { turn: 1, text: 'done' } }, 's');
  assert.equal(flat[0].text, 'done');
  assert.equal(flat[0].id, 'worker:s:2:assistant');
});

test('synthetic runtime-context user messages do not become user bubbles', () => {
  const synthetic = projectEvent(userEvent('injected instructions', 3, { kind: 'runtime-context' }), 's');
  assert.equal(synthetic.length, 0);
  const direct = projectEvent(userEvent('hello', 4, { kind: 'user', rpcId: 'pdsh-prompt-2' }), 's');
  assert.equal(direct.length, 1);
  assert.equal(direct[0].kind, K.User);
});

test('an event without a durable seq gets a stable best-effort id', () => {
  const rows = projectEvent({ type: 'assistant/message', data: { text: 'best effort' } }, 'sess_abc');
  assert.equal(rows.length, 1);
  assert.equal(rows[0].id, 'worker:sess_abc:best:assistant');
  assert.equal(rows[0].seq, -1);
});

test('malformed frames are ignored rather than throwing', () => {
  assert.equal(projectFollowSnapshot({ type: 'not-a-snapshot' }, 's').present, false);
  assert.equal(projectFollowSnapshot({ type: 'snapshot', records: 'nope' }, 's').rows.length, 0);
  assert.equal(projectEvent({ type: 'tool/result', seq: 1, data: {} }, 's').length, 1);
  assert.equal(projectEvent({ type: 'unknown/future', seq: 1, data: {} }, 's').length, 0);
});

test('sanitizer removes bearer, sk-, header and query credentials', () => {
  const input = 'x Authorization: Bearer abc.def sk-1234567890 x-api-key: header-secret '
    + 'https://example.test/v1?token=q1&key=q2';
  const output = sanitizeWorkerDetail(input);
  assert.doesNotMatch(output, /abc\.def/);
  assert.doesNotMatch(output, /sk-1234567890/);
  assert.doesNotMatch(output, /header-secret/);
  assert.doesNotMatch(output, /q1|q2/);
  assert.match(output, /\[redacted\]/);
});

console.log('transcript projection tests loaded:', pathToFileURL(ENTRY).href);
