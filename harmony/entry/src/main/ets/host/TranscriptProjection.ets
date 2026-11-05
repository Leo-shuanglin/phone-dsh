// SPDX-License-Identifier: MIT
// Pure transcript projection for the Worker's `session/follow` snapshot and live
// events. No platform imports: the App supplies the Worker session id and the
// helper returns rows with identity derived from the Worker's own durable `seq`.
//
// ---- the wire shape this module owns --------------------------------------
// `session/follow` opens with one frame of the Worker's own schema:
//
//   { type: 'snapshot', cursor, hasMore, records: [
//       { type: 'event', event: { type, seq, time, data } }, ...
//   ] }
//
// Followed by live `{ type: 'event', event: { ... } }` frames. Records and live
// events carry the exact same `SessionWireEvent` envelope
// (`packages/api/session-controller/src/types.ts`, `SessionHistoryRecord`). The
// App does not rename or re-shape them here; only the surface events that own a
// transcript row are folded: `user/message`, `assistant/message`, `tool/call`,
// `tool/result` and a non-completed `turn/end`.
//
// ---- stable identity and append dedup -------------------------------------
// Android's projection is `session/<chat>/turn/<turn>/event/<seq>/<slot>`: the
// durable `seq` is the identity, and an append whose id already exists is
// dropped (`ChatSurface.kt` `applyChatEvent`). This module uses the Worker
// session id that owns the event instead of the App chat id, so a row recovered
// from a snapshot and the same row delivered live are the same row:
//
//   worker:<sessionId>:<seq>:<slot>
//   worker:<sessionId>:tool:<callId>      (a call and its result share one row)
//
// Legacy rows written before this module have random ids. `mergeTranscriptRows`
// adopts them in place by their semantic identity (same call id, same text) so
// an upgrade does not duplicate the transcript.
import { ChatRow, RowKind } from '../model/Contracts';

const TEXT_LIMIT: number = 20000;
const ARGUMENT_LIMIT: number = 4000;
const RESULT_LIMIT: number = 4000;
const ERROR_DETAIL_LIMIT: number = 300;

/** One projected row with the Worker facts that own its identity. */
export class TranscriptRow extends ChatRow {
  /** Worker durable sequence; -1 for a best-effort event with no seq. */
  seq: number = -1;
  /** Worker session that owns the event. */
  workerSessionId: string = '';
  /** The stable slot within the event (`user`, `assistant`, `tool`, ...). */
  slot: string = '';
  /** Provider-issued tool call id, when the row is a tool row. */
  toolCallId: string = '';
  /** True when this row came from an opening snapshot rather than a live event. */
  fromSnapshot: boolean = false;
}

/** One decoded `session/follow` opening snapshot. */
export class FollowSnapshotProjection {
  /** True when `value.type` was the Worker snapshot envelope. */
  present: boolean = false;
  /** The log cut the Worker reported for the records in this snapshot. */
  cursor: number = -1;
  hasMore: boolean = false;
  /** Number of `records` entries seen, including ones with no visible row. */
  recordCount: number = 0;
  rows: TranscriptRow[] = [];
}

/** Result of adopting/appending projected rows into the stored transcript. */
export class TranscriptMergeResult {
  rows: ChatRow[] = [];
  /** Rows matched by id or by legacy semantic identity and updated in place. */
  adopted: number = 0;
  /** Rows that had no counterpart and were appended. */
  appended: number = 0;
}

function asRecord(value: Object | null | undefined): Record<string, Object> | null {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, Object>;
}

function asArray(value: Object | null | undefined): Object[] {
  if (value === null || value === undefined) {
    return [];
  }
  if (!Array.isArray(value)) {
    return [];
  }
  return value as Object[];
}

function field(record: Record<string, Object> | null, key: string): Object | null {
  if (record === null) {
    return null;
  }
  const value: Object | undefined = record[key];
  return value === undefined ? null : value;
}

function jsonString(record: Record<string, Object> | null, key: string, fallback: string = ''): string {
  const value = field(record, key);
  return typeof value === 'string' ? value as string : fallback;
}

