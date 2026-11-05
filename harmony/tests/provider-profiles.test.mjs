// SPDX-License-Identifier: MIT
// Narrow pure-model test: the provider document must migrate the legacy row
// without changing its id/key alias, enforce the same endpoint/key rejection
// rules the host applies, and publish only the phone-model-config v1 fields.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const source = readFileSync(
  new URL('../entry/src/main/ets/model/ProviderProfiles.ets', import.meta.url),
  'utf8',
);
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const {
  PROVIDER_DEFAULT_BASE_URL,
  PROVIDER_DEFAULT_ENCODER,
  PROVIDER_DEFAULT_MODEL,
  ProviderModelRecord,
  ProviderProfileRecord,
  cloneProviderProfiles,
  migrateProviderProfiles,
  providerAliasForId,
  providerCredentialAlias,
  providerEndpointRejection,
  providerKeyRejection,
  providerLaunchDocument,
  providerLaunchSelectedModel,
  providerProfileRejection,
  selectedProviderProfile,
  withDeletedProviderProfile,
  withSelectedProviderProfile,
  withUpsertedProviderProfile,
} = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

const legacy = {
  id: 'deepseek',
  name: 'DeepSeek（本机）',
  baseUrl: 'https://api.deepseek.com/anthropic',
  model: 'deepseek-flash',
  effort: 'high',
  encoder: 'deepseek',
};

// The legacy alias is the one the previous single-provider store used.
assert.equal(providerAliasForId('deepseek'), 'pdsh.model.key.deepseek');
assert.equal(providerAliasForId('  '), 'pdsh.model.key.default');

const migrated = migrateProviderProfiles(legacy, [], '');
assert.equal(migrated.profiles.length, 1);
assert.equal(migrated.selectedId, 'deepseek');
assert.equal(migrated.profiles[0].id, 'deepseek');
assert.equal(migrated.profiles[0].credentialRef, 'pdsh.model.key.deepseek');
assert.equal(providerCredentialAlias(migrated.profiles[0]), 'pdsh.model.key.deepseek');
assert.equal(migrated.profiles[0].baseUrl, PROVIDER_DEFAULT_BASE_URL);
assert.equal(migrated.profiles[0].model, PROVIDER_DEFAULT_MODEL);
assert.equal(migrated.profiles[0].encoder, PROVIDER_DEFAULT_ENCODER);
assert.equal(migrated.profiles[0].models[0].id, 'deepseek-flash');

// An existing document is not merged with the legacy row, and a stale
// selectedId falls back to the first row.
const existing = new ProviderProfileRecord();
existing.id = 'alt';
existing.name = 'Alt';
existing.baseUrl = 'https://example.com/api';
existing.model = 'alt-1';
const kept = migrateProviderProfiles(legacy, [existing], 'missing');
assert.equal(kept.profiles.length, 1);
assert.equal(kept.selectedId, 'alt');
assert.equal(selectedProviderProfile(kept).id, 'alt');

// Endpoint and key rules reject before anything is written.
assert.equal(providerEndpointRejection('https://api.deepseek.com/anthropic'), '');
assert.notEqual(providerEndpointRejection(''), '');
assert.notEqual(providerEndpointRejection('http://api.deepseek.com'), '');
assert.notEqual(providerEndpointRejection('https://api.deepseek.com?x=1'), '');
assert.notEqual(providerEndpointRejection('https://user:pw@api.deepseek.com'), '');
assert.notEqual(providerEndpointRejection('ftp://api.deepseek.com'), '');
assert.equal(providerKeyRejection('abcdefgh'), '');
assert.notEqual(providerKeyRejection('short'), '');
assert.notEqual(providerKeyRejection('abcdefg '), '');
assert.notEqual(providerKeyRejection('abcdefgé'), '');

const blank = new ProviderProfileRecord();
assert.notEqual(providerProfileRejection(blank), '');
blank.id = 'p';
blank.name = 'P';
blank.model = '';
blank.baseUrl = 'https://example.com';
assert.equal(providerProfileRejection(blank), '模型名不能为空');
blank.model = 'm';
assert.equal(providerProfileRejection(blank), '');

// Upsert selects a new row, editing an existing row keeps the selection, and
// delete moves the selection to a remaining row (or clears it).
const first = new ProviderProfileRecord();
first.id = 'one';
first.name = 'One';
first.baseUrl = 'https://one.example.com';
first.model = 'm1';
const afterAdd = withUpsertedProviderProfile(migrated, first, true);
assert.deepEqual(afterAdd.profiles.map((row) => row.id), ['deepseek', 'one']);
assert.equal(afterAdd.selectedId, 'one');

