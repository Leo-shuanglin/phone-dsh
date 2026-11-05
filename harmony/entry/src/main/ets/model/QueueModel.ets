// SPDX-License-Identifier: MIT
// Data-only durable DSH queue projection and action codec.
//
// The only authoritative queue read is the `sequenced` inbox projection at
// `values.inbox['next-turn']`. A missing lane, a failed read or a
// `migration-required` marker is Unknown (`null`), never an empty queue.
// Identity comes from the durable `item.id`, the text parts and, for a user
// source, `source.rpcId`; preview text is never an identity.

export enum QueueOrigin {
  Local = 'local',
  Durable = 'durable'
}

export enum QueueItemState {
  Pending = 'pending',
  Ready = 'ready',
  Failed = 'failed'
}

export enum QueueItemAction {
  Edit = 'edit',
  Remove = 'remove',
  Steer = 'steer'
}

/** One queue row. `origin` decides whether the row can be mutated. */
export class QueueItem {
  id: string = '';
  origin: QueueOrigin = QueueOrigin.Durable;
  workerSessionId: string = '';
  promptRequestId: string = '';
  text: string = '';
  lane: string = 'next-turn';
  state: QueueItemState = QueueItemState.Ready;
  canEdit: boolean = false;
  canRemove: boolean = false;
  canSteer: boolean = false;
  busy: boolean = false;
  // The in-flight durable action, or the last failed action. UI-only: these are
  // never sent back as wire fields.
  action: string = '';
  failure: string = '';
}

/** `items: null` means the projection did not establish a queue. */
export class QueueProjectionResult {
  items: QueueItem[] | null = null;
  failure: string = '';
}

