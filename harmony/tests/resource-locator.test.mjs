// SPDX-License-Identifier: MIT
// Pure regressions for ResourceLocator: canonical Android URI/session policy,
// remote redirect caps, byte chunks and the frozen Worker binary limits.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE = resolve(ROOT, 'entry/src/main/ets/model/ResourceLocator.ets');
const sourceText = readFileSync(SOURCE, 'utf8');
const output = stripTypeScriptTypes(sourceText, { mode: 'transform' });
const locator = await import('data:text/javascript;base64,' + Buffer.from(output).toString('base64'));

function context(current = 'w-1', known = ['w-1'], workspace = true) {
  const value = new locator.ResourceContext();
  value.currentWorkerSessionId = current;
  value.knownWorkerSessionIds = known;
  value.hasVerifiedWorkspace = workspace;
  return value;
}

test('workspace file image resolves to the Worker mount', () => {
  const resolved = locator.resolveMarkdownResource('file:///dsh/workspace/img/a.png', context());
  assert.equal(resolved.kind, locator.ResourceKind.Workspace);
  assert.equal(resolved.target, '/dsh/workspace/img/a.png');
  assert.equal(resolved.relativePath, 'img/a.png');
});

test('file URIs outside /dsh/workspace are refused as absolute', () => {
  const resolved = locator.resolveMarkdownResource('file:///etc/passwd', context());
  assert.equal(resolved.kind, locator.ResourceKind.Refused);
  assert.equal(resolved.reason, locator.ResourceRefusal.AbsoluteResource);
});

test('sandbox and plain relative paths resolve; traversal is refused', () => {
  assert.equal(locator.resolveMarkdownResource('sandbox:/dsh/workspace/a.png', context()).target,
    '/dsh/workspace/a.png');
  assert.equal(locator.resolveMarkdownResource('sandbox:a.png', context()).target,
    '/dsh/workspace/a.png');
  assert.equal(locator.resolveMarkdownResource('img/a.png', context()).target,
    '/dsh/workspace/img/a.png');
  assert.equal(locator.resolveMarkdownResource('../../etc/passwd', context()).reason,
    locator.ResourceRefusal.UnsafePath);
  assert.equal(locator.resolveMarkdownResource('/etc/passwd', context()).reason,
    locator.ResourceRefusal.AbsoluteResource);
});

test('dsh-resource session identity never falls back to the active workspace', () => {
  const current = locator.resolveMarkdownResource(
    'dsh-resource://file/session/w-1/dsh/workspace/a.png', context('w-1', ['w-1']));
  assert.equal(current.kind, locator.ResourceKind.Session);
  assert.equal(current.target, '/dsh/workspace/a.png');

  const different = locator.resolveMarkdownResource(
    'dsh-resource://file/session/w-2/dsh/workspace/a.png', context('w-1', ['w-1']));
  assert.equal(different.reason, locator.ResourceRefusal.DifferentSession);

  const unknown = locator.resolveMarkdownResource(
    'dsh-resource://file/session/w-9/dsh/workspace/a.png', context('w-1', ['w-1']));
  assert.equal(unknown.reason, locator.ResourceRefusal.DifferentSession);

  const noSession = locator.resolveMarkdownResource(
    'dsh-resource://file/session/w-1/a.png', context('', []));
  assert.equal(noSession.reason, locator.ResourceRefusal.NoCurrentSession);

  const absolute = locator.resolveMarkdownResource(
    'dsh-resource://file/absolute/etc/passwd', context());
  assert.equal(absolute.reason, locator.ResourceRefusal.AbsoluteResource);

  const traversal = locator.resolveMarkdownResource(
    'dsh-resource://file/session/w-1/../../etc/passwd', context('w-1', ['w-1']));
  assert.equal(traversal.reason, locator.ResourceRefusal.UnsafePath);

  const noWorkspace = locator.resolveMarkdownResource(
    'dsh-resource://file/session/w-1/dsh/workspace/a.png', context('w-1', ['w-1'], false));
  assert.equal(noWorkspace.reason, locator.ResourceRefusal.NoVerifiedWorkspace);
});

test('remote URI policy rejects credentials and non-HTTP schemes', () => {
  assert.equal(locator.resolveMarkdownResource('https://example.test/a.png', context()).kind,
    locator.ResourceKind.Remote);
  assert.equal(locator.resolveMarkdownResource('https://user:pw@example.test/a.png', context()).reason,
    locator.ResourceRefusal.Invalid);
  assert.equal(locator.resolveMarkdownResource('ftp://example.test/a.png', context()).reason,
    locator.ResourceRefusal.UnsupportedScheme);
  assert.equal(locator.isValidRemoteUri('https://example.test/a.png'), true);
  assert.equal(locator.isValidRemoteUri('https://user@example.test/a.png'), false);
});