const edited = new ProviderProfileRecord();
edited.id = 'deepseek';
edited.name = 'DeepSeek edited';
edited.baseUrl = 'https://api.deepseek.com/anthropic';
edited.model = 'deepseek-flash';
const afterEdit = withUpsertedProviderProfile(afterAdd, edited, false);
assert.equal(afterEdit.profiles.length, 2);
assert.equal(afterEdit.selectedId, 'one');
assert.equal(afterEdit.profiles[0].name, 'DeepSeek edited');

const selected = withSelectedProviderProfile(afterEdit, 'deepseek');
assert.equal(selected.selectedId, 'deepseek');
assert.equal(withSelectedProviderProfile(afterEdit, 'gone'), null);

const afterDelete = withDeletedProviderProfile(selected, 'deepseek');
assert.deepEqual(afterDelete.profiles.map((row) => row.id), ['one']);
assert.equal(afterDelete.selectedId, 'one');
const emptied = withDeletedProviderProfile(afterDelete, 'one');
assert.deepEqual(emptied.profiles, []);
assert.equal(emptied.selectedId, '');
assert.equal(providerLaunchDocument(emptied), '');

// The published document is exactly the v1 public shape: no encoder, no
// credential ref, no key state and no arbitrary fields.
const rich = new ProviderProfileRecord();
rich.id = 'selected';
rich.name = 'Selected';
rich.baseUrl = 'https://api.deepseek.com/anthropic';
rich.model = 'deepseek-flash';
rich.encoder = 'deepseek';
rich.credentialRef = 'pdsh.model.key.selected';
rich.effort = 'high';
rich.reasoningEffort = 'high';
const model = new ProviderModelRecord();
model.id = 'deepseek-flash';
model.name = 'Flash';
model.contextWindow = 64000;
model.maxTokens = 8192;
model.reasoningEffort = 'high';
rich.models = [model];
const state = withUpsertedProviderProfile(migrated, rich, true);
assert.equal(providerLaunchSelectedModel(state), 'deepseek-flash');
const projected = providerLaunchDocument(state);
assert.ok(projected.length > 0);
assert.ok(!projected.includes('pdsh.model.key'));
assert.ok(!projected.includes('credentialRef'));
assert.ok(!projected.includes('encoder'));
assert.ok(!projected.includes('keyConfigured'));
const json = JSON.parse(projected);
assert.equal(json.version, 1);
assert.equal(json.selectedId, 'selected');
assert.equal(json.providers.length, 2);
const publicRow = json.providers.find((row) => row.id === 'selected');
assert.equal(publicRow.displayName, 'Selected');
assert.equal(publicRow.baseURL, 'https://api.deepseek.com/anthropic');
assert.equal(publicRow.model, 'deepseek-flash');
assert.equal(publicRow.reasoningEffort, 'high');
assert.equal(publicRow.models[0].contextWindow, 64000);
assert.equal(publicRow.models[0].maxTokens, 8192);
assert.equal(publicRow.models[0].textInput, true);
assert.equal(publicRow.models[0].imageInput, false);

// The official Search/Fetch grant is an optional host-private root flag: it is
// omitted when disabled, present only as `true`, and never copied into a
// public provider row. The Rust reader treats absent/false/invalid as disabled.
assert.equal('officialWebTools' in json, false);
const granted = JSON.parse(providerLaunchDocument(state, true));
assert.equal(granted.officialWebTools, true);
assert.equal('officialWebTools' in granted.providers[0], false);
assert.equal('officialWebTools' in granted.providers[1], false);
assert.equal('officialWebTools' in JSON.parse(providerLaunchDocument(state, false)), false);
assert.equal('officialWebTools' in JSON.parse(providerLaunchDocument(state, 'true')), false);

// Optional (zero/empty) model fields are omitted rather than invented.
const plain = new ProviderProfileRecord();
plain.id = 'plain';
plain.name = 'Plain';
plain.baseUrl = 'https://plain.example.com';
plain.model = 'plain-1';
const plainState = withUpsertedProviderProfile(emptied, plain, true);
const plainRow = JSON.parse(providerLaunchDocument(plainState)).providers[0];
assert.equal(plainRow.models[0].id, 'plain-1');
assert.equal('contextWindow' in plainRow.models[0], false);
assert.equal('maxTokens' in plainRow.models[0], false);
assert.equal('name' in plainRow.models[0], false);

// Cloning is deep enough that edits cannot leak into the snapshot.
const clone = cloneProviderProfiles(state.profiles);
clone[0].models[0].contextWindow = 1;
assert.notEqual(state.profiles[0].models[0].contextWindow, 1);

console.log('provider profile cases passed:', state.profiles.length, 'profiles');
