// SPDX-License-Identifier: MIT
/**
 * The `ubuntu` Worker command: one command in a separate local Linux environment.
 *
 * ---- why this is a command and not an accelerator ----
 * `bridge.md` §12 makes `ubuntu.exec` a *host tool*, not a read-only
 * accelerator: it admits a job that writes, so it must not ride exec.md §3's
 * read-only accelerator contract. The Agent still works through `bash`, and the
 * Agent-facing entry is one command in the production table
 * ({@link createWorkerCommands}), next to `rg`, `jq` and `python3`.
 *
 * ---- why the command exists only when the host advertised it ----
 * A host with no local environment must answer `ubuntu: command not found` /
 * 127 — not a timeout and not a silent success. That is design §4.3's rule:
 * "this device does not have that capability" becomes an honest, observable
 * fact. The host advertises by answering the boot probe (`phone ubuntu probe`),
 * which the Worker entry runs before the first command table is built;
 * {@link setUbuntuCapability} publishes that answer and
 * {@link createUbuntuCommand} is registered from it. Nothing here invents a
 * capability string on the channel wire (channel.md §4.1 reserves those) and
 * nothing here joins the accelerator names (exec.md §3 keeps those read-only).
 *
 * ---- the host tool this command drives ----
 * `ubuntu` sends `phone ubuntu <op> ...` through the same host-tool dispatcher
 * every phone command uses. The ops are the bridge.md §12 vocabulary:
 *
 *   `run`    → `ubuntu.exec`: admit one job and answer its record once terminal
 *   `status` → `ubuntu.status`: the current record, or a refusal for an unknown job
 *   `close`  → close the environment (the Agent may stop it when done)
 *
 * The host answers one JSON object on stdout, so this command is the only place
 * that decides how a job is rendered for the model. The rendering is explicit
 * about `backend=mock`: a synthetic 0 must never read as a real compile,
 * because "did it really run?" is the question this lane exists to keep
 * answerable.
 * @module @phone-dsh/dsh-shell-ext/ubuntu
 */

import { defineCommand } from 'just-bash/browser'
import type { CustomCommand } from 'just-bash'
import type { AcceleratorHost } from './bridge.ts'
import { byteStringToBytes, decodeUtf8 } from './encoding.ts'
import { SHELL_MANIFEST_PATH } from './contract.ts'

/** Environment id the command defaults to (bridge.md §12.1's first example). */
export const UBUNTU_DEFAULT_ENV = 'ubuntu-24.04'

/**
 * What the host answered when the Worker asked whether it can run jobs.
 *
 * Every member is a fact the host stated; the Worker infers no environment, no
 * backend and no command list.
 */
export interface UbuntuCapability {
  /** `ubuntu-24.04` today; bridge.md §12.1 keeps the environment a parameter. */
  readonly env: string
  /** `mock` while no real environment is attached, so output can say so. */
  readonly backend: string
  /** Ops the host implements: `run`, `status`, `close` (and `probe`). */
  readonly commands: readonly string[]
}

/**
 * The Agent-facing description.
 *
 * This exact text is what the model reads twice: the boot-published
 * `/dsh/home/AGENTS.md` carries it in its `## Local Ubuntu environment`
 * section, and `ubuntu --help` prints it. One constant, one wording, so the two
 * surfaces cannot drift.
 *
 * It has to carry the cost, because a description that only says "runs
 * commands" invites the Agent to use it as `bash` — which would start an
 * environment for `ls`. It also states the mock plainly: this build's adapter
 * runs no real command, and a synthetic exit 0 must not be reported as a
 * compile.
 */
