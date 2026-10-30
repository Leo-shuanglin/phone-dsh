// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { VfsFileSystem, WorkerShell } from '../shell-ext/src/index.ts'
import { createWorkerCommands } from '../shell-ext/src/commands.ts'
import type { AcceleratorHost, AcceleratorRequest } from '../shell-ext/src/bridge.ts'
import { SCPReceiver } from '../shell-ext/src/scp.ts'

const roots: string[] = []
after(() => { for (const root of roots) rmSync(root, { recursive: true, force: true }) })
async function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'pdsh-scp-')); roots.push(root)
  const fs = new VfsFileSystem({ root })
  await fs.mkdir('/dsh/workspace', { recursive: true })
  return fs
}
function records(values: object[]): Uint8Array { return Buffer.from(values.map(value => JSON.stringify(value) + '\n').join('')) }
function file(path: string, name: string, data: Uint8Array) {
  return [{ type: 'file', path, name, size: data.length }, { type: 'data', data: Buffer.from(data).toString('base64') }, { type: 'end', path }]
}

test('scp downloads binary directory records through shell and VFS, including split UTF-8 chunks', async () => {
  const fs = await fixture()
  const data = new Uint8Array([0, 255, 128, 10, 13, 1])
  const body = records([{ type: 'directory', path: '', name: '下载' }, ...file('data.bin', 'data.bin', data), ...file('empty', 'empty', new Uint8Array())])
  let called: AcceleratorRequest | undefined
  const host: AcceleratorHost = { names: ['scp'], async exec(request) {
    called = request
    for (let index = 0; index < body.length; index += 7) await request.onStdout!(body.slice(index, index + 7))
    return { stdout: '', stderr: '', exitCode: 0 }
  } }
  const shell = new WorkerShell({ fs, commands: createWorkerCommands({ accelerators: host, git: false, toolbox: false }) })
  const result = await shell.run(['bash', '-c', 'scp -r -P 2222 demo:/source .'], { cwd: '/dsh/workspace', env: {} })
  assert.equal(result.exitCode, 0, result.stderr)
  assert.deepEqual(called?.argv, ['scp', '--pdsh-v1', 'download', 'recursive', 'demo', '2222', '/source'])
  assert.deepEqual(await fs.readFileBuffer('/dsh/workspace/下载/data.bin'), data)
  assert.equal((await fs.stat('/dsh/workspace/下载/empty')).size, 0)
})

test('scp uploads the normalized materialized path and refuses remote-to-remote', async () => {
  const fs = await fixture()
  let requests: AcceleratorRequest[] = []
  const host: AcceleratorHost = { names: ['scp'], async exec(request) { requests.push(request); return { stdout: '', stderr: '', exitCode: 0 } } }
  const shell = new WorkerShell({ fs, commands: createWorkerCommands({ accelerators: host, git: false, toolbox: false }) })
  assert.equal((await shell.run(['bash', '-c', 'scp "a file" demo:/tmp/file'], { cwd: '/dsh/workspace', env: {} })).exitCode, 0)
  assert.deepEqual(requests[0]?.argv, ['scp', '--pdsh-v1', 'upload', 'file', 'demo', 'default', '/dsh/workspace/a file', '/tmp/file'])
  assert.notEqual((await shell.run(['bash', '-c', 'scp demo:a other:b'], { cwd: '/dsh/workspace', env: {} })).exitCode, 0)
  assert.equal(requests.length, 1)
})

test('interrupted download preserves existing target and removes its partial file', async () => {
  const fs = await fixture()
  await fs.writeFile('/dsh/workspace/target', 'original')
  const host: AcceleratorHost = { names: ['scp'], async exec(request) {
    await request.onStdout!(records([{ type: 'file', path: '', name: 'remote', size: 5 }, { type: 'data', data: 'AQI=' }]))
    return { stdout: '', stderr: 'connection lost', exitCode: 1 }
  } }
  const shell = new WorkerShell({ fs, commands: createWorkerCommands({ accelerators: host, git: false, toolbox: false }) })
  const result = await shell.run(['bash', '-c', 'scp demo:file target'], { cwd: '/dsh/workspace', env: {} })
  assert.equal(result.exitCode, 1)
  assert.equal(await fs.readFile('/dsh/workspace/target'), 'original')
  assert.deepEqual(await fs.readdir('/dsh/workspace'), ['target'])
})

test('receiver refuses traversal, forged sizes, duplicate roots and partial success', async () => {
  for (const values of [
    [{ type: 'file', path: '../escape', name: 'escape', size: 0 }],
    [{ type: 'file', path: '', name: '..', size: 0 }],
    [{ type: 'file', path: '', name: 'x', size: 1 }, { type: 'data', data: 'AQI=' }],
    [{ type: 'directory', path: '', name: 'x' }, { type: 'directory', path: '', name: 'x' }],
  ]) {
    const fs = await fixture()
    const receiver = new SCPReceiver(fs, '/dsh/workspace/result', true)
    await assert.rejects(receiver.accept(records(values)))
    await receiver.cleanup()
  }
  const fs = await fixture()
  const receiver = new SCPReceiver(fs, '/dsh/workspace/result', false)
  await receiver.accept(records([{ type: 'file', path: '', name: 'x', size: 1 }]))
  assert.throws(() => receiver.finish(), /incomplete/)
  await receiver.cleanup()
  assert.deepEqual(await fs.readdir('/dsh/workspace'), [])
})
