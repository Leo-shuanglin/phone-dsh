// SPDX-License-Identifier: MIT
import { Buffer } from 'node:buffer'
import { posix } from 'node:path'
import { defineCommand } from 'just-bash/browser'
import type { IFileSystem } from 'just-bash/browser'
import type { AcceleratorHost } from './bridge.ts'

const usage = 'Usage: scp [-r] [-P port] local connection:remote\n       scp [-r] [-P port] connection:remote local\nConnections are saved in Settings → SSH; list with ssh --list. SFTP transport; no wildcards, remote-to-remote copies or legacy -O.\n'

function remote(value: string): { connection: string; path: string } | undefined {
  // A slash before the colon denotes a local path. Brackets allow IPv6 hosts.
  const match = /^([^/:]+@\[[^\]]+\]|\[[^\]]+\]|[^/:]+):(.*)$/s.exec(value)
  return match === null ? undefined : { connection: match[1]!, path: match[2] || '.' }
}

function localPath(cwd: string, value: string): string {
  const path = posix.resolve(cwd, value)
  if (!['/dsh/workspace', '/dsh/home'].some(root => path === root || path.startsWith(`${root}/`))) {
    throw new Error('local files must be in /dsh/workspace or /dsh/home')
  }
  if (path.includes('\0')) throw new Error('invalid local path')
  return path
}

/** Reject links in every existing ancestor, including the destination itself. */
async function noLinks(fs: IFileSystem, path: string): Promise<void> {
  let current = '/'
  for (const part of path.split('/').filter(Boolean)) {
    current = posix.join(current, part)
    if (await fs.exists(current) && (await fs.lstat(current)).isSymbolicLink) throw new Error('symbolic links are not supported')
  }
}

function name(value: unknown): string {
  if (typeof value !== 'string' || value === '' || value === '.' || value === '..' || /[/\\\0]/.test(value)) throw new Error('invalid remote filename')
  return value
}

/** Bounded NDJSON receiver. Only a complete file is renamed into place. */
export class SCPReceiver {
  private pending = ''
  private root: string | undefined
  private directories = new Set<string>()
  private paths = new Set<string>()
  private active: { temp: string; target: string; relative: string; size: number; bytes: number } | undefined
  private decoder = new TextDecoder('utf-8', { fatal: true })
  private entries = 0
  private bytes = 0
  files = 0
  private fs: IFileSystem
  private destination: string
  private recursive: boolean
  constructor(fs: IFileSystem, destination: string, recursive: boolean) {
    this.fs = fs; this.destination = destination; this.recursive = recursive
  }

  async accept(bytes: Uint8Array): Promise<void> {
    this.pending += this.decoder.decode(bytes, { stream: true })
    let newline: number
    while ((newline = this.pending.indexOf('\n')) >= 0) {
      if (newline > 65536) throw new Error('oversized transfer record')
      const line = this.pending.slice(0, newline)
      this.pending = this.pending.slice(newline + 1)
      await this.record(JSON.parse(line) as Record<string, unknown>)
    }
    if (this.pending.length > 65536) throw new Error('oversized transfer record')
  }

