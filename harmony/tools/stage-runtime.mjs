#!/usr/bin/env node
// SPDX-License-Identifier: MIT
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import { existsSync, readFileSync, writeFileSync, mkdirSync, cpSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { parseArgs } from 'node:util'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(resolve(root, 'plugins/package.json'))
const { values } = parseArgs({ options: { bundle: { type: 'string', default: resolve(root, 'examples/probe-app/Bundle/worker') } } })
const bundle = resolve(values.bundle)
const record = JSON.parse(readFileSync(join(bundle, 'probe-bundle.json'), 'utf8'))
const checkout = process.env.PDSH_DSH_CHECKOUT || record.image?.checkout
if (!checkout || !existsSync(join(checkout, 'packages/experimental/webworker-runtime/src/client/client.ts'))) throw new Error('Set PDSH_DSH_CHECKOUT to the pinned DSH source checkout')
const commit = execFileSync('git', ['-C', checkout, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim()
if (commit !== record.image?.commit) throw new Error('Runtime client source and Worker image commit differ')
const directory = mkdtempSync(join(tmpdir(), 'pdsh-harmony-runtime-'))
const output = resolve(root, 'harmony/entry/src/main/resources/rawfile')
mkdirSync(output, { recursive: true })
try {
  cpSync(bundle, join(directory, 'worker'), { recursive: true, filter: path => !path.endsWith('.map') && path !== join(bundle, 'web') && path !== join(bundle, 'probe-bundle.json') })
  cpSync(resolve(root, 'harmony/web'), join(directory, 'app'), { recursive: true })
  require('esbuild').buildSync({
    entryPoints: [join(checkout, 'packages/experimental/webworker-runtime/src/client/client.ts')],
    outfile: join(directory, 'app/tunnel-client.mjs'), bundle: true, platform: 'browser', format: 'esm', logLevel: 'silent',
  })
  const identity = {
    dshCommit: commit,
    workerSha256: createHash('sha256').update(readFileSync(join(bundle, 'worker-entry.js'))).digest('hex'),
    imageSha256: createHash('sha256').update(readFileSync(join(bundle, 'image.tar.gz'))).digest('hex'),
  }
  writeFileSync(join(directory, 'runtime-identity.json'), JSON.stringify(identity, null, 2) + '\n')
  const zipPath = join(output, 'runtime.zip')
  if (existsSync(zipPath)) rmSync(zipPath)
  execFileSync('/usr/bin/zip', ['-q', '-r', zipPath, 'worker', 'app', 'runtime-identity.json'], { cwd: directory })
  console.log(JSON.stringify({ output: zipPath, ...identity }))
} finally { rmSync(directory, { recursive: true, force: true }) }
