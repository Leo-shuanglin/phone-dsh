// SPDX-License-Identifier: MIT
// Narrow pure-model test: the composer must render only Worker-reported model
// routes/permissions and must carry reasoning effort exactly like Android.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const source = readFileSync(new URL('../entry/src/main/ets/model/SessionControls.ets', import.meta.url), 'utf8');
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const {
  effortLabel,
  modelOptionsFromCatalog,
  permissionFromProjections,
  permissionOptionsFromCatalog,
  permissionTitle,
  resolveModelSelection,
  selectionFromProjections,
} = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

const plain = (selection) => ({
  provider: selection.provider, model: selection.model, effort: selection.effort,
});

const catalog = {
  groups: [
    {
      id: 'deepseek',
      models: [
        {
          id: 'deepseek-chat',
          reasoning: {
            efforts: [{ id: 'high', name: 'High' }, { id: 'max', name: 'Max' }],
            defaultEffort: 'high',
          },
        },
        { id: 'deepseek-reasoner' },
      ],
    },
    {
      id: 'other',
      models: [{ id: 'x', reasoning: { efforts: [{ id: 'low', name: '' }], defaultEffort: 'low' } }],
    },
  ],
};
const options = modelOptionsFromCatalog(catalog);
assert.equal(options.length, 3);
assert.equal(options[0].provider, 'deepseek');
assert.equal(options[0].model, 'deepseek-chat');
assert.deepEqual(options[0].efforts.map((effort) => effort.id), ['high', 'max']);
assert.equal(options[0].efforts[0].name, '高');
assert.equal(options[0].defaultEffort, 'high');
assert.equal(options[1].efforts.length, 0);
assert.equal(options[2].efforts[0].name, '低');

// Missing/malformed groups are skipped, never invented.
assert.deepEqual(modelOptionsFromCatalog(null), []);
assert.deepEqual(modelOptionsFromCatalog({ groups: [{ id: '' }, { id: 'p', models: [{}, { id: 'm' }] }] }).map((row) => row.provider + '/' + row.model), ['p/m']);

const nextProjection = {
  values: {
    modelSelection: {
      next: { provider: 'deepseek', model: 'deepseek-chat', reasoningEffort: 'max' },
      lastUsed: { provider: 'old', model: 'old-model', reasoningEffort: 'low' },
    },
    permissions: { currentValue: 'workspace-write' },
  },
};
assert.deepEqual(plain(selectionFromProjections(nextProjection)), {
  provider: 'deepseek', model: 'deepseek-chat', effort: 'max',
});
const lastUsedProjection = {
  values: {
    modelSelection: { lastUsed: { provider: 'p', model: 'm', reasoningEffort: 'low' } },
    permissions: {},
  },
};
assert.deepEqual(plain(selectionFromProjections(lastUsedProjection)), {
  provider: 'p', model: 'm', effort: 'low',
});
assert.deepEqual(plain(selectionFromProjections({ values: {} })), { provider: '', model: '', effort: '' });
assert.deepEqual(plain(selectionFromProjections(null)), { provider: '', model: '', effort: '' });
assert.equal(permissionFromProjections(nextProjection), 'workspace-write');
assert.equal(permissionFromProjections(null), '');

const permissionCatalog = {
  options: [
    { value: 'workspace-write', name: 'Workspace' },
    { value: 'custom-mode', name: '自定义模式' },
    { value: '', name: 'ignored' },
    { value: 'read-only', name: 'Read only' },
  ],
};
const permissions = permissionOptionsFromCatalog(permissionCatalog);
assert.deepEqual(permissions.map((row) => row.id), ['workspace-write', 'custom-mode', 'read-only']);
assert.equal(permissions[0].title, '工作区内修改');
assert.equal(permissions[1].title, '自定义模式');
assert.equal(permissionTitle('danger-full-access', ''), '完全权限');
assert.equal(permissionTitle('unknown', ''), 'unknown');
assert.deepEqual(permissionOptionsFromCatalog(null), []);
assert.deepEqual(permissionOptionsFromCatalog({ defaultOptions: [{ value: 'auto', name: '' }] }).map((row) => row.id), ['auto']);

const selection = { provider: 'deepseek', model: 'deepseek-chat', effort: 'high' };
// Same model without an explicit effort drops the old effort (Android parity).
assert.deepEqual(plain(resolveModelSelection(options, selection, 'deepseek', 'deepseek-chat', '')), {
  provider: 'deepseek', model: 'deepseek-chat', effort: '',
});
// Switching to a route that offers the current effort carries it over.
const mixed = [
  { provider: 'p', model: 'a', efforts: [{ id: 'high', name: '高' }], defaultEffort: 'high' },
  { provider: 'p', model: 'b', efforts: [{ id: 'high', name: '高' }, { id: 'low', name: '低' }], defaultEffort: 'high' },
];
assert.deepEqual(plain(resolveModelSelection(mixed, selection, 'p', 'b', '')), {
  provider: 'p', model: 'b', effort: 'high',
});
// An explicit effort must exist on the route.
assert.equal(resolveModelSelection(mixed, selection, 'p', 'a', 'low'), null);
assert.deepEqual(plain(resolveModelSelection(mixed, selection, 'p', 'a', 'high')), {
  provider: 'p', model: 'a', effort: 'high',
});
// Provider fallback mirrors Android's model-only fallback.
assert.deepEqual(plain(resolveModelSelection(mixed, selection, 'elsewhere', 'b', '')), {
  provider: 'p', model: 'b', effort: 'high',
});
// Unknown model never invents a selection.
assert.equal(resolveModelSelection(options, selection, 'deepseek', 'missing', ''), null);
assert.equal(resolveModelSelection([], selection, 'deepseek', 'deepseek-chat', ''), null);

assert.equal(effortLabel('off', 'Off'), '关');
assert.equal(effortLabel('xhigh', 'XHigh'), '最高');
assert.equal(effortLabel('custom', '自定义'), '自定义');
assert.equal(effortLabel('custom', ''), 'custom');

console.log('session-controls: all assertions passed');