  private async record(record: Record<string, unknown>): Promise<void> {
    if (record.type === 'data') {
      const file = this.active
      if (file === undefined || typeof record.data !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(record.data)) throw new Error('invalid transfer data')
      const bytes = new Uint8Array(Buffer.from(record.data, 'base64'))
      if (file.bytes + bytes.length > file.size) throw new Error('remote file exceeds declared size')
      await this.fs.appendFile(file.temp, bytes)
      file.bytes += bytes.length
      return
    }
    if (record.type === 'end') {
      const file = this.active
      if (file === undefined || record.path !== file.relative || file.bytes !== file.size) throw new Error('incomplete remote file')
      await noLinks(this.fs, file.target)
      await this.fs.mv(file.temp, file.target)
      this.active = undefined
      this.files++
      return
    }
    if (this.active !== undefined || !['directory', 'file'].includes(String(record.type))) throw new Error('invalid transfer sequence')
    if (typeof record.path !== 'string' || record.path.startsWith('/') || record.path.split('/').length > 33) throw new Error('invalid remote path')
    const relative = record.path
    if (relative !== '') relative.split('/').forEach(name)
    const basename = name(record.name)
    if (this.paths.has(relative) || ++this.entries > 1000) throw new Error('duplicate path or too many entries')
    this.paths.add(relative)
    if (this.root === undefined) {
      if (relative !== '') throw new Error('missing transfer root')
      await noLinks(this.fs, this.destination)
      this.root = await this.fs.exists(this.destination) && (await this.fs.stat(this.destination)).isDirectory
        ? posix.join(this.destination, basename) : this.destination
    } else if (relative === '' || !this.directories.has(posix.dirname(relative) === '.' ? '' : posix.dirname(relative))) {
      throw new Error('missing parent directory')
    }
    const target = relative === '' ? this.root : posix.join(this.root, relative)
    await noLinks(this.fs, target)
    if (record.type === 'directory') {
      if (!this.recursive) throw new Error('source is a directory; use scp -r')
      await this.fs.mkdir(target, { recursive: true })
      this.directories.add(relative)
    } else {
      if (!Number.isSafeInteger(record.size) || (record.size as number) < 0 || this.bytes + (record.size as number) > 128 * 1024 * 1024) throw new Error('invalid or oversized remote file')
      if (await this.fs.exists(target) && (await this.fs.stat(target)).isDirectory) throw new Error('destination is a directory')
      this.bytes += record.size as number
      const temp = posix.join(posix.dirname(target), `.scp-${globalThis.crypto.randomUUID()}.partial`)
      this.active = { temp, target, relative, size: record.size as number, bytes: 0 }
      await this.fs.writeFile(temp, new Uint8Array())
    }
  }

  finish(): void {
    this.pending += this.decoder.decode()
    if (this.pending !== '' || this.active !== undefined || this.root === undefined) throw new Error('incomplete SCP transfer')
  }

  async cleanup(): Promise<void> {
    if (this.active !== undefined) await this.fs.rm(this.active.temp, { force: true }).catch(() => {})
  }
}

/** scp's local side always uses the Worker VFS, preserving WAL ordering. */
export function createScpCommand(host: AcceleratorHost) {
  return defineCommand('scp', async (args, ctx) => {
    let receiver: SCPReceiver | undefined
    try {
      if (args.length === 1 && args[0] === '--help') return { stdout: usage, stderr: '', exitCode: 0 }
      let recursive = false
      let port = 'default'
      let index = 0
      while (index < args.length && args[index]!.startsWith('-')) {
        const flag = args[index++]
        if (flag === '--') break
        if (flag === '-r') recursive = true
        else if (flag === '-P' && index < args.length && /^\d+$/.test(args[index]!)) {
          port = args[index++]!
          if (+port < 1 || +port > 65535) throw new Error('invalid port')
        } else throw new Error(`unsupported option ${flag}; ${usage}`)
      }
      if (args.length - index !== 2) throw new Error(usage)
      const source = args[index]!, destination = args[index + 1]!
      const from = remote(source), to = remote(destination)
      if ((from === undefined) === (to === undefined)) throw new Error('exactly one operand must be connection:remote-path')
      const endpoint = (from ?? to)!
      const local = localPath(ctx.cwd, from === undefined ? source : destination)
      await noLinks(ctx.fs, local)
      if (from !== undefined) receiver = new SCPReceiver(ctx.fs, local, recursive)
      const result = await host.exec({
        argv: ['scp', '--pdsh-v1', from === undefined ? 'upload' : 'download', recursive ? 'recursive' : 'file', endpoint.connection, port,
          ...from === undefined ? [local, endpoint.path] : [endpoint.path]],
        cwd: ctx.cwd, env: ctx.exportedEnv === undefined ? Object.fromEntries(ctx.env) : { ...ctx.exportedEnv },
        stdin: '', signal: ctx.signal,
        onStdout: receiver === undefined ? undefined : bytes => receiver!.accept(bytes),
      })
      if (result.exitCode !== 0) return { stdout: '', stderr: result.stderr, exitCode: result.exitCode }
      receiver?.finish()
      return { stdout: from === undefined ? 'Upload complete.\n' : `Downloaded ${receiver!.files} file(s).\n`, stderr: result.stderr, exitCode: 0 }
    } catch (error) {
      return { stdout: '', stderr: `scp: ${error instanceof Error ? error.message : String(error)}\n`, exitCode: ctx.signal?.aborted === true ? 130 : 1 }
    } finally { await receiver?.cleanup() }
  })
}