function jsonNumber(record: Record<string, Object> | null, key: string, fallback: number = 0): number {
  const value = field(record, key);
  if (typeof value === 'number' && Number.isFinite(value as number)) {
    return value as number;
  }
  return fallback;
}

function jsonBool(record: Record<string, Object> | null, key: string, fallback: boolean = false): boolean {
  const value = field(record, key);
  return typeof value === 'boolean' ? value as boolean : fallback;
}

function bounded(text: string, limit: number): string {
  if (text.length <= limit) {
    return text;
  }
  return text.substring(0, limit);
}

function sessionKey(workerSessionId: string): string {
  return workerSessionId.length > 0 ? workerSessionId : 'worker';
}

function stableRowId(workerSessionId: string, seq: number, slot: string): string {
  const cut = seq >= 0 ? seq.toString() : 'best';
  return `worker:${sessionKey(workerSessionId)}:${cut}:${slot}`;
}

function toolRowId(workerSessionId: string, seq: number, callId: string): string {
  if (callId.length > 0) {
    return `worker:${sessionKey(workerSessionId)}:tool:${callId}`;
  }
  return stableRowId(workerSessionId, seq, 'tool');
}

function stringifyValue(value: Object | null): string {
  if (value === null) {
    return '';
  }
  if (typeof value === 'string') {
    return bounded(value as string, ARGUMENT_LIMIT);
  }
  try {
    return bounded(JSON.stringify(value), ARGUMENT_LIMIT);
  } catch (error) {
    return '';
  }
}

function textFromContent(value: Object | null): string {
  if (value === null) {
    return '';
  }
  if (typeof value === 'string') {
    return value as string;
  }
  const blocks = asArray(value);
  let text = '';
  for (let index = 0; index < blocks.length; index++) {
    const block = asRecord(blocks[index]);
    if (block !== null && jsonString(block, 'type') === 'text') {
      text += jsonString(block, 'text', '');
    }
  }
  return text;
}

function reasoningFromContent(value: Object | null): string {
  const blocks = asArray(value);
  let text = '';
  for (let index = 0; index < blocks.length; index++) {
    const block = asRecord(blocks[index]);
    if (block !== null && jsonString(block, 'type') === 'reasoning') {
      const part = jsonString(block, 'text', '');
      if (part.length > 0) {
        text += `${text.length > 0 ? '\n' : ''}${part}`;
      }
    }
  }
  return text;
}

function firstLine(text: string): string {
  const cut = text.indexOf('\n');
  return (cut >= 0 ? text.substring(0, cut) : text).trim();
}

function makeRow(): TranscriptRow {
  const row = new TranscriptRow();
  row.kind = RowKind.Assistant;
  row.status = 'done';
  return row;
}

function userMessageData(data: Record<string, Object> | null): Record<string, Object> | null {
  if (data === null) {
    return null;
  }
  const nested = asRecord(data['message']);
  return nested === null ? data : nested;
}

function isDirectUserMessage(data: Record<string, Object> | null): boolean {
  const message = userMessageData(data);
  if (message === null) {
    return false;
  }
  const source = asRecord(field(message, 'source'));
  if (source === null) {
    // Older fixtures may omit the source; a message with content is still a
    // user prompt. Known synthetic sources are excluded below when present.
    return true;
  }
  const kind = jsonString(source, 'kind', '');
  return kind.length === 0 || kind === 'user';
}

function projectUserMessage(
  data: Record<string, Object> | null,
  workerSessionId: string,
  seq: number,
  time: number,
): TranscriptRow[] {
  if (!isDirectUserMessage(data)) {
    return [];
  }
  const message = userMessageData(data);
  let text = textFromContent(field(message, 'content'));
  if (text.length === 0) {
    text = jsonString(message, 'text', '');
  }
  if (text.length === 0) {
    return [];
  }
  const row = makeRow();
  row.id = stableRowId(workerSessionId, seq, 'user');
  row.kind = RowKind.User;
  row.text = bounded(text, TEXT_LIMIT);
  row.status = 'done';
  row.createdAt = time;
  row.seq = seq;
  row.workerSessionId = workerSessionId;
  row.slot = 'user';
  return [row];
}