export const UBUNTU_DESCRIPTION = [
  '`ubuntu` runs **one command in a separate local Linux environment**. It is for tasks that need',
  'a full Linux userland: a toolchain (`gcc`, `make`, ...), a package manager, or a program that',
  'only runs on Linux.',
  '',
  '**It does not run DSH.** The turn, the context, the session and every file stay in DSH.',
  'Ordinary work — `ls`, `cat`, `grep`, `git`, `python3`, reading, editing, searching — runs in the',
  'existing `bash` and must not start this environment.',
  '',
  '**It costs.** Starting the environment takes time, uses disk and battery. **Close it when you',
  'are done** (`ubuntu close`): files inside it are not kept, and the next use starts a fresh',
  'environment and waits again.',
  '',
  // The approval gate is not built yet, so the description must not promise it.
  // A sentence the build cannot honour is the same class of defect as claiming a
  // mock exit code was a real compile.
  '**This build asks for no approval.** The per-session approval gate is part of the design but',
  '**is not implemented yet**, so the environment starts without asking. Do not treat that as',
  'permission to use it casually — the cost rules above still apply.',
  '',
  '```',
  'ubuntu run [--env <id>] [--cwd <path>] [--label <text>] [--timeout-ms <n>] -- <command...>',
  'ubuntu status <jobId>',
  'ubuntu close',
  '```',
  '',
  '**This build is wired to a MOCK adapter.** The job protocol is real; the environment is not.',
  'Its output says `backend=mock` and its exit code is synthetic, so a mock `exit=0` is not a real',
  'compile or run — never present one as if it were.',
].join('\n')

/** Usage alone, for a malformed invocation. */
export const UBUNTU_USAGE = [
  'usage: ubuntu run [--env <id>] [--cwd <path>] [--label <text>] [--timeout-ms <n>] -- <command...>',
  '       ubuntu status <jobId>',
  '       ubuntu close',
].join('\n')

/** The shape the Worker entry publishes and the table reads. */
interface UbuntuGlobal {
  __PDSH_UBUNTU__?: UbuntuCapability | undefined
}

/**
 * Publish the host's answer for this boot.
 *
 * Called by the Worker entry after it probes the host. `undefined` clears it,
 * which is what a host that did not answer leaves behind: no command, so the
 * shell answers `ubuntu: command not found` and exits 127.
 *
 * The answer lives on `globalThis`, not in this module, because the Worker has
 * two module graphs: the entry bundle that runs the probe and the image's
 * plugin bundle that builds the shell table. A module-local value would not
 * cross from one to the other.
 * @param capability - the host's answer, or undefined when it has none.
 */
export function setUbuntuCapability(capability: UbuntuCapability | undefined): void {
  const globals = globalThis as UbuntuGlobal
  if (capability === undefined) {
    delete globals.__PDSH_UBUNTU__
    return
  }
  globals.__PDSH_UBUNTU__ = {
    env: capability.env,
    backend: capability.backend,
    commands: [...capability.commands],
  }
}

/**
 * The host's probe answer for this boot, or undefined when it did not answer.
 * @returns a copy of the published capability, or undefined.
 */
export function getUbuntuCapability(): UbuntuCapability | undefined {
  const published = (globalThis as UbuntuGlobal).__PDSH_UBUNTU__
  if (published === undefined) return undefined
  if (typeof published.env !== 'string' || published.env === '') return undefined
  if (typeof published.backend !== 'string' || published.backend === '') return undefined
  if (!Array.isArray(published.commands)) return undefined
  return {
    env: published.env,
    backend: published.backend,
    commands: published.commands.filter((name): name is string => typeof name === 'string'),
  }
}

/** The JSON object one host-tool op answers on stdout. */
interface UbuntuHostReply {
  readonly backend?: unknown
  readonly env?: unknown
  readonly jobId?: unknown
  readonly state?: unknown
  readonly seq?: unknown
  readonly exitCode?: unknown
  readonly logTail?: unknown
  readonly artifacts?: unknown
  readonly failure?: unknown
  readonly internalAccessObserved?: unknown
  readonly seal?: unknown
  readonly sealMock?: unknown
  readonly egress?: unknown
  readonly authorityExited?: unknown
  readonly limits?: unknown
}

/** One artifact line as the host reports it. */
interface UbuntuArtifactLine {
  readonly name: string
  readonly bytes: number | null
  readonly state: string
}

/** The host's answer to one op. */
interface HostAnswer {
  readonly text: string
  readonly stderr: string
  readonly exitCode: number
}

/** Parse one host answer; undefined when it is not a usable object. */
function decodeReply(text: string): UbuntuHostReply | undefined {
  const trimmed = text.trim()
  if (!trimmed.startsWith('{')) return undefined
  try {
    const parsed: unknown = JSON.parse(trimmed)
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? parsed as UbuntuHostReply
      : undefined
  } catch {
    return undefined
  }
}

