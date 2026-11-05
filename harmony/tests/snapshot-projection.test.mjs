// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
const directory = resolve(dirname(fileURLToPath(import.meta.url)), '../entry/src/main/ets/model');
const cache = new Map();
function moduleUrl(path) {
  if (cache.has(path)) return cache.get(path);
  let text = readFileSync(path, 'utf8');
  text = text.replace(/from\s+(['"])(\.[^'"]+)\1/g, (_all, quote, target) =>
    'from ' + quote + moduleUrl(resolve(dirname(path), target + '.ets')) + quote);
  const output = stripTypeScriptTypes(text, { mode: 'transform' });
  const url = 'data:text/javascript;base64,' + Buffer.from(output).toString('base64');
  cache.set(path, url);
  return url;
}
const contracts = await import(moduleUrl(resolve(directory, 'Contracts.ets')));
const { publishSnapshot, detachedSettings } = await import(moduleUrl(resolve(directory, 'SnapshotProjection.ets')));
const { ShellSnapshot, SessionRecord, ChatRow, Settings, FontRole, fontSizeValue, setFontSize } = contracts;
const { SessionModelOption, SessionPermissionOption } = await import(moduleUrl(resolve(directory, 'SessionControls.ets')));
const { SessionStatsDTO, TurnOutcome } = await import(moduleUrl(resolve(directory, 'ChatMetrics.ets')));
function state() {
  const value = new ShellSnapshot();
  const model = new SessionModelOption();
  model.provider = 'deepseek'; model.model = 'deepseek-flash';
  value.modelOptions = [model];
  value.modelSelection.provider = model.provider; value.modelSelection.model = model.model;
  value.modelError = 'catalog warning';
  const permission = new SessionPermissionOption(); permission.id = 'read-only'; permission.title = '仅可查看';
  value.permissionOptions = [permission]; value.currentPermission = permission.id;
  value.permissionError = 'permission warning';
  const session = new SessionRecord(); session.id = 'chat-1'; session.workerSessionId = 'worker-1';
  value.sessions = [session]; value.activeSessionId = session.id;
  const row = new ChatRow(); row.text = '设置'; value.rows = [row];
  value.settings.fontSize = 18; value.settings.codeFontSize = 15; value.settings.terminalFontSize = 17;
  return value;
}
test('available model and current-session permission survive UI publication', () => {
  const source = state(); let view;
  publishSnapshot(source, next => { view = next; });
  assert.equal(view.modelOptions[0].model, 'deepseek-flash');
  assert.equal(view.modelSelection.provider, 'deepseek');
  assert.equal(view.currentPermission, 'read-only');
  assert.equal(view.permissionOptions[0].title, '仅可查看');
  assert.equal(view.modelError, 'catalog warning');
  assert.equal(view.permissionError, 'permission warning');
});
test('the UI cannot mutate the host session, catalog, settings, or user text', () => {
  const source = state(); let view;
  publishSnapshot(source, next => { view = next; });
  view.sessions[0].workerSessionId = 'UI-change'; view.modelOptions.pop();
  view.settings.fontSize = 10; view.rows[0].text = 'Settings';
  assert.equal(source.sessions[0].workerSessionId, 'worker-1');
  assert.equal(source.modelOptions.length, 1); assert.equal(source.settings.fontSize, 18);
  assert.equal(source.rows[0].text, '设置');
});
test('an asynchronous session update remains attached to the canonical state', () => {
  const source = state(); const session = source.sessions[0]; const views = [];
  publishSnapshot(source, next => { views.push(next); });
  session.workerSessionId = 'created-after-first-publication';
  publishSnapshot(source, next => { views.push(next); });
  assert.equal(source.sessions[0], session);
  assert.equal(views[0].sessions[0].workerSessionId, 'worker-1');
  assert.equal(views[1].sessions[0].workerSessionId, 'created-after-first-publication');
});
test('the full projection preserves every data field without a separate field whitelist', () => {
  const source = state(); let view;
  publishSnapshot(source, next => { view = next; });
  assert.deepEqual(JSON.parse(JSON.stringify(view)), JSON.parse(JSON.stringify(source)));
  function requireDataOnly(value) {
    if (value === null || typeof value !== 'object') return;
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== Array.prototype) {
      assert.deepEqual(Object.getOwnPropertyNames(prototype).filter(key => key !== 'constructor'), [],
        'Snapshot DTOs contain data. Put behavior in a typed helper.');
    }
    for (const child of Object.values(value)) requireDataOnly(child);
  }
  requireDataOnly(source);
});
test('font helpers work after detached settings and preserve legacy defaults', () => {
  const legacy = new Settings(); delete legacy.codeFontSize; delete legacy.terminalFontSize;
  const view = detachedSettings(legacy);
  assert.equal(fontSizeValue(view, FontRole.Code), 14);
  assert.equal(fontSizeValue(view, FontRole.Terminal), 13);
  setFontSize(view, FontRole.Text, 22);
  assert.equal(fontSizeValue(view, FontRole.Text), 22);
  assert.equal(legacy.fontSize, 16);
});
test('performance preference and Worker metrics detach from canonical host state', () => {
  const source = state();
  source.settings.performanceUsage = 'compact';
  const stats = new SessionStatsDTO();
  stats.turns = 7; stats.steps = 9; stats.decodeMs = 1000; stats.decodeTokens = 263;
  source.sessionStats = stats;
  source.rows[0].turnId = 'turn-1';
  source.rows[0].turnOutcome = TurnOutcome.Completed;
  source.rows[0].turnStartedAt = 1000;
  source.rows[0].turnEndedAt = 2000;
  let view;
  publishSnapshot(source, next => { view = next; });
  assert.equal(view.settings.performanceUsage, 'compact');
  assert.equal(view.sessionStats.turns, 7);
  assert.equal(view.rows[0].turnId, 'turn-1');
  assert.equal(view.rows[0].turnEndedAt, 2000);
  view.settings.performanceUsage = 'detailed';
  view.sessionStats.turns = 0;
  view.rows[0].turnEndedAt = 0;
  assert.equal(source.settings.performanceUsage, 'compact');
  assert.equal(source.sessionStats.turns, 7);
  assert.equal(source.rows[0].turnEndedAt, 2000);
});