function projectAssistantMessage(
  data: Record<string, Object> | null,
  workerSessionId: string,
  seq: number,
  time: number,
): TranscriptRow[] {
  if (data === null) {
    return [];
  }
  const message = asRecord(field(data, 'message'));
  const content = message === null ? field(data, 'content') : field(message, 'content');
  let text = textFromContent(content);
  if (text.length === 0) {
    text = jsonString(data, 'text', '');
  }
  if (text.length === 0) {
    // An empty assistant message is not a model-visible row
    // (`assistant/message` surface contract). Its usage still has no UI row.
    return [];
  }
  const reasoning = reasoningFromContent(content);
  const rows: TranscriptRow[] = [];
  if (reasoning.length > 0) {
    const thinking = makeRow();
    thinking.id = stableRowId(workerSessionId, seq, 'thinking');
    thinking.kind = RowKind.Thinking;
    thinking.text = bounded(reasoning, TEXT_LIMIT);
    thinking.status = jsonBool(data, 'interrupted', false) ? 'interrupted' : 'done';
    thinking.createdAt = time;
    thinking.seq = seq;
    thinking.workerSessionId = workerSessionId;
    thinking.slot = 'thinking';
    rows.push(thinking);
  }
  const row = makeRow();
  row.id = stableRowId(workerSessionId, seq, 'assistant');
  row.kind = RowKind.Assistant;
  row.text = bounded(text, TEXT_LIMIT);
  row.status = jsonBool(data, 'interrupted', false) ? 'interrupted' : 'done';
  row.createdAt = time;
  row.seq = seq;
  row.workerSessionId = workerSessionId;
  row.slot = 'assistant';
  rows.push(row);
  return rows;
}

function projectToolCall(
  data: Record<string, Object> | null,
  workerSessionId: string,
  seq: number,
  time: number,
): TranscriptRow[] {
  if (data === null) {
    return [];
  }
  let callId = jsonString(data, 'callId', '');
  if (callId.length === 0) {
    callId = jsonString(data, 'call_id', '');
  }
  const name = jsonString(data, 'name', '');
  let argumentsText = '';
  const args = field(data, 'arguments');
  if (args === null) {
    argumentsText = stringifyValue(field(data, 'input'));
  } else {
    argumentsText = stringifyValue(args);
  }
  const row = makeRow();
  row.id = toolRowId(workerSessionId, seq, callId);
  row.kind = RowKind.Tool;
  row.tool = name.length > 0 ? name : 'tool';
  row.text = '';
  row.detail = bounded(argumentsText, ARGUMENT_LIMIT);
  row.status = 'running';
  row.path = callId;
  row.createdAt = time;
  row.seq = seq;
  row.workerSessionId = workerSessionId;
  row.slot = 'tool';
  row.toolCallId = callId;
  return [row];
}

function toolResultDetail(data: Record<string, Object> | null): string {
  if (data === null) {
    return '';
  }
  const message = asRecord(field(data, 'message'));
  let detail = '';
  if (message !== null) {
    detail = textFromContent(field(message, 'content'));
  }
  if (detail.length === 0) {
    detail = jsonString(data, 'summary', '');
  }
  if (detail.length === 0) {
    detail = jsonString(data, 'detail', '');
  }
  if (detail.length === 0) {
    const result = field(data, 'result');
    if (result !== null) {
      detail = textFromContent(result);
      if (detail.length === 0) {
        detail = stringifyValue(result);
      }
    }
  }
  if (detail.length === 0) {
    detail = textFromContent(field(data, 'output'));
  }
  if (detail.length === 0) {
    detail = jsonString(data, 'text', '');
  }
  const error = asRecord(field(data, 'error'));
  if (error !== null) {
    const reason = jsonString(error, 'reason', '');
    const name = jsonString(error, 'name', '');
    const line = reason.length > 0 ? reason : name;
    if (line.length > 0) {
      detail += `${detail.length > 0 ? '\n' : ''}${line}`;
    }
  }
  const bytes = jsonNumber(data, 'bytes', -1);
  const duration = jsonNumber(data, 'duration_ms', -1);
  if (bytes >= 0) {
    detail += `${detail.length > 0 ? '\n' : ''}${bytes} bytes`;
  }
  if (duration >= 0) {
    detail += `${detail.length > 0 ? '\n' : ''}${duration} ms`;
  }
  return bounded(detail, RESULT_LIMIT);
}