/** A string member, or undefined when the host did not send one. */
function stringOf(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined
}

/** A finite non-negative integer member, or undefined. */
function countOf(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : undefined
}

/** Render an exit code as `exit=<n>` / `exit=none`. */
function exitLabel(value: unknown): string {
  return typeof value === 'number' && Number.isInteger(value) ? `exit=${String(value)}` : 'exit=none'
}

/** Format bytes the way the capability manifest does. */
function formatBytes(bytes: number): string {
  if (bytes % (1024 * 1024) === 0) return `${String(bytes / (1024 * 1024))} MiB`
  if (bytes % 1024 === 0) return `${String(bytes / 1024)} KiB`
  return `${String(bytes)} B`
}

/** The artifact rows the host sent, defensively. */
function artifactsOf(reply: UbuntuHostReply): UbuntuArtifactLine[] {
  if (!Array.isArray(reply.artifacts)) return []
  const rows: UbuntuArtifactLine[] = []
  for (const value of reply.artifacts) {
    if (typeof value !== 'object' || value === null) continue
    const record = value as Record<string, unknown>
    const name = stringOf(record.name)
    if (name === undefined) continue
    rows.push({
      name,
      bytes: countOf(record.bytes) ?? null,
      state: stringOf(record.state) ?? 'unknown',
    })
  }
  return rows
}

/** One `{state, reason}` seal member, or undefined. */
function sealOf(reply: UbuntuHostReply): { state: string; reason: string | undefined } | undefined {
  if (typeof reply.seal !== 'object' || reply.seal === null) return undefined
  const record = reply.seal as Record<string, unknown>
  const state = stringOf(record.state)
  if (state === undefined) return undefined
  return { state, reason: stringOf(record.reason) }
}

/** The failure member, or undefined. */
function failureOf(reply: UbuntuHostReply): Record<string, unknown> | undefined {
  return typeof reply.failure === 'object' && reply.failure !== null
    ? reply.failure as Record<string, unknown>
    : undefined
}

/**
 * Render one job answer as text the model can read.
 *
 * The header carries the facts a caller needs to decide whether anything ran
 * and where it ran; the log tail is the command's last lines, never a head
 * (bridge.md §12.8); the artifact list is the manifest, not the bytes (a fetch
 * is a separate call). `backend=mock` is always shown when the host said so,
 * and the seal line says when the seal is the mock's.
 * @param reply - the decoded host answer.
 * @param command - the CLI verb that produced it, for the first line.
 * @returns stdout text; the caller decides the exit code.
 */
export function renderUbuntuReply(reply: UbuntuHostReply, command: 'run' | 'status'): string {
  const state = stringOf(reply.state) ?? 'unknown'
  const backend = stringOf(reply.backend)
  const env = stringOf(reply.env)
  const jobId = stringOf(reply.jobId)
  const lines: string[] = [
    `ubuntu: ${command} job=${jobId ?? 'unknown'} state=${state} ${exitLabel(reply.exitCode)}`
      + `${env === undefined ? '' : ` env=${env}`}`
      + `${backend === undefined ? '' : ` backend=${backend}`}`,
  ]
  const egress = typeof reply.egress === 'boolean' ? ` egress=${String(reply.egress)}` : ''
  const authority = stringOf(reply.authorityExited)
  lines.push(`ubuntu:${egress}${authority === undefined ? '' : ` authorityExited=${authority}`}`)

  const seal = sealOf(reply)
  if (seal !== undefined) {
    const mock = reply.sealMock === true
      ? ' (mock: this adapter seals no workspace and reads no internal root)'
      : ''
    const observed = reply.internalAccessObserved === null || reply.internalAccessObserved === undefined
      ? 'null'
      : String(reply.internalAccessObserved)
    lines.push(
      `ubuntu: seal=${seal.state}${seal.reason === undefined ? '' : ` reason=${seal.reason}`}${mock}`
        + ` internalAccessObserved=${observed}`,
    )
  }

  const failure = failureOf(reply)
  if (failure !== undefined) {
    const code = stringOf(failure.code) ?? 'unknown'
    const message = stringOf(failure.message) ?? ''
    const outcome = stringOf(failure.outcome)
    const cause = stringOf(failure.cause)
    lines.push(
      `ubuntu: failure code=${code}`
        + `${outcome === undefined ? '' : ` outcome=${outcome}`}`
        + `${cause === undefined ? '' : ` cause=${cause}`}`
        + `${message === '' ? '' : `: ${message}`}`,
    )
  }

  const tail = typeof reply.logTail === 'string' ? reply.logTail : ''
  if (tail !== '') {
    lines.push('--- log tail ---')
    lines.push(tail.endsWith('\n') ? tail.slice(0, -1) : tail)
  }

  const artifacts = artifactsOf(reply)
  if (artifacts.length > 0) {
    lines.push(`--- artifacts (${String(artifacts.length)}) ---`)
    for (const artifact of artifacts) {
      lines.push(
        `${artifact.name} ${artifact.bytes === null ? 'unknown' : formatBytes(artifact.bytes)}`
          + ` ${artifact.state}`,
      )
    }
  }

  if (state !== 'succeeded' && state !== 'failed' && state !== 'cancelled' && state !== 'ingestRefused') {
    lines.push(`ubuntu: the job is still ${state}; check it with \`ubuntu status ${jobId ?? '<jobId>'}\``)
  }
  return `${lines.join('\n')}\n`
}

