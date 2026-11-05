// SPDX-License-Identifier: MIT
// Shared official DeepSeek Search/Fetch route cases.
//
// The frozen fixture lives at spec/v1/golden/gateway/official-deepseek-web/
// cases.json. The same file drives the Android and Harmony ports; this test
// runs it through the pure ArkTS model-base and target functions the host proxy
// actually calls, so eligibility and canonical-URL refusal cannot drift.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const cases = JSON.parse(readFileSync(
  new URL('../../spec/v1/golden/gateway/official-deepseek-web/cases.json', import.meta.url),
  'utf8',
));
const source = readFileSync(
  new URL('../entry/src/main/ets/host/HostModelAlias.ets', import.meta.url),
  'utf8',
);
const output = stripTypeScriptTypes(source, { mode: 'transform' });
const {
  officialWebModelBaseEligible,
  resolveOfficialWebToolTarget,
} = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

for (const entry of cases.modelBases) {
  assert.equal(
    officialWebModelBaseEligible(entry.url),
    entry.supported,
    `model base ${entry.url}`,
  );
}

const eligibleBase = 'https://api.deepseek.com/v1';
for (const entry of cases.requests) {
  const target = resolveOfficialWebToolTarget(eligibleBase, entry.method, entry.url);
  if (entry.allowed) {
    assert.equal(target, entry.url, `allowed request ${entry.method} ${entry.url}`);
  } else {
    assert.equal(target, '', `refused request ${entry.method} ${entry.url}`);
  }
}

// Every supported model base admits exactly the two canonical POST URLs, and
// every unsupported base admits nothing even with a canonical target.
const canonical = [
  'https://api.deepseek.com/v1/tools/search',
  'https://api.deepseek.com/v1/tools/fetch',
];
for (const entry of cases.modelBases) {
  for (const url of canonical) {
    assert.equal(
      resolveOfficialWebToolTarget(entry.url, 'POST', url),
      entry.supported ? url : '',
      `route via model base ${entry.url}`,
    );
  }
}

// Aliases and alternate spellings are refusals: a different case, an explicit
// port, a trailing slash, a percent-encoded path, query or fragment, and any
// method other than POST. None of them may inherit the model credential.
const refusedTargets = [
  'https://API.DEEPSEEK.COM/v1/tools/search',
  'https://api.deepseek.com:443/v1/tools/search',
  'https://api.deepseek.com/v1/tools/search/',
  'https://api.deepseek.com/v1/tools/%73earch',
  'https://api.deepseek.com/v1/tools/search?x=1',
  'https://api.deepseek.com/v1/tools/search#x',
  'https://api.deepseek.com/v1/tools/search/extra',
  'http://api.deepseek.com/v1/tools/search',
];
for (const url of refusedTargets) {
  assert.equal(resolveOfficialWebToolTarget(eligibleBase, 'POST', url), '', `alias ${url}`);
}
for (const method of ['GET', 'HEAD', 'PUT', 'DELETE', 'PATCH', 'OPTIONS']) {
  assert.equal(
    resolveOfficialWebToolTarget(eligibleBase, method, canonical[0]),
    '',
    `method ${method}`,
  );
}

// An eligible base with an optional trailing slash stays eligible, while an
// unsupported path is a refusal even for a canonical target.
assert.equal(
  resolveOfficialWebToolTarget('https://api.deepseek.com/anthropic/', 'POST', canonical[1]),
  canonical[1],
);
assert.equal(
  resolveOfficialWebToolTarget('https://api.deepseek.com/v2', 'POST', canonical[0]),
  '',
);

console.log(
  'official web cases passed:',
  cases.modelBases.length,
  'model bases,',
  cases.requests.length,
  'requests',
);

const asModule = text => 'data:text/javascript;base64,' + Buffer.from(text).toString('base64');
function hostModule(name, imports = {}) {
  let text = readFileSync(new URL(`../entry/src/main/ets/host/${name}.ets`, import.meta.url), 'utf8');
  for (const [specifier, url] of Object.entries(imports)) {
    text = text.replace(`from '${specifier}'`, `from '${url}'`);
  }
  return asModule(stripTypeScriptTypes(text, { mode: 'transform' }));
}

const remoteKit = asModule(`
  export const outbound = [];
  export const rcp = {
    Request: class {
      constructor(url, method, headers, body) { Object.assign(this, { url, method, headers, body }); }
    },
    createSession({ requestConfiguration }) {
      return {
        async fetch(request) {
          outbound.push({ request, requestConfiguration });
          return { statusCode: 200, headers: { 'content-type': 'application/json' } };
        },
        close() {},
        cancel() {},
      };
    },
  };
`);
const { outbound } = await import(remoteKit);
const { HostHttpProxy } = await import(hostModule('HostHttpProxy', {
  '@kit.NetworkKit': asModule('export const http = {};'),
  '@kit.RemoteCommunicationKit': remoteKit,
  '@kit.BasicServicesKit': asModule('export class BusinessError extends Error {}'),
  '@kit.PerformanceAnalysisKit': asModule('export const hilog = { info() {}, warn() {} };'),
  './HostModelAlias': asModule(output),
  './HostProxyStreamState': hostModule('HostProxyStreamState'),
  './HostUtil': hostModule('HostUtil', {
    '@kit.ArkTS': asModule(`
      export const util = {
        Base64Helper: class { decodeSync(text) { return Buffer.from(text, 'base64'); } },
        TextEncoder: class { encodeInto(text) { return new TextEncoder().encode(text); } },
        TextDecoder: class { decodeToString(bytes) { return new TextDecoder().decode(bytes); } },
      };
    `),
  }),
}));

const proxy = new HostHttpProxy();
const replies = [];
let key = 'fixture-model-key';
let keyReads = 0;
proxy.setRoute({ baseUrl: 'https://api.deepseek.com/anthropic', encoder: 'anthropic', officialWebTools: true });
proxy.setKeyProvider(async () => {
  keyReads++;
  return key;
});
proxy.setResponder({ respond(id, kind, status) { replies.push({ id, kind, status }); } });
async function request(id, method, url) {
  await proxy.handle({
    id, path: '/v1/http',
    headers: {
      'x-pdsh-target-url': url,
      'x-pdsh-target-method': method,
      'authorization': 'Bearer caller-value',
      'x-api-key': 'caller-value',
    },
    bodyBase64: 'e30=',
  });
}
try {
  for (const [id, entry] of cases.requests.entries()) {
    const before = outbound.length;
    await request(id, entry.method, entry.url);
    assert.equal(outbound.length - before, entry.allowed ? 1 : 0, entry.url);
    if (!entry.allowed) continue;
    const { request: sent, requestConfiguration } = outbound.at(-1);
    assert.equal(sent.url, entry.url);
    assert.equal(sent.method, 'POST');
    assert.equal(sent.headers.authorization, 'Bearer fixture-model-key');
    assert.equal(sent.headers['x-api-key'], undefined);
    assert.equal(requestConfiguration.transfer.autoRedirect, false);
  }
  assert.equal(keyReads, 2);
  key = '';
  await request(100, 'POST', canonical[0]);
  assert.equal(outbound.length, 2);
  assert.ok(replies.some(reply => reply.id === 100 && reply.status === 401));
} finally {
  proxy.dispose();
}