function projectToolResult(
  data: Record<string, Object> | null,
  workerSessionId: string,
  seq: number,
  time: number,
): TranscriptRow[] {
  if (data === null) {
    return [];
  }
  const message = asRecord(field(data, 'message'));
  let callId = jsonString(data, 'callId', '');
  if (callId.length === 0) {
    callId = jsonString(data, 'call_id', '');
  }
  if (callId.length === 0 && message !== null) {
    callId = jsonString(message, 'toolCallId', '');
  }
  if (callId.length === 0 && message !== null) {
    const source = asRecord(field(message, 'source'));
    callId = jsonString(source, 'callId', '');
  }
  const isError = jsonBool(data, 'isError', false)
    || (message !== null && jsonBool(message, 'isError', false))
    || field(data, 'error') !== null;
  const detail = toolResultDetail(data);
  const row = makeRow();
  row.id = toolRowId(workerSessionId, seq, callId);
  row.kind = RowKind.Tool;
  row.tool = '';
  row.text = bounded(firstLine(detail), 1000);
  row.detail = detail;
  row.status = isError ? 'error' : 'done';
  row.path = callId;
  row.createdAt = time;
  row.seq = seq;
  row.workerSessionId = workerSessionId;
  row.slot = 'tool';
  row.toolCallId = callId;
  return [row];
}

/**
 * Sanitize free-form Worker/provider detail before it reaches the UI.
 *
 * The Worker never holds the App's credential, but transport and provider
 * errors can echo headers, URL credentials or query parameters. Remove those
 * shapes and collapse whitespace; the HostService still runs its own `clean()`
 * (native launch token) over the result.
 */
