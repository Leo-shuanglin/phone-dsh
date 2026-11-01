// SPDX-License-Identifier: MIT
// L51: the `ubuntu` command in the Worker's production table.
//
// What these tests pin, and why each one is a fact the Agent depends on:
//
//   1. **Absent unless advertised.** With no boot-probe answer there is no
//      `ubuntu` command, and the shell answers 127 rather than a timeout or a
//      silent success (design §4.3).
//   2. **Present when the probe answered.** The command is one ordinary entry
//      next to `rg`/`jq`, and `which` reports it.
//   3. **The description carries the cost.** The text the model reads is the
//      manifest section and `ubuntu --help`; both must say what it costs and
//      that this build's backend is a mock.
//   4. **The job renderer is honest.** `backend=mock`, the exit code, the log
//      tail and the mock seal are all visible, and a mock `succeeded` keeps the
//      job's own exit code.
//   5. **The host call shape is the contract.** `run` sends
//      `phone ubuntu run --env ... -- <argv...>` with the command unparsed, so
//      no shell re-splits it.
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { VfsFileSystem, WorkerShell } from '../src/index.ts'
import type { AcceleratorHost, AcceleratorRequest } from '../src/bridge.ts'
import { createWorkerCommands } from '../src/commands.ts'
import {
  UBUNTU_DESCRIPTION,
  UBUNTU_USAGE,
  exitOfReply,
  getUbuntuCapability,
  renderUbuntuReply,
  setUbuntuCapability,
} from '../src/ubuntu.ts'
import type { UbuntuCapability } from '../src/ubuntu.ts'

/** vfs cwd every invocation runs in. */
const CWD = '/dsh/workspace'

/** The capability a host with a mock environment answers with. */
const CAPABILITY: UbuntuCapability = {
  env: 'ubuntu-24.04',
  backend: 'mock',
  commands: ['run', 'status', 'close'],
}

/** One mock job record as the host tool answers it on stdout. */
const JOB_REPLY = JSON.stringify({
  backend: 'mock',
  env: 'ubuntu-24.04',
  jobId: 'uj-abc',
  state: 'succeeded',
  seq: 4,
  exitCode: 0,
  logTail: 'mock ubuntu-24.04: uj-abc\n',
  artifacts: [{ name: 'tiny.txt', bytes: 12, state: 'available' }],
  failure: null,
  internalAccessObserved: false,
  seal: { state: 'restored', reason: null },
  sealMock: true,
  egress: false,
  authorityExited: 'worker-vfs',
})

const roots: string[] = []

/** A disposable VFS + shell over an explicit command table. */
async function fixture(options: {
  readonly withUbuntu: boolean
  readonly host?: AcceleratorHost
}): Promise<{ shell: WorkerShell; calls: AcceleratorRequest[] }> {
  const root = mkdtempSync(join(tmpdir(), 'pdsh-shell-ubuntu-'))
  roots.push(root)
  const fs = new VfsFileSystem({ root })
  await fs.mkdir(CWD, { recursive: true })
  const calls: AcceleratorRequest[] = []
  const host: AcceleratorHost = options.host ?? {
    names: ['phone'],
    async exec(request) {
      calls.push(request)
      return { stdout: Buffer.from(`${JOB_REPLY}\n`, 'utf8').toString('latin1'), stderr: '', exitCode: 0 }
    },
  }
  const commands = createWorkerCommands({
    accelerators: host,
    git: false,
    curl: false,
    gzip: false,
    toolbox: false,
    ...options.withUbuntu ? { ubuntu: CAPABILITY } : { ubuntu: false },
  })
  return { shell: new WorkerShell({ fs, commands }), calls }
}

after(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true })
})

test('with no probe answer the command does not exist: exit 127, not a timeout', async () => {
  setUbuntuCapability(undefined)
  const { shell } = await fixture({ withUbuntu: false })
  const result = await shell.run(['bash', '-c', `ubuntu run -- echo hi`], { cwd: CWD })
  assert.equal(result.exitCode, 127)
  assert.match(result.stderr, /ubuntu: (command not found|not found)/iu)
})

test('a host probe answer registers the command and `which` reports it', async () => {
  const { shell } = await fixture({ withUbuntu: true })
  const which = await shell.run(['bash', '-c', 'command -v ubuntu || which ubuntu'], { cwd: CWD })
  assert.equal(which.exitCode, 0)
  assert.match(which.stdout, /ubuntu/u)
})

test('the description states the cost and the mock backend', () => {
  // The Agent reads the manifest section and `--help`; both are this constant.
  // Match across the render's line wraps, which are not part of the facts.
  const text = UBUNTU_DESCRIPTION.replace(/\s+/gu, ' ')
  for (const fact of [
    /separate local Linux environment/u,
    /does not run DSH/u,
    /takes time, uses disk and battery/u,
    // The approval gate is not built yet, so the description must say so rather
    // than promise it. Flip this back when the gate lands.
    /This build asks for no approval/u,
    /Close it when you are/u,
    /must not start this environment/u,
    /MOCK adapter/u,
  ]) {
    assert.match(text, fact)
  }
})

test('ubuntu --help prints the description and usage, exit 0', async () => {
  const { shell } = await fixture({ withUbuntu: true })
  const result = await shell.run(['ubuntu', '--help'], { cwd: CWD })
  assert.equal(result.exitCode, 0)
  assert.equal(result.stderr, '')
  assert.equal(result.stdout, `${UBUNTU_DESCRIPTION}\n${UBUNTU_USAGE}\n`)
})

test('ubuntu with no arguments prints the description but is a usage error', async () => {
  const { shell } = await fixture({ withUbuntu: true })
  const result = await shell.run(['ubuntu'], { cwd: CWD })
  assert.equal(result.exitCode, 2)
  assert.equal(result.stdout, `${UBUNTU_DESCRIPTION}\n`)
  assert.equal(result.stderr, `${UBUNTU_USAGE}\n`)
})