/**
 * The exit status a job answer should surface to the shell.
 *
 * A terminal `succeeded` carries the command's own code. `ingestRefused` is a
 * job that ran but whose artifacts could not all be taken, so it is a non-zero
 * for the caller even when the command exited 0. `failed`/`cancelled` and an
 * unfinished job are non-zero too: an admitted-but-running job is not a
 * success yet.
 * @param reply - the decoded host answer.
 * @returns the shell status.
 */
export function exitOfReply(reply: UbuntuHostReply): number {
  const state = stringOf(reply.state)
  if (state === 'succeeded') {
    return typeof reply.exitCode === 'number' && Number.isInteger(reply.exitCode) ? reply.exitCode : 0
  }
  if (state === 'ingestRefused') return 1
  if (state === 'failed' || state === 'cancelled' || state === 'unknown') return 1
  return 0
}

/** One parsed `ubuntu run` invocation. */
interface RunOptions {
  readonly env: string
  readonly cwd: string | undefined
  readonly label: string | undefined
  readonly timeoutMs: string | undefined
  readonly command: readonly string[]
}

/**
 * Parse `run [flags] -- <command...>`.
 * @param args - argv after the `run` verb.
 * @returns the options, or a string explaining the usage error.
 */
function parseRun(args: readonly string[]): RunOptions | string {
  let env: string | undefined
  let cwd: string | undefined
  let label: string | undefined
  let timeoutMs: string | undefined
  let index = 0
  for (; index < args.length; index += 1) {
    const word = args[index] ?? ''
    if (word === '--') {
      index += 1
      break
    }
    const value = args[index + 1]
    if (word === '--env' || word === '--cwd' || word === '--label' || word === '--timeout-ms') {
      if (value === undefined) return `${word} needs a value`
      index += 1
      if (word === '--env') env = value
      else if (word === '--cwd') cwd = value
      else if (word === '--label') label = value
      else timeoutMs = value
      continue
    }
    return `unknown option ${JSON.stringify(word)}; put the command after \`--\``
  }
  const command = args.slice(index)
  if (command.length === 0) return 'run needs a command after `--`'
  return { env: env ?? UBUNTU_DEFAULT_ENV, cwd, label, timeoutMs, command }
}

/**
 * Build the `ubuntu` command.
 *
 * Registered only when the host answered the boot probe and the table has a
 * host dispatcher; otherwise the name stays unregistered and the shell answers
 * `ubuntu: command not found` with status 127, which is the whole point
 * (design §4.3).
 * @param host - the host dispatcher every phone tool rides.
 * @param capability - the probe answer this boot published.
 * @returns the command definition.
 */