export function sanitizeWorkerDetail(text: string): string {
  let value = text;
  value = value.replace(/Bearer\s+[^\s,;]+/gi, 'Bearer [redacted]');
  value = value.replace(/sk-[A-Za-z0-9_-]{6,}/g, '[redacted]');
  value = value.replace(/(?:authorization|x-api-key|api[_-]?key)\s*[:=]\s*[^\s,;]+/gi, '[redacted]');
  value = value.replace(
    /((?:api[_-]?key|access[_-]?token|token|secret|password)\s*[:=]\s*)[^\s,;"']+/gi,
    '$1[redacted]',
  );
  value = value.replace(/([?&](?:api[_-]?key|access[_-]?token|token|key|secret)=)[^&\s]+/gi, '$1[redacted]');
  value = value.replace(/\s+/g, ' ').trim();
  return bounded(value, ERROR_DETAIL_LIMIT);
}

function turnEndDetailFromReason(reason: Record<string, Object> | null): string {
  if (reason === null) {
    return '';
  }
  const kind = jsonString(reason, 'kind', '');
  if (kind === 'error') {
    let failure = asRecord(field(reason, 'error'));
    if (failure === null) {
      failure = asRecord(field(reason, 'failure'));
    }
    const parts: string[] = [];
    if (failure !== null) {
      const code = jsonString(failure, 'code', '');
      const status = jsonNumber(failure, 'status', 0);
      const message = jsonString(failure, 'message', '');
      if (code.length > 0) {
        parts.push(code);
      }
      if (status > 0) {
        parts.push(`HTTP ${status}`);
      }
      if (message.length > 0) {
        parts.push(firstLine(message));
      }
    }
    if (parts.length === 0) {
      return 'Worker 未提供更具体的失败信息';
    }
    return sanitizeWorkerDetail(parts.join(' · '));
  }
  if (kind === 'aborted') {
    const cause = asRecord(field(reason, 'reason'));
    const causeKind = jsonString(cause, 'kind', '');
    if (causeKind === 'hook') {
      const hookReason = jsonString(cause, 'reason', '');
      if (hookReason.length > 0) {
        return sanitizeWorkerDetail(`hook: ${hookReason}`);
      }
      return 'hook';
    }
    if (causeKind.length > 0) {
      return sanitizeWorkerDetail(causeKind);
    }
    return '';
  }
  return '';
}

/** `turn/end.data.reason.kind`, or `unknown` for a malformed frame. */
export function turnEndReasonOf(value: Object): string {
  const raw = asRecord(value);
  if (raw === null) {
    return 'unknown';
  }
  const data = asRecord(field(raw, 'data'));
  const reason = asRecord(data === null ? null : field(data, 'reason'));
  return jsonString(reason, 'kind', 'unknown');
}

/** Sanitized, user-visible detail of a `turn/end` reason. */
export function turnEndDetailOf(value: Object): string {
  const raw = asRecord(value);
  if (raw === null) {
    return '';
  }
  const data = asRecord(field(raw, 'data'));
  const reason = asRecord(data === null ? null : field(data, 'reason'));
  return turnEndDetailFromReason(reason);
}

/**
 * Project one Worker `SessionWireEvent` into the rows this App displays.
 *
 * Unknown types and lifecycle-only events yield no row; a non-completed
 * `turn/end` yields one visible failure row whose detail is the sanitized
 * provider failure, not just `reason.kind`.
 */
export function projectEvent(event: Record<string, Object>, workerSessionId: string): TranscriptRow[] {
  const type = jsonString(event, 'type', '');
  const seq = jsonNumber(event, 'seq', -1);
  const time = jsonNumber(event, 'time', 0);
  const data = asRecord(field(event, 'data'));
  if (type === 'user/message') {
    return projectUserMessage(data, workerSessionId, seq, time);
  }
  if (type === 'assistant/message') {
    return projectAssistantMessage(data, workerSessionId, seq, time);
  }
  if (type === 'tool/call') {
    return projectToolCall(data, workerSessionId, seq, time);
  }
  if (type === 'tool/result') {
    return projectToolResult(data, workerSessionId, seq, time);
  }
  if (type === 'turn/end') {
    const reason = asRecord(data === null ? null : field(data, 'reason'));
    const kind = jsonString(reason, 'kind', 'unknown');
    if (kind === 'completed' || kind === 'unknown') {
      return [];
    }
    const row = makeRow();
    row.id = stableRowId(workerSessionId, seq, 'turn-end');
    const detail = turnEndDetailFromReason(reason);
    const userStopped = kind === 'aborted' && detail === 'user';
    row.kind = userStopped ? RowKind.System : RowKind.Error;
    // `ChatRowView` draws an error row's `text`; keep the title and append the
    // real failure there, and retain the same value in `detail` for export.
    row.text = userStopped ? '已停止' : (detail.length > 0 ? `本轮结束：${kind} · ${detail}` : `本轮结束：${kind}`);
    row.detail = userStopped ? '' : detail;
    row.status = userStopped ? 'cancelled' : 'failed';
    row.createdAt = time;
    row.seq = seq;
    row.workerSessionId = workerSessionId;
    row.slot = 'turn-end';
    return [row];
  }
  return [];
}

/**
 * Decode one `session/follow` opening snapshot.
 *
 * @param value the frame's `value` object (the `{ type: 'snapshot', ... }` body).
 * @param workerSessionId the Worker session the follow request addressed.
 */
export function projectFollowSnapshot(value: Object, workerSessionId: string): FollowSnapshotProjection {
  const result = new FollowSnapshotProjection();
  const raw = asRecord(value);
  if (raw === null || jsonString(raw, 'type') !== 'snapshot') {
    return result;
  }
  result.present = true;
  result.cursor = jsonNumber(raw, 'cursor', -1);
  result.hasMore = jsonBool(raw, 'hasMore', false);
  const header = asRecord(field(raw, 'header'));
  let owner = workerSessionId;
  if (owner.length === 0 && header !== null) {
    owner = jsonString(header, 'id', '');
  }
  const records = asArray(field(raw, 'records'));
  result.recordCount = records.length;
  const seen: Map<string, number> = new Map<string, number>();
  for (let index = 0; index < records.length; index++) {
    const record = asRecord(records[index]);
    const event = asRecord(record === null ? null : field(record, 'event'));
    if (event === null) {
      continue;
    }
    const rows = projectEvent(event, owner);
    for (let rowIndex = 0; rowIndex < rows.length; rowIndex++) {
      const row = rows[rowIndex];
      const existing = seen.get(row.id);
      if (existing !== undefined) {
        // A tool call and its result share one stable id; the later durable
        // record must update the earlier row, not be dropped as a duplicate.
        copyProjected(result.rows[existing], row);
        result.rows[existing].fromSnapshot = true;
        continue;
      }
      seen.set(row.id, result.rows.length);
      row.fromSnapshot = true;
      result.rows.push(row);
    }
  }
  return result;
}

function copyProjected(target: ChatRow, source: TranscriptRow): void {
  target.id = source.id;
  target.kind = source.kind;
  target.text = source.text;
  target.detail = source.detail.length > 0 ? source.detail : target.detail;
  target.tool = source.tool.length > 0 ? source.tool : target.tool;
  target.status = source.status.length > 0 ? source.status : target.status;
  target.path = source.path.length > 0 ? source.path : target.path;
  target.createdAt = source.createdAt > 0 ? source.createdAt : target.createdAt;
}

function sameUserText(existing: string, projected: string): boolean {
  if (existing === projected) {
    return true;
  }
  // HostService appends `[附件]` lines to the prompt but the optimistic local
  // row carries only what the person typed. Adopt that prefix instead of
  // rendering a second bubble for the same submission.
  return projected.startsWith(`${existing}\n\n[附件]`);
}

function semanticMatchIndex(rows: ChatRow[], claimed: boolean[], source: TranscriptRow): number {
  for (let index = 0; index < rows.length; index++) {
    if (claimed[index]) {
      continue;
    }
    const row = rows[index];
    if (source.fromSnapshot && row.status === 'running') {
      // A snapshot is the history before this send. It must not swallow the
      // optimistic user/tool row the live turn is still using.
      continue;
    }
    if (source.kind === RowKind.Tool) {
      if (row.kind !== RowKind.Tool) {
        continue;
      }
      if (source.path.length > 0 && row.path === source.path) {
        return index;
      }
      if (source.text.length > 0 && row.text === source.text && row.tool === source.tool) {
        return index;
      }
      continue;
    }
    if (row.kind !== source.kind) {
      continue;
    }
    if (source.kind === RowKind.User) {
      if (sameUserText(row.text, source.text)) {
        return index;
      }
      continue;
    }
    if (source.text.length > 0 && row.text === source.text) {
      return index;
    }
  }
  return -1;
}

/**
 * Fold projected rows into the existing transcript.
 *
 * Exact stable ids update in place; legacy rows with random ids are adopted by
 * call id or text instead of being duplicated. Unmatched rows append. Local
 * pending rows are never consumed by a snapshot.
 */
export function mergeTranscriptRows(existing: ChatRow[], projected: TranscriptRow[]): TranscriptMergeResult {
  const result = new TranscriptMergeResult();
  const rows: ChatRow[] = [];
  const claimed: boolean[] = [];
  for (let index = 0; index < existing.length; index++) {
    rows.push(existing[index]);
    claimed.push(false);
  }
  for (let index = 0; index < projected.length; index++) {
    const source = projected[index];
    let target = -1;
    for (let scan = 0; scan < rows.length; scan++) {
      if (rows[scan].id === source.id) {
        target = scan;
        break;
      }
    }
    if (target >= 0) {
      copyProjected(rows[target], source);
      claimed[target] = true;
      result.adopted += 1;
      continue;
    }
    target = semanticMatchIndex(rows, claimed, source);
    if (target >= 0) {
      rows[target] = source;
      claimed[target] = true;
      result.adopted += 1;
      continue;
    }
    rows.push(source);
    claimed.push(true);
    result.appended += 1;
  }
  result.rows = rows;
  return result;
}