test('each redirect hop is revalidated and capped', () => {
  assert.equal(locator.nextRemoteRedirect('https://a.test/x/a.png', '/y/b.png'), 'https://a.test/y/b.png');
  assert.equal(locator.nextRemoteRedirect('https://a.test/x/a.png', 'b.png'), 'https://a.test/x/b.png');
  assert.equal(locator.nextRemoteRedirect('https://a.test/x/a.png', 'file:///etc/passwd'), '');
  assert.equal(locator.nextRemoteRedirect('https://a.test/x/a.png', 'https://u:p@a.test/b.png'), '');
  assert.equal(locator.MAX_REMOTE_REDIRECTS, 3);
  assert.equal(locator.MAX_REMOTE_IMAGE_BYTES, 8 * 1024 * 1024);
  assert.equal(locator.MAX_DECODED_IMAGE_DIMENSION, 2048);
});

test('decode size mirrors Android sampleMarkdownImageSize', () => {
  assert.equal(locator.sampleMarkdownImageSize(4096, 2048), 2);
  assert.equal(locator.sampleMarkdownImageSize(100, 100), 1);
  assert.deepEqual({ ...locator.boundedDecodeSize(4096, 2048) }, { width: 2048, height: 1024 });
  assert.deepEqual({ ...locator.boundedDecodeSize(0, 0) }, { width: 1, height: 1 });
});

test('byte chunks use charCodeAt & 255, not UTF-8 re-encoding', () => {
  const raw = String.fromCharCode(0x00, 0x41, 0x80, 0xff, 0x2028);
  const bytes = locator.latin1ByteStringToBytes(raw);
  assert.deepEqual([...bytes], [0x00, 0x41, 0x80, 0xff, 0x28]);
  assert.notEqual(new TextEncoder().encode(raw).length, bytes.length);

  const all = new Uint8Array(256);
  for (let index = 0; index < 256; index++) all[index] = index;
  const base64 = Buffer.from(all).toString('base64');
  assert.deepEqual([...locator.decodeBase64(base64)], [...all]);
  assert.deepEqual([...locator.decodeBase64('')], []);

  const joined = locator.concatBytes([new Uint8Array([1, 2]), new Uint8Array([3, 4])], 4);
  assert.deepEqual([...joined], [1, 2, 3, 4]);
});

test('frozen Worker limits and commands are present', () => {
  assert.equal(locator.MAX_BINARY_SNAPSHOT_BYTES, 16 * 1024 * 1024);
  assert.equal(locator.MAX_BINARY_READERS, 4);
  assert.equal(locator.BINARY_SNAPSHOT_TTL_MS, 60000);
  assert.equal(locator.BINARY_CHUNK_BYTES, 256 * 1024);
  const worker = readFileSync(resolve(ROOT, 'web/product-worker.mjs'), 'utf8');
  for (const marker of ['$openBinaryRead', '$readBinaryChunk', '$closeBinaryRead',
    'MAX_BINARY_SNAPSHOT_BYTES = 16 * 1024 * 1024', 'BINARY_CHUNK_BYTES = 256 * 1024',
    'BINARY_SNAPSHOT_TTL_MS = 60 * 1000', 'MAX_BINARY_READERS = 4',
    'binaryReads.delete(id)']) {
    assert.ok(worker.includes(marker), 'worker is missing ' + marker);
  }
});

test('SDK declares the actual redirect/body/image APIs used', () => {
  const sdk = process.env.PDSH_HARMONY_SDK ||
    resolve(process.env.DEVECO_CLI_CLT_PATH || 'missing-harmony-sdk', 'sdk/default/openharmony/ets');
  const httpPath = resolve(sdk, 'api/@ohos.net.http.d.ts');
  const imagePath = resolve(sdk, 'api/@ohos.multimedia.image.d.ts');
  if (!existsSync(httpPath) || !existsSync(imagePath)) {
    test.diagnostic?.('Harmony SDK declarations not present; run with PDSH_HARMONY_SDK');
    return;
  }
  const http = readFileSync(httpPath, 'utf8');
  const image = readFileSync(imagePath, 'utf8');
  for (const marker of ['maxRedirects?: number;', 'maxLimit?: number;', 'responseCode: ResponseCode | number;',
    'ARRAY_BUFFER']) {
    assert.ok(http.includes(marker), 'SDK http is missing ' + marker);
  }
  for (const marker of ['function createImageSource(buf: ArrayBuffer): ImageSource;',
    'createPixelMap(options?: DecodingOptions): Promise<PixelMap>', 'release(): Promise<void>;']) {
    assert.ok(image.includes(marker), 'SDK image is missing ' + marker);
  }
});