export function createUbuntuCommand(
  host: AcceleratorHost,
  capability: UbuntuCapability,
): CustomCommand {
  return defineCommand('ubuntu', async (args, ctx) => {
    // One host-tool round trip. Every op answers one JSON object on stdout;
    // stderr and the exit code belong to the host tool, never to the job.
    const dispatch = async (argv: readonly string[]): Promise<HostAnswer> => {
      const env: Record<string, string> = ctx.exportedEnv === undefined
        ? Object.fromEntries(ctx.env)
        : { ...ctx.exportedEnv }
      const stdin = ctx.stdin === undefined ? '' : ctx.stdin as unknown as string
      const result = await host.exec({
        argv: ['phone', 'ubuntu', ...argv],
        cwd: ctx.cwd,
        env,
        stdin,
        signal: ctx.signal,
      })
      return {
        text: decodeUtf8(byteStringToBytes(result.stdout)),
        stderr: result.stderr,
        exitCode: result.exitCode,
      }
    }

    /** Render one job answer, or the host refusal that produced none. */
    const render = (answer: HostAnswer, command: 'run' | 'status'): {
      stdout: string
      stderr: string
      exitCode: number
    } => {
      const decoded = decodeReply(answer.text)
      if (answer.exitCode === 127 || decoded === undefined) {
        const detail = answer.stderr.trim() !== '' ? answer.stderr.trim() : answer.text.trim()
        return {
          stdout: '',
          stderr: `ubuntu: this host cannot answer that job (${detail === '' ? 'no usable answer' : detail}); `
            + `it does not provide the local environment this command needs; see ${SHELL_MANIFEST_PATH}\n`,
          exitCode: 127,
        }
      }
      return {
        stdout: renderUbuntuReply(decoded, command),
        stderr: answer.stderr,
        exitCode: answer.exitCode === 0 ? exitOfReply(decoded) : answer.exitCode,
      }
    }

    const verb = args[0]
    if (verb === undefined) {
      return { stdout: `${UBUNTU_DESCRIPTION}\n`, stderr: `${UBUNTU_USAGE}\n`, exitCode: 2 }
    }
    if (verb === '--help' || verb === '-h' || verb === 'help') {
      return { stdout: `${UBUNTU_DESCRIPTION}\n${UBUNTU_USAGE}\n`, stderr: '', exitCode: 0 }
    }

    if (verb === 'run') {
      const parsed = parseRun(args.slice(1))
      if (typeof parsed === 'string') {
        return { stdout: '', stderr: `ubuntu: ${parsed}\n${UBUNTU_USAGE}\n`, exitCode: 2 }
      }
      return render(await dispatch([
        'run',
        '--env', parsed.env,
        ...parsed.cwd === undefined ? [] : ['--cwd', parsed.cwd],
        ...parsed.label === undefined ? [] : ['--label', parsed.label],
        ...parsed.timeoutMs === undefined ? [] : ['--timeout-ms', parsed.timeoutMs],
        '--',
        ...parsed.command,
      ]), 'run')
    }

    if (verb === 'status') {
      const jobId = args[1]
      if (jobId === undefined || jobId === '' || args.length !== 2) {
        return { stdout: '', stderr: `ubuntu: status needs exactly one job id\n${UBUNTU_USAGE}\n`, exitCode: 2 }
      }
      return render(await dispatch(['status', jobId]), 'status')
    }

    if (verb === 'close') {
      if (args.length !== 1) {
        return { stdout: '', stderr: `ubuntu: close takes no arguments\n${UBUNTU_USAGE}\n`, exitCode: 2 }
      }
      const answer = await dispatch(['close'])
      if (answer.exitCode !== 0) {
        const detail = answer.stderr.trim() !== '' ? answer.stderr.trim() : answer.text.trim()
        return {
          stdout: '',
          stderr: `ubuntu: close failed (${detail === '' ? 'no answer' : detail})\n`,
          exitCode: answer.exitCode === 127 ? 127 : 1,
        }
      }
      return {
        stdout: 'ubuntu: closed. Files inside the environment are not kept; the next run starts fresh.\n',
        stderr: answer.stderr,
        exitCode: 0,
      }
    }

    return {
      stdout: '',
      stderr: `ubuntu: unknown command ${JSON.stringify(verb)}\n${UBUNTU_USAGE}\n`,
      exitCode: 2,
    }
  })
}
