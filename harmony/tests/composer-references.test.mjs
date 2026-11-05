// SPDX-License-Identifier: MIT
// Narrow pure-model test for the composer `@` chooser. Ported semantics:
// Android triggerToken/referenceMenu caps, workspace relative paths, other-chat
// titles, and whole-draft replacement ("<path> " / "@<title> ").
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const source = readFileSync(
  new URL('../entry/src/main/ets/model/ComposerReferences.ets', import.meta.url), 'utf8'
);
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const {
  REFERENCE_DIRECTORY_LIMIT,
  REFERENCE_FILE_LIMIT,
  REFERENCE_FILE_MENU_LIMIT,
  REFERENCE_SESSION_LIMIT,
  REFERENCE_SESSION_MENU_LIMIT,
  ComposerReferenceSessionFact,
  referenceMenuForDraft,
  referenceMenuFromToken,
  referenceRelativePath,
  referenceSelection,
  referenceSessionTitles,
  referenceToken,
} = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

// Android `CHAT_MENTION_FILE_LIMIT`, `CHAT_MENTION_SESSION_LIMIT`, `MAX_COMMANDS`,
// `MAX_SESSIONS`, plus the host-side bounded tree walk.
assert.equal(REFERENCE_FILE_LIMIT, 200);
assert.equal(REFERENCE_SESSION_LIMIT, 100);
assert.equal(REFERENCE_FILE_MENU_LIMIT, 6);
assert.equal(REFERENCE_SESSION_MENU_LIMIT, 3);
assert.ok(REFERENCE_DIRECTORY_LIMIT > 0);

// Android triggerToken: trimmed '@' prefix, no whitespace after it.
assert.equal(referenceToken('@'), '');
assert.equal(referenceToken('@foo'), 'foo');
assert.equal(referenceToken('  @foo  '), 'foo');
assert.equal(referenceToken('@a/b.md'), 'a/b.md');
assert.equal(referenceToken('@foo\n'), 'foo');
assert.equal(referenceToken(''), null);
assert.equal(referenceToken('hello'), null);
assert.equal(referenceToken('hello @foo'), null);
assert.equal(referenceToken('@foo bar'), null);
assert.equal(referenceToken('@f\to'), null);
assert.equal(referenceToken('@\u3000x'), null);

// Workspace-relative paths only; the root itself and outside paths are dropped.
assert.equal(referenceRelativePath('/dsh/workspace', '/dsh/workspace/a.md'), 'a.md');
assert.equal(referenceRelativePath('/dsh/workspace', '/dsh/workspace/dir/a.md'), 'dir/a.md');
assert.equal(referenceRelativePath('/dsh/workspace/', '/dsh/workspace/a.md'), 'a.md');
assert.equal(referenceRelativePath('/dsh/workspace', '/dsh/workspace'), '');
assert.equal(referenceRelativePath('/dsh/workspace', '/dsh/workspaceX/a.md'), '');
assert.equal(referenceRelativePath('/dsh/workspace', '/tmp/a.md'), '');
assert.equal(referenceRelativePath('', '/dsh/workspace/a.md'), '');

// Other chats: non-archived, not the active one, trimmed, distinct, capped.
const facts = [
  { id: 'active', title: 'Current', archived: false },
  { id: 'a', title: '  Alpha  ', archived: false },
  { id: 'b', title: 'Alpha', archived: false },
  { id: 'c', title: '   ', archived: false },
  { id: 'd', title: 'Archived', archived: true },
  { id: 'e', title: 'Beta', archived: false },
];
assert.deepEqual(referenceSessionTitles(facts, 'active'), ['Alpha', 'Beta']);
assert.deepEqual(referenceSessionTitles(facts, ''), ['Current', 'Alpha', 'Beta']);
assert.deepEqual(referenceSessionTitles(facts, 'active', 1), ['Alpha']);
const many = [];
for (let index = 0; index < 130; index++) {
  many.push({ id: 'id' + index, title: 'title' + index, archived: false });
}
assert.equal(referenceSessionTitles(many, '').length, 100);
assert.equal(referenceSessionTitles(many, '')[99], 'title99');

const files = [];
for (let index = 0; index < 10; index++) {
  files.push('src/file' + index + '.ts');
}
const sessions = ['Alpha chat', 'Beta chat', 'Gamma chat', 'Alpha other'];

// Empty token: Android shows the first files only (cap 6), never sessions.
const emptyMenu = referenceMenuFromToken('', files, sessions);
assert.equal(emptyMenu.open, true);
assert.equal(emptyMenu.token, '');
assert.deepEqual(emptyMenu.files, files.slice(0, 6));
assert.deepEqual(emptyMenu.sessions, []);

// Typed token: case-insensitive substring match, file cap 6, session cap 3.
const alphaMenu = referenceMenuFromToken('ALPHA', ['a/Alpha.md', 'b/alpha.ts', 'c/other.md'], sessions);
assert.deepEqual(alphaMenu.files, ['a/Alpha.md', 'b/alpha.ts']);
assert.deepEqual(alphaMenu.sessions, ['Alpha chat', 'Alpha other']);
const sessionMenu = referenceMenuFromToken('chat', [], sessions);
assert.deepEqual(sessionMenu.sessions, ['Alpha chat', 'Beta chat', 'Gamma chat']);

// A non-trigger draft has no open menu at all.
assert.equal(referenceMenuForDraft('hello', files, sessions).open, false);
assert.equal(referenceMenuForDraft('@fil', files, sessions).open, true);
assert.deepEqual(referenceMenuForDraft('@fil', files, sessions).files, files.slice(0, 6));

// Android whole-draft replacement: files are plain paths, conversations get @.
assert.equal(referenceSelection('src/a.md', ['Alpha chat']), 'src/a.md ');
assert.equal(referenceSelection('Alpha chat', ['Alpha chat']), '@Alpha chat ');
// Android checks session membership first, so an identical value uses @ too.
assert.equal(referenceSelection('src/a.md', ['src/a.md']), '@src/a.md ');

// The fact class exists so HostService can map durable records without making
// the pure model import Contracts.
const fact = new ComposerReferenceSessionFact();
fact.id = 'x';
fact.title = '  X  ';
fact.archived = false;
assert.deepEqual(referenceSessionTitles([fact], ''), ['X']);
