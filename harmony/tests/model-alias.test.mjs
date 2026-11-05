// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
const source = readFileSync(new URL('../entry/src/main/ets/host/HostModelAlias.ets', import.meta.url), 'utf8');
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const { parseNativeModelOrigin, providerModelPathAdmitted, resolveNativeModelAlias } = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));


const origin = parseNativeModelOrigin('http://127.0.0.1:45678');
assert.notEqual(origin, null);
if (origin === null) throw new Error('origin');

const cases = [
  ['/v1/messages', 'https://api.example.com/v1/messages', 'https://api.example.com'],
  ['/v1/messages?beta=1', 'https://api.example.com/v1/messages?beta=1', 'https://api.example.com'],
  ['/v1/chat/completions', 'https://api.example.com/chat/completions', 'https://api.example.com'],
  ['/chat/completions', 'https://api.example.com/chat/completions', 'https://api.example.com'],
  ['/v1/completions', 'https://api.example.com/completions', 'https://api.example.com'],
  ['/v1/responses', 'https://api.example.com/responses', 'https://api.example.com'],
  ['/v1/responses/resp_1', 'https://api.example.com/responses/resp_1', 'https://api.example.com'],
  ['/responses', 'https://api.example.com/responses', 'https://api.example.com'],
  ['/v1/models/deepseek-chat', 'https://api.example.com/v1/models/deepseek-chat', 'https://api.example.com'],
  ['/v1/models?x=1', 'https://api.example.com/v1/models?x=1', 'https://api.example.com'],
  ['/v1/files/file-1', 'https://api.example.com/v1/files/file-1', 'https://api.example.com'],
  ['/models/deepseek-chat', 'https://api.example.com/models/deepseek-chat', 'https://api.example.com'],
  ['/v1/messages', 'https://api.example.com/anthropic/v1/messages', 'https://api.example.com/anthropic'],
  ['/v1/chat/completions', 'https://api.example.com/anthropic/chat/completions', 'https://api.example.com/anthropic'],
  ['/v1/responses/x', 'https://api.example.com/anthropic/responses/x', 'https://api.example.com/anthropic'],
  ['/v1/models/x', 'https://api.example.com/anthropic/v1/models/x', 'https://api.example.com/anthropic'],
  ['/models/x', 'https://api.example.com/models/x', 'https://api.example.com/anthropic'],
  ['/v1/messages', 'https://api.example.com/v1/messages', 'https://api.example.com/v1'],
  ['/v1/chat/completions', 'https://api.example.com/v1/chat/completions', 'https://api.example.com/v1'],
  ['/v1/responses/x', 'https://api.example.com/v1/responses/x', 'https://api.example.com/v1'],
];
for (const [path, expected, provider] of cases) {
  const got = resolveNativeModelAlias(origin, `http://127.0.0.1:45678${path}`, provider);
  assert.equal(got.matched, true, `matched ${path} @ ${provider}`);
  assert.equal(got.target, expected, `target ${path} @ ${provider}`);
}

const rejected = [
  'http://127.0.0.1:45679/v1/messages',
  'http://localhost:45678/v1/messages',
  'http://127.0.0.2:45678/v1/messages',
  'https://127.0.0.1:45678/v1/messages',
  'http://127.0.0.1:45678/api/messages',
  'http://127.0.0.1:45678/assets/index.js',
  'http://127.0.0.1:45678/v1/http',
  'http://127.0.0.1:45678/v1/models/%2e%2e/secret',
  'http://127.0.0.1:45678/v1/models/../secret',
  'http://127.0.0.1:45678/v1/messages/extra',
  'http://127.0.0.1:45678/v1/models-mcp',
  'http://127.0.0.1:45678/chat/completions/extra',
];
for (const target of rejected) {
  const got = resolveNativeModelAlias(origin, target, 'https://api.example.com');
  assert.equal(got.matched, false, `rejected ${target}`);
}

for (const badProvider of ['http://api.example.com', 'https://api.example.com?x=1', 'https://user:pw@api.example.com', '']) {
  const got = resolveNativeModelAlias(origin, 'http://127.0.0.1:45678/v1/messages', badProvider);
  assert.equal(got.matched, true, `bad provider matched ${badProvider}`);
  assert.equal(got.target, '', `bad provider target ${badProvider}`);
}

assert.equal(parseNativeModelOrigin('http://127.0.0.1:45678/v1'), null);
assert.equal(parseNativeModelOrigin('https://127.0.0.1:45678'), null);
assert.equal(parseNativeModelOrigin('http://10.0.0.1:45678'), null);
assert.equal(parseNativeModelOrigin('http://127.0.0.1:45678?x=1'), null);

assert.equal(providerModelPathAdmitted('https://api.example.com', '/v1/messages'), true);
assert.equal(providerModelPathAdmitted('https://api.example.com', '/chat/completions'), true);
assert.equal(providerModelPathAdmitted('https://api.example.com', '/v1/models/deepseek'), true);
assert.equal(providerModelPathAdmitted('https://api.example.com', '/api/messages'), false);
assert.equal(providerModelPathAdmitted('https://api.example.com', '/v1/messages/extra'), false);
assert.equal(providerModelPathAdmitted('https://api.example.com/anthropic', '/models/x'), true);
assert.equal(providerModelPathAdmitted('https://api.example.com/anthropic', '/anthropic/models'), false);

console.log('alias URI cases passed:', cases.length, 'rejections:', rejected.length);
