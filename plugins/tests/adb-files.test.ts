// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { InMemoryFs, Bash } from 'just-bash'
import { createAdbCommand } from '../shell-ext/src/adb.ts'
import type { AcceleratorRequest } from '../shell-ext/src/bridge.ts'

test('adb pull commits exact binary bytes and cleans a failed partial transfer', async () => {
  for (const success of [true, false]) {
    const fs = new InMemoryFs({ '/dsh/workspace/result': 'original' })
    const data = Buffer.from([0, 255, 128, 10, 13])
    let request: AcceleratorRequest | undefined
    const bash = new Bash({ fs, cwd: '/dsh/workspace', customCommands: [createAdbCommand({ names: ['adb'], async exec(req) {
      request = req
      const rows = [{ type: 'file', path: '', name: 'remote', size: data.length }, { type: 'data', data: data.toString('base64') },
        ...success ? [{ type: 'end', path: '' }] : []]
      const body = Buffer.from(rows.map(r => JSON.stringify(r) + '\n').join(''))
      for (let i = 0; i < body.length; i += 7) await req.onStdout!(body.subarray(i, i + 7))
      return { stdout: '', stderr: success ? '' : 'connection lost', exitCode: success ? 0 : 1 }
    } })] })
    const result = await bash.exec('adb pull /sdcard/remote result')
    assert.equal(result.exitCode, success ? 0 : 1, result.stderr)
    assert.deepEqual(request?.argv, ['adb', '--pdsh-v1', 'pull', '/dsh/workspace/result', '/sdcard/remote'])
    assert.deepEqual(Buffer.from(await fs.readFileBuffer('/dsh/workspace/result')), success ? data : Buffer.from('original'))
    assert.deepEqual(await fs.readdir('/dsh/workspace'), ['result'])
  }
})

test('adb push normalizes source and rejects unsupported/unsafe operands before dispatch', async () => {
  const fs = new InMemoryFs({ '/dsh/workspace/a file': 'hello' })
  const requests: AcceleratorRequest[] = []
  const bash = new Bash({ fs, cwd: '/dsh/workspace', customCommands: [createAdbCommand({ names: ['adb'], async exec(req) {
    requests.push(req); return { stdout: '', stderr: '', exitCode: 0 }
  } })] })
  assert.equal((await bash.exec('adb push "a file" /sdcard/test')).exitCode, 0)
  assert.deepEqual(requests[0]?.argv, ['adb', '--pdsh-v1', 'push', '/dsh/workspace/a file', '/sdcard/test'])
  for (const command of ['adb push . /sdcard/dir', 'adb pull relative result', 'adb pull /sdcard/file /etc/out', 'adb pull -a /sdcard/file out']) {
    assert.notEqual((await bash.exec(command)).exitCode, 0, command)
  }
  assert.equal(requests.length, 1)
})

test('adb defaults downloads to the 1 GiB native disk store and can upload them without VFS reads', async () => {
  const requests: AcceleratorRequest[] = []
  const bash = new Bash({ cwd: '/dsh/workspace', customCommands: [createAdbCommand({ names: ['adb'], async exec(req) {
    requests.push(req)
    assert.equal(req.onStdout, undefined, 'disk transfer data must not enter the in-memory VFS receiver')
    return { stdout: 'Transferred 1 GiB', stderr: '', exitCode: 0 }
  } })] })
  assert.equal((await bash.exec('adb pull /sdcard/large.apk')).exitCode, 0)
  assert.deepEqual(requests[0]?.argv, ['adb', '--pdsh-v1', 'pull', '/dsh/transfers/large.apk', '/sdcard/large.apk'])
  assert.equal((await bash.exec('adb push /dsh/transfers/large.apk /sdcard/copy.apk')).exitCode, 0)
  assert.equal(requests.length, 2)
})

test('adb stat returns native running/saved status without starting another transfer', async () => {
  const requests: AcceleratorRequest[] = []
  const bash = new Bash({ cwd: '/dsh/workspace', customCommands: [createAdbCommand({ names: ['adb'], async exec(req) {
    requests.push(req)
    return { stdout: '{"status":"saved","size":173254137}\n', stderr: '', exitCode: 0 }
  } })] })
  const result = await bash.exec('adb stat /dsh/transfers/app.apk')
  assert.equal(result.exitCode, 0)
  assert.equal(JSON.parse(result.stdout).size, 173254137)
  assert.deepEqual(requests[0]?.argv, ['adb', '--pdsh-v1', 'stat', '/dsh/transfers/app.apk'])
  assert.notEqual((await bash.exec('adb stat /etc/file')).exitCode, 0)
  assert.equal(requests.length, 1)
})
