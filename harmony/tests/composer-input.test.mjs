// SPDX-License-Identifier: MIT
// Narrow pure-model test: only real Worker-registered slash commands are
// intercepted or suggested; unknown slash text and absolute paths stay prompts.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const source = readFileSync(new URL('../entry/src/main/ets/model/ComposerInput.ets', import.meta.url), 'utf8');
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const {
  commandsFromCatalog,
  commandSuggestions,
  isRegisteredSlashCommand,
  parseCommandExecution,
} = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

const rows = [
  { name: 'permission', description: 'Switch the permission mode', input: { hint: 'mode' } },
  { name: 'help', description: 'Show help' },
  { name: '', description: 'no name' },
  { name: 'missing-description' },
];

// Array catalog.
const arrayCatalog = commandsFromCatalog(rows);
assert.deepEqual(arrayCatalog.map((row) => row.name), ['permission', 'help']);
assert.equal(arrayCatalog[0].hasInput, true);
assert.equal(arrayCatalog[1].hasInput, false);
assert.equal(commandsFromCatalog(null).length, 0);

// Object catalog: both accepted wrappers, malformed rows skipped, no invention.
const objectCatalog = commandsFromCatalog({ commands: rows });
assert.deepEqual(objectCatalog.map((row) => row.name), ['permission', 'help']);
const valueCatalog = commandsFromCatalog({ value: [{ name: 'status', description: 'Status' }] });
assert.deepEqual(valueCatalog.map((row) => row.name), ['status']);
assert.deepEqual(commandsFromCatalog({ commands: 'nope' }), []);

// Known command: exact name or trailing input.
assert.equal(isRegisteredSlashCommand('/help', false, arrayCatalog), true);
assert.equal(isRegisteredSlashCommand('  /permission workspace-write  ', false, arrayCatalog), true);
// Unknown slash text stays an ordinary prompt.
assert.equal(isRegisteredSlashCommand('/not-registered', false, arrayCatalog), false);
assert.equal(isRegisteredSlashCommand('/', false, arrayCatalog), false);
// Absolute paths are not commands even though they start with '/'.
assert.equal(isRegisteredSlashCommand('/dsh/workspace/notes.txt', false, arrayCatalog), false);
assert.equal(isRegisteredSlashCommand('/help', true, arrayCatalog), false);

// Suggestions come from the actual catalog only.
assert.deepEqual(commandSuggestions('/', arrayCatalog).map((row) => row.name), ['permission', 'help']);
assert.deepEqual(commandSuggestions('/he', arrayCatalog).map((row) => row.name), ['help']);
assert.deepEqual(commandSuggestions('/help now', arrayCatalog), []);
assert.deepEqual(commandSuggestions('/zzz', arrayCatalog), []);

// Rejected execution: missing result/kind and explicit error are not success.
const success = parseCommandExecution({
  commandId: 'cmd-1', result: { kind: 'success', text: 'Permission updated' },
});
assert.equal(success.accepted, true);
assert.equal(success.commandId, 'cmd-1');
assert.equal(success.kind, 'success');
assert.equal(success.text, 'Permission updated');
const rejected = parseCommandExecution({
  commandId: 'cmd-2', result: { kind: 'error', text: 'not accepted' },
});
assert.equal(rejected.accepted, false);
assert.equal(rejected.commandId, 'cmd-2');
assert.equal(rejected.text, 'not accepted');
assert.equal(parseCommandExecution({ commandId: 'cmd-3', result: {} }).accepted, false);
assert.equal(parseCommandExecution({ commandId: 'cmd-4' }).accepted, false);
assert.equal(parseCommandExecution(null).accepted, false);
console.log('composer-input: ok');
