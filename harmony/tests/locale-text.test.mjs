// SPDX-License-Identifier: MIT
// Guards the static-copy contract: every literal that UI code sends through
// Copy.tr / Copy.fill must have an English pair, while unknown or dynamic
// strings (user text, model output, host data) fall back unchanged.
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const localeSource = readFileSync(
  new URL('../entry/src/main/ets/model/LocaleText.ets', import.meta.url), 'utf8'
);
const compiled = stripTypeScriptTypes(localeSource, { mode: 'strip' });
const { LocaleText } = await import(
  'data:text/javascript;base64,' + Buffer.from(compiled).toString('base64')
);

const uiDir = new URL('../entry/src/main/ets/ui/', import.meta.url);
const uiFiles = readdirSync(uiDir).filter((name) => name.endsWith('.ets'));
const literalPattern = /Copy\.(?:tr|fill)\(\s*(['"])((?:[^'"\\]|\\.)*)\1/g;
const literals = new Set();
for (const file of uiFiles) {
  const source = readFileSync(new URL(file, uiDir), 'utf8');
  let match;
  while ((match = literalPattern.exec(source)) !== null) {
    literals.add(match[2].replace(/\\(['"\\])/g, '$1'));
  }
}

assert.ok(literals.size > 0, "UI literal collection must not be empty");
const missing = [...literals].filter((text) => !LocaleText.hasEnglish(text));
assert.deepEqual(missing, [], `wrapped static literals without English: ${JSON.stringify(missing)}`);

// Wrapped literals must resolve to the mapped English, never to the Chinese
// source (except intentional identity pairs such as 中文 / English).
for (const text of literals) {
  const english = LocaleText.tr(LocaleText.EN, text);
  assert.equal(typeof english, 'string');
  assert.ok(english.length > 0, `empty English for ${JSON.stringify(text)}`);
  assert.equal(LocaleText.tr(LocaleText.ZH, text), text);
}

// Unknown strings are not rewritten: this is the safety net for host data,
// user-provided values and model output, including dynamic host reasons.
const unknownUserData = '我的会话 /private/tmp/机密 文件.md';
assert.equal(LocaleText.tr(LocaleText.EN, unknownUserData), unknownUserData);
assert.equal(LocaleText.tr(LocaleText.ZH, unknownUserData), unknownUserData);
const dynamicHostReason = '只读设备信息：Pixel 9 · Google · API 36';
assert.equal(LocaleText.tr(LocaleText.EN, dynamicHostReason), dynamicHostReason);
assert.equal(LocaleText.hasEnglish(dynamicHostReason), false);

// Template values are filled verbatim and never translated.
assert.equal(LocaleText.fill(LocaleText.EN, '{0} 个会话', ['我的会话']), '我的会话 sessions');
assert.equal(LocaleText.fill(LocaleText.ZH, '{0} 个会话', ['我的会话']), '我的会话 个会话');
assert.equal(LocaleText.fill(LocaleText.EN, '未知模板 {0}', ['值']), '未知模板 值');
