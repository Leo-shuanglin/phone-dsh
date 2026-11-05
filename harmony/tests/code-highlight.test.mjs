// SPDX-License-Identifier: MIT
// Focused tests for the pure ArkTS code highlighter.
//
// The expectations are the Android `CodeHighlighter.kt` behaviour, not a
// re-derivation from this port: strings, comments, numbers, then keywords, with
// every later pass overwriting the earlier one. The copy guarantee is checked
// by joining the runs back to the exact UTF-16 source, including trailing
// newlines and surrogate pairs.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const directory = resolve(dirname(fileURLToPath(import.meta.url)), '../entry/src/main/ets/model');

function moduleUrl(path) {
  const text = readFileSync(path, 'utf8');
  const output = stripTypeScriptTypes(text, { mode: 'transform' });
  return 'data:text/javascript;base64,' + Buffer.from(output).toString('base64');
}

const code = await import(moduleUrl(resolve(directory, 'CodeHighlight.ets')));
const markdown = await import(moduleUrl(resolve(directory, 'MarkdownModel.ets')));

function pairs(source) {
  return code.highlightCode(source).map((run) => [run.text, run.ink]);
}

function joined(source) {
  return code.highlightCode(source).map((run) => run.text).join('');
}

test('empty text has no runs and joins back to the empty source', () => {
  assert.deepEqual(code.highlightCode(''), []);
  assert.equal(joined(''), '');
});

test('unrecognised text stays one base run', () => {
  assert.deepEqual(pairs('plain text  '), [['plain text  ', 'base']]);
  assert.deepEqual(pairs('xif if_x _if foo1'), [['xif if_x _if foo1', 'base']]);
});

test('keywords are case-insensitive and split from the surrounding base text', () => {
  assert.deepEqual(pairs('IF If if while'), [
    ['IF', 'keyword'],
    [' ', 'base'],
    ['If', 'keyword'],
    [' ', 'base'],
    ['if', 'keyword'],
    [' ', 'base'],
    ['while', 'keyword'],
  ]);
});

test('string literals match escaped quotes on both quote styles', () => {
  assert.deepEqual(pairs('"a\\"b" \'c\''), [
    ['"a\\"b"', 'string'],
    [' ', 'base'],
    ["'c'", 'string'],
  ]);
});

test('trailing newline stays part of the source', () => {
  assert.deepEqual(pairs('let x = 1\n'), [
    ['let', 'keyword'],
    [' x = ', 'base'],
    ['1', 'number'],
    ['\n', 'base'],
  ]);
  assert.equal(joined('let x = 1\n'), 'let x = 1\n');
});

test('later passes overwrite strings: keyword and number inside a string win', () => {
  assert.deepEqual(pairs('let s = "hello if 42"; // tail if 7\n'), [
    ['let', 'keyword'],
    [' s = ', 'base'],
    ['"hello ', 'string'],
    ['if', 'keyword'],
    [' ', 'string'],
    ['42', 'number'],
    ['"', 'string'],
    ['; ', 'base'],
    ['// tail ', 'comment'],
    ['if', 'keyword'],
    [' ', 'comment'],
    ['7', 'number'],
    ['\n', 'base'],
  ]);
});

test('later passes overwrite the whole block comment but keywords/numbers inside win', () => {
  assert.deepEqual(pairs('/* outer "inner" if 1 */ x'), [
    ['/* outer "inner" ', 'comment'],
    ['if', 'keyword'],
    [' ', 'comment'],
    ['1', 'number'],
    [' */', 'comment'],
    [' x', 'base'],
  ]);
});

test('hash comments do not consume the newline', () => {
  assert.deepEqual(pairs('# if 12\nnext'), [
    ['# ', 'comment'],
    ['if', 'keyword'],
    [' ', 'comment'],
    ['12', 'number'],
    ['\nnext', 'base'],
  ]);
});

test('numbers use the Android pattern, including hex and decimals', () => {
  assert.deepEqual(pairs('x = 0x1f + 42 + 3.5'), [
    ['x = ', 'base'],
    ['0x1f', 'number'],
    [' + ', 'base'],
    ['42', 'number'],
    [' + ', 'base'],
    ['3.5', 'number'],
  ]);
  assert.deepEqual(pairs('0x 0xZZ 1.'), [
    ['0x 0xZZ ', 'base'],
    ['1', 'number'],
    ['.', 'base'],
  ]);
  assert.deepEqual(pairs('1.2.3 -7'), [
    ['1.2', 'number'],
    ['.', 'base'],
    ['3', 'number'],
    [' -', 'base'],
    ['7', 'number'],
  ]);
});

test('unterminated strings and comments keep the complete source as base/comment', () => {
  assert.deepEqual(pairs('"unterminated if'), [
    ['"unterminated ', 'base'],
    ['if', 'keyword'],
  ]);
  assert.deepEqual(pairs('// no newline if'), [
    ['// no newline ', 'comment'],
    ['if', 'keyword'],
  ]);
});

test('UTF-16 surrogate pairs and CRLF are preserved in the copied runs', () => {
  const source = '😀 if\r\nconst s = "x"\r\n';
  assert.equal(joined(source), source);
  assert.ok(code.highlightCode(source).some((run) => run.text.indexOf('😀') >= 0));
  assert.deepEqual(pairs('😀 if'), [
    ['😀 ', 'base'],
    ['if', 'keyword'],
  ]);
});

test('runs are contiguous and never repeat the same ink next to each other', () => {
  const source = 'const a = "x"; // if 9\n/* y */\n';
  const runs = code.highlightCode(source);
  assert.equal(runs.map((run) => run.text).join(''), source);
  for (let index = 0; index < runs.length; index++) {
    assert.ok(runs[index].text.length > 0);
    if (index > 0) {
      assert.notEqual(runs[index].ink, runs[index - 1].ink);
    }
  }
});

test('the parser feeds the exact fenced body to the highlighter', () => {
  const blocks = markdown.parseMarkdownOrPlain('```ts\n// if 42\nconst s = "x";\n```');
  assert.equal(blocks.length, 1);
  const block = blocks[0];
  assert.equal(block.kind, markdown.MarkdownBlockKind.Code);
  assert.equal(block.language, 'ts');
  assert.equal(joined(block.body), block.body);
  assert.deepEqual(pairs(block.body), pairs('// if 42\nconst s = "x";'));
});

test('the parser body ending in a newline still copies character for character', () => {
  const blocks = markdown.parseMarkdownOrPlain('```\nline\n\n```');
  const block = blocks[0];
  assert.equal(block.kind, markdown.MarkdownBlockKind.Code);
  assert.equal(joined(block.body), block.body);
  assert.ok(block.body.endsWith('\n'));
});