export class PendingQueueRetirement {
  removed: boolean = false;
  remaining: string[] = [];
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

function asArray(value: Object | null | undefined): Object[] | null {
  if (value === null || value === undefined || !Array.isArray(value)) {
    return null;
  }
  return value as Object[];
}

function textOf(value: Object | null | undefined): string {
  return typeof value === 'string' ? value as string : '';
}

function unwrap(value: Object | null | undefined): Record<string, Object> | null {
  let root = asRecord(value);
  if (root === null) {
    return null;
  }
  // The HostBridge may hand back the RPC envelope (`{ok, value}`) or the bare
  // result. Only unwrap an actual `ok:true` envelope; never read a second
  // projection from an unrelated field.
  if (root['ok'] === true) {
    const inner = asRecord(root['value']);
    if (inner !== null) {
      root = inner;
    }
  }
  return root;
}

function kindOf(row: Record<string, Object> | null): string {
  if (row === null) {
    return '';
  }
  const raw = row['kind'];
  if (raw === undefined || raw === null) {
    return '';
  }
  if (typeof raw !== 'string') {
    return '__invalid__';
  }
  return raw as string;
}

/**
 * The inbox projection is declared `sequenced`. Validate the declaration when
 * the Worker reports it; never infer readiness from another projection kind.
 */
function projectionKind(root: Record<string, Object>, values: Record<string, Object>,
  inbox: Record<string, Object>): string {
  const candidates: Record<string, Object>[] = [inbox, values, root];
  for (let index = 0; index < candidates.length; index++) {
    const kind = kindOf(candidates[index]);
    if (kind.length > 0) {
      return kind;
    }
  }
  const projections = asRecord(root['projections']);
  const metadata = projections === null ? null : asRecord(projections['inbox']);
  const metadataKind = kindOf(metadata);
  if (metadataKind.length > 0) {
    return metadataKind;
  }
  return '';
}

function isMigrationValue(value: Object | null | undefined): boolean {
  if (value === true) {
    return true;
  }
  if (typeof value !== 'string') {
    return false;
  }
  const text = value as string;
  return text === 'migration-required' || text === 'migration_required' || text === 'required';
}

/**
 * `migration-required` is an unknown queue, not an empty one. DSH reports the
 * marker as a kebab-case field or state; only those exact spellings count.
 */
function migrationRequired(root: Record<string, Object>, values: Record<string, Object>,
  inbox: Record<string, Object>): boolean {
  const rows: Record<string, Object>[] = [root, values, inbox];
  for (let index = 0; index < rows.length; index++) {
    const row = rows[index];
    if (isMigrationValue(row['migration-required'])) {
      return true;
    }
    const state = textOf(row['state']);
    const status = textOf(row['status']);
    if (state === 'migration-required' || status === 'migration-required') {
      return true;
    }
  }
  return false;
}

function promptRequestIdOf(source: Object | null | undefined): string {
  const record = asRecord(source);
  if (record === null) {
    return '';
  }
  // Only a real user source owns a prompt request identity.
  if (textOf(record['kind']) !== 'user') {
    return '';
  }
  return textOf(record['rpcId']);
}

function failure(message: string): QueueProjectionResult {
  const result = new QueueProjectionResult();
  result.items = null;
  result.failure = message;
  return result;
}

function parseItem(workerSessionId: string, raw: Object): QueueItem | null {
  const record = asRecord(raw);
  if (record === null) {
    return null;
  }
  const id = textOf(record['id']);
  if (id.length === 0) {
    return null;
  }
  const item = new QueueItem();
  item.id = id;
  item.origin = QueueOrigin.Durable;
  item.workerSessionId = workerSessionId;
  item.lane = 'next-turn';
  item.state = QueueItemState.Ready;
  item.canRemove = true;
  item.canSteer = false;
  item.busy = false;
  const content = asArray(record['content']);
  if (content !== null) {
    let allText = true;
    const parts: string[] = [];
    for (let index = 0; index < content.length; index++) {
      const part = asRecord(content[index]);
      if (part === null || textOf(part['type']) !== 'text' || typeof part['text'] !== 'string') {
        allText = false;
        continue;
      }
      parts.push(part['text'] as string);
    }
    item.text = parts.join('\n');
    item.canEdit = allText && content.length > 0 && item.text.trim().length > 0;
  }
  item.promptRequestId = promptRequestIdOf(record['source']);
  return item;
}

/**
 * Read one `session/projections` result. Only `values.inbox['next-turn']` is a
 * queue; an absent values/inbox/lane, a malformed row, duplicate identity or a
 * migration marker makes the whole queue Unknown.
 */
export function parseQueueProjection(workerSessionId: string,
  rootValue: Object | null | undefined): QueueProjectionResult {
  const root = unwrap(rootValue);
  if (root === null) {
    return failure('会话投影不是对象');
  }
  const values = asRecord(root['values']);
  if (values === null) {
    return failure('会话投影缺少 values');
  }
  const inbox = asRecord(values['inbox']);
  if (inbox === null) {
    return failure('会话投影缺少 inbox');
  }
  if (migrationRequired(root, values, inbox)) {
    return failure('队列需要迁移（migration-required），无法确认当前队列');
  }
  const kind = projectionKind(root, values, inbox);
  if (kind === '__invalid__') {
    return failure('inbox projection kind 无效');
  }
  if (kind.length > 0 && kind !== 'sequenced') {
    return failure('inbox projection 不是 sequenced：' + kind);
  }
  const lane = asArray(inbox['next-turn']);
  if (lane === null) {
    return failure("会话投影缺少 inbox['next-turn']");
  }
  const seen: Set<string> = new Set<string>();
  const items: QueueItem[] = [];
  for (let index = 0; index < lane.length; index++) {
    const item = parseItem(workerSessionId, lane[index]);
    if (item === null) {
      return failure('inbox next-turn 包含无效队列项');
    }
    if (seen.has(item.id)) {
      return failure('inbox next-turn 包含重复队列项 id');
    }
    seen.add(item.id);
    items.push(item);
  }
  const result = new QueueProjectionResult();
  result.items = items;
  result.failure = '';
  return result;
}

export function encodeQueueAction(action: QueueItemAction,
  replacement: string): Record<string, Object> | null {
  const payload: Record<string, Object> = {};
  if (action === QueueItemAction.Edit) {
    if (replacement.trim().length === 0) {
      return null;
    }
    const part: Record<string, Object> = {};
    part['type'] = 'text';
    part['text'] = replacement;
    const content: Object[] = [part];
    payload['kind'] = 'edit';
    payload['content'] = content;
    return payload;
  }
  if (action === QueueItemAction.Remove) {
    payload['kind'] = 'remove';
    return payload;
  }
  if (action === QueueItemAction.Steer) {
    payload['kind'] = 'steer';
    return payload;
  }
  return null;
}

/** Exact `session/updateQueue` args, or null for an invalid action/identity. */
export function encodeQueueUpdate(workerSessionId: string, itemId: string,
  action: QueueItemAction, replacement: string): Record<string, Object> | null {
  if (workerSessionId.length === 0 || itemId.length === 0) {
    return null;
  }
  const actionValue = encodeQueueAction(action, replacement);
  if (actionValue === null) {
    return null;
  }
  const request: Record<string, Object> = {};
  request['sessionId'] = workerSessionId;
  request['itemId'] = itemId;
  request['action'] = actionValue;
  const args: Record<string, Object> = {};
  args['request'] = request;
  return args;
}

/** A local echo owns the real prompt request id, never a durable item id. */
export function localQueueEcho(workerSessionId: string, promptRequestId: string, text: string,
  lane: string): QueueItem {
  const item = new QueueItem();
  item.id = 'local:' + promptRequestId;
  item.origin = QueueOrigin.Local;
  item.workerSessionId = workerSessionId;
  item.promptRequestId = promptRequestId;
  item.text = text;
  item.lane = lane;
  item.state = QueueItemState.Pending;
  item.canEdit = false;
  item.canRemove = false;
  item.canSteer = false;
  item.busy = true;
  return item;
}

/** Prompt ack stopped the upload; the echo waits for durable/admitted proof. */
export function acknowledgeLocalQueuePrompt(items: QueueItem[], promptRequestId: string): QueueItem[] {
  const next: QueueItem[] = [];
  for (let index = 0; index < items.length; index++) {
    const item = items[index];
    if (item.origin === QueueOrigin.Local && item.promptRequestId === promptRequestId) {
      const copy = cloneQueueItem(item);
      copy.busy = false;
      next.push(copy);
    } else {
      next.push(item);
    }
  }
  return next;
}

export function failLocalQueuePrompt(items: QueueItem[], promptRequestId: string,
  message: string): QueueItem[] {
  const next: QueueItem[] = [];
  for (let index = 0; index < items.length; index++) {
    const item = items[index];
    if (item.origin === QueueOrigin.Local && item.promptRequestId === promptRequestId) {
      const copy = cloneQueueItem(item);
      copy.busy = false;
      copy.state = QueueItemState.Failed;
      copy.failure = message;
      next.push(copy);
    } else {
      next.push(item);
    }
  }
  return next;
}

/** Retire local echoes only for a durable `source.rpcId`, never by text. */
export function retireLocalQueueByDurable(local: QueueItem[],
  durable: QueueItem[] | null): QueueItem[] {
  if (durable === null) {
    return local;
  }
  const ids: Set<string> = new Set<string>();
  for (let index = 0; index < durable.length; index++) {
    if (durable[index].origin === QueueOrigin.Durable && durable[index].promptRequestId.length > 0) {
      ids.add(durable[index].promptRequestId);
    }
  }
  if (ids.size === 0) {
    return local;
  }
  const next: QueueItem[] = [];
  for (let index = 0; index < local.length; index++) {
    const item = local[index];
    if (item.origin !== QueueOrigin.Local || item.promptRequestId.length === 0 ||
      !ids.has(item.promptRequestId)) {
      next.push(item);
    }
  }
  return next;
}

/** Retire one local echo after an admitted user/message carried its rpcId. */
export function retireLocalQueuePrompt(local: QueueItem[], promptRequestId: string): QueueItem[] {
  if (promptRequestId.length === 0) {
    return local;
  }
  const next: QueueItem[] = [];
  for (let index = 0; index < local.length; index++) {
    const item = local[index];
    if (item.origin === QueueOrigin.Local && item.promptRequestId === promptRequestId) {
      continue;
    }
    next.push(item);
  }
  return next;
}

/** `data.message.source.rpcId` for a real user message, or ''. */
export function admittedUserPromptRequestId(eventValue: Object | null | undefined): string {
  const event = asRecord(eventValue);
  if (event === null) {
    return '';
  }
  const data = asRecord(event['data']);
  if (data !== null) {
    const message = asRecord(data['message']);
    if (message !== null) {
      const fromMessage = promptRequestIdOf(message['source']);
      if (fromMessage.length > 0) {
        return fromMessage;
      }
    }
    const direct = promptRequestIdOf(data['source']);
    if (direct.length > 0) {
      return direct;
    }
  }
  const message = asRecord(event['message']);
  return message === null ? '' : promptRequestIdOf(message['source']);
}

export function containsPromptRequestId(items: QueueItem[] | null, promptRequestId: string): boolean {
  if (items === null || promptRequestId.length === 0) {
    return false;
  }
  for (let index = 0; index < items.length; index++) {
    if (items[index].promptRequestId === promptRequestId) {
      return true;
    }
  }
  return false;
}

export function durableQueueItemById(items: QueueItem[] | null, itemId: string): QueueItem | null {
  if (items === null || itemId.length === 0) {
    return null;
  }
  for (let index = 0; index < items.length; index++) {
    const item = items[index];
    if (item.origin === QueueOrigin.Durable && item.id === itemId) {
      return item;
    }
  }
  return null;
}

/** Authoritative readback confirmation; accepted alone is never success. */
export function queueActionConfirmed(action: QueueItemAction, itemId: string, previousLane: string,
  replacement: string, items: QueueItem[] | null): boolean {
  const updated = durableQueueItemById(items, itemId);
  if (action === QueueItemAction.Edit) {
    return updated !== null && updated.text === replacement;
  }
  if (action === QueueItemAction.Remove) {
    return updated === null;
  }
  if (action === QueueItemAction.Steer) {
    return updated === null || updated.lane !== previousLane;
  }
  return false;
}

/**
 * Keep the action marker of a row whose update RPC is still in flight while a
 * fresh projection supplies its measured text/capabilities.
 */
export function mergeDurableQueue(current: QueueItem[] | null,
  measured: QueueItem[] | null): QueueItem[] | null {
  if (measured === null) {
    return null;
  }
  if (current === null) {
    return measured;
  }
  const inFlight: Map<string, QueueItem> = new Map<string, QueueItem>();
  for (let index = 0; index < current.length; index++) {
    const row = current[index];
    if (row.origin === QueueOrigin.Durable && (row.busy || row.action.length > 0)) {
      inFlight.set(row.id, row);
    }
  }
  if (inFlight.size === 0) {
    return measured;
  }
  const next: QueueItem[] = [];
  for (let index = 0; index < measured.length; index++) {
    const row = measured[index];
    const prior = inFlight.get(row.id);
    if (prior === undefined || row.origin !== QueueOrigin.Durable) {
      next.push(row);
      continue;
    }
    const copy = cloneQueueItem(row);
    copy.busy = prior.busy;
    copy.action = prior.action;
    copy.failure = prior.failure;
    next.push(copy);
  }
  return next;
}

/** Removes at most one still-pending owned prompt request by its rpcId. */
export function retireOwnedQueueRequest(pending: string[],
  promptRequestId: string): PendingQueueRetirement {
  const result = new PendingQueueRetirement();
  if (promptRequestId.length === 0) {
    result.remaining = pending.slice();
    return result;
  }
  const remaining: string[] = [];
  for (let index = 0; index < pending.length; index++) {
    if (!result.removed && pending[index] === promptRequestId) {
      result.removed = true;
      continue;
    }
    remaining.push(pending[index]);
  }
  result.remaining = remaining;
  return result;
}

function cloneQueueItem(item: QueueItem): QueueItem {
  const copy = new QueueItem();
  copy.id = item.id;
  copy.origin = item.origin;
  copy.workerSessionId = item.workerSessionId;
  copy.promptRequestId = item.promptRequestId;
  copy.text = item.text;
  copy.lane = item.lane;
  copy.state = item.state;
  copy.canEdit = item.canEdit;
  copy.canRemove = item.canRemove;
  copy.canSteer = item.canSteer;
  copy.busy = item.busy;
  copy.action = item.action;
  copy.failure = item.failure;
  return copy;
}
