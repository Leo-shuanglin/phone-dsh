// SPDX-License-Identifier: MIT
import { posix } from 'node:path'
import { defineCommand } from 'just-bash/browser'
import type { AcceleratorHost } from './bridge.ts'
import { SCPReceiver } from './scp.ts'

export function createAdbCommand(host: AcceleratorHost) {
  return defineCommand('adb', async (args, ctx) => {
    let receiver: SCPReceiver | undefined
    try {
      if (args.length === 1 && args[0] === '--help') return { stdout: 'Usage: adb push LOCAL REMOTE | adb pull REMOTE [LOCAL] | adb stat /dsh/transfers/NAME\nSingle regular files. /dsh/transfers/NAME streams up to 1 GiB to iPhone disk; /dsh/workspace/NAME uses the smaller Worker file budget. Pull defaults to /dsh/transfers/NAME. Uses the Android target saved by android_connect.\n', stderr: '', exitCode: 0 }
      const action = args[0]
      if (action === 'stat' && args.length === 2) {
        const local = posix.resolve(ctx.cwd, args[1]!)
        if (!local.startsWith('/dsh/transfers/')) throw new Error('adb stat requires /dsh/transfers/NAME')
        const result = await host.exec({ argv: ['adb', '--pdsh-v1', 'stat', local], cwd: ctx.cwd,
          env: ctx.exportedEnv === undefined ? Object.fromEntries(ctx.env) : { ...ctx.exportedEnv }, stdin: '', signal: ctx.signal })
        return { stdout: result.stdout, stderr: result.stderr, exitCode: result.exitCode }
      }
      if (!((action === 'push' && args.length === 3) || (action === 'pull' && (args.length === 2 || args.length === 3)))) throw new Error('use adb push LOCAL REMOTE or adb pull REMOTE [LOCAL]; directories and flags are unsupported')
      const remote = args[action === 'push' ? 2 : 1]!
      if (!remote.startsWith('/') || remote.includes('\0') || remote.endsWith('/')) throw new Error('Android path must be an absolute file path')
      const local = posix.resolve(ctx.cwd, action === 'push' ? args[1]! : args[2] ?? '/dsh/transfers/' + posix.basename(remote))
      const native = local.startsWith('/dsh/transfers/')
      if (!native && !local.startsWith('/dsh/workspace/')) throw new Error('local path must be inside /dsh/workspace or /dsh/transfers')
      let current = '/'
      for (const part of local.split('/').filter(Boolean)) {
        current = posix.join(current, part)
        if (await ctx.fs.exists(current) && (await ctx.fs.lstat(current)).isSymbolicLink) throw new Error('local symbolic links are unsupported')
      }
      if (!native && action === 'push' && !(await ctx.fs.stat(local)).isFile) throw new Error('push source must be a regular file')
      if (!native && action === 'pull') receiver = new SCPReceiver(ctx.fs, local, false)
      const result = await host.exec({ argv: ['adb', '--pdsh-v1', action, local, remote], cwd: ctx.cwd,
        env: ctx.exportedEnv === undefined ? Object.fromEntries(ctx.env) : { ...ctx.exportedEnv }, stdin: '', signal: ctx.signal,
        onStdout: receiver === undefined ? undefined : bytes => receiver!.accept(bytes) })
      if (result.exitCode !== 0) return { stdout: '', stderr: result.stderr, exitCode: result.exitCode }
      receiver?.finish()
      return { stdout: native ? `Transferred 1 file: ${local}\n` : action === 'push' ? 'Pushed 1 file.\n' : 'Pulled 1 file.\n', stderr: result.stderr, exitCode: 0 }
    } catch (error) {
      return { stdout: '', stderr: `adb: ${error instanceof Error ? error.message : String(error)}\n`, exitCode: ctx.signal?.aborted ? 130 : 1 }
    } finally { await receiver?.cleanup() }
  })
}