test('run sends the command unparsed after -- and renders the job', async () => {
  const { shell, calls } = await fixture({ withUbuntu: true })
  const result = await shell.run(
    ['ubuntu', 'run', '--cwd', '/dsh/workspace', '--label', 'fib', '--', 'gcc', '-o', 'a', 'a.c'],
    { cwd: CWD },
  )
  assert.equal(result.exitCode, 0, result.stderr)
  const argv = calls[0]?.argv ?? []
  assert.deepEqual(
    [...argv],
    ['phone', 'ubuntu', 'run', '--env', 'ubuntu-24.04', '--cwd', '/dsh/workspace', '--label', 'fib', '--', 'gcc', '-o', 'a', 'a.c'],
  )
  assert.match(result.stdout, /ubuntu: run job=uj-abc state=succeeded exit=0 env=ubuntu-24\.04 backend=mock/u)
  assert.match(result.stdout, /ubuntu: egress=false authorityExited=worker-vfs/u)
  assert.match(result.stdout, /seal=restored.*mock: this adapter seals no workspace.*internalAccessObserved=false/u)
  assert.match(result.stdout, /--- log tail ---\nmock ubuntu-24\.04: uj-abc/u)
  assert.match(result.stdout, /tiny\.txt 12 B available/u)
})

test('run requires the -- separator and a command', async () => {
  const { shell, calls } = await fixture({ withUbuntu: true })
  const missing = await shell.run(['ubuntu', 'run', 'gcc', '-o', 'a', 'a.c'], { cwd: CWD })
  assert.equal(missing.exitCode, 2)
  assert.match(missing.stderr, /put the command after `--`/u)
  const noCommand = await shell.run(['ubuntu', 'run', '--'], { cwd: CWD })
  assert.equal(noCommand.exitCode, 2)
  assert.match(noCommand.stderr, /needs a command after `--`/u)
  assert.equal(calls.length, 0)
})

test('status sends exactly the job id and renders the record', async () => {
  const { shell, calls } = await fixture({ withUbuntu: true })
  const result = await shell.run(['ubuntu', 'status', 'uj-abc'], { cwd: CWD })
  assert.equal(result.exitCode, 0)
  assert.deepEqual([...(calls[0]?.argv ?? [])], ['phone', 'ubuntu', 'status', 'uj-abc'])
  assert.match(result.stdout, /ubuntu: status job=uj-abc/u)
  const bad = await shell.run(['ubuntu', 'status'], { cwd: CWD })
  assert.equal(bad.exitCode, 2)
  assert.match(bad.stderr, /status needs exactly one job id/u)
})

test('close reports that files are not kept', async () => {
  const { shell, calls } = await fixture({ withUbuntu: true })
  const result = await shell.run(['ubuntu', 'close'], { cwd: CWD })
  assert.equal(result.exitCode, 0)
  assert.deepEqual([...(calls[0]?.argv ?? [])], ['phone', 'ubuntu', 'close'])
  assert.match(result.stdout, /closed\. Files inside the environment are not kept/u)
})

test('a host refusal is 127 with the reason, not a fake success', async () => {
  const { shell } = await fixture({
    withUbuntu: true,
    host: {
      names: ['phone'],
      async exec() {
        return {
          stdout: '',
          stderr: 'phone: ubuntu: this host does not provide that phone tool; nothing executed\n',
          exitCode: 127,
        }
      },
    },
  })
  const result = await shell.run(['ubuntu', 'run', '--', 'true'], { cwd: CWD })
  assert.equal(result.exitCode, 127)
  assert.match(result.stderr, /this host cannot answer that job/u)
  assert.match(result.stderr, /does not provide the local environment/u)
})

test('an unknown job is non-zero and its open window is rendered null', () => {
  const text = renderUbuntuReply({
    state: 'unknown',
    jobId: 'uj-missing',
    internalAccessObserved: null,
    seal: { state: 'pending' },
  }, 'status')
  assert.match(text, /state=unknown/u)
  assert.match(text, /seal=pending internalAccessObserved=null/u)
  assert.equal(exitOfReply({ state: 'unknown' }), 1)
  assert.equal(exitOfReply({ state: 'succeeded', exitCode: 0 }), 0)
  assert.equal(exitOfReply({ state: 'succeeded', exitCode: 3 }), 3)
})

test('a mock ingestRefused job is non-zero even when the command exited 0', () => {
  assert.equal(
    exitOfReply({ state: 'ingestRefused', exitCode: 0 }),
    1,
  )
  assert.match(
    renderUbuntuReply({
      state: 'ingestRefused',
      exitCode: 0,
      artifacts: [{ name: 'huge.bin', bytes: 20 * 1024 * 1024, state: 'refused' }],
    }, 'run'),
    /huge\.bin 20 MiB refused/u,
  )
})

test('the published capability is what the table reads and is cleared again', async () => {
  try {
    setUbuntuCapability(CAPABILITY)
    assert.deepEqual(getUbuntuCapability(), CAPABILITY)
    const { shell } = await fixture({ withUbuntu: true })
    const result = await shell.run(['ubuntu', '--help'], { cwd: CWD })
    assert.equal(result.exitCode, 0)
  } finally {
    setUbuntuCapability(undefined)
  }
  assert.equal(getUbuntuCapability(), undefined)
  // A table built after the answer was cleared has no command again.
  const { shell } = await fixture({ withUbuntu: false })
  const result = await shell.run(['bash', '-c', 'ubuntu'], { cwd: CWD })
  assert.equal(result.exitCode, 127)
})
