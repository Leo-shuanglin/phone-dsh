// SPDX-License-Identifier: MIT
import { AsyncLocalStorage } from 'node:async_hooks'
import { createHash } from 'node:crypto'
import type { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import { canonicalStringify } from './canonical-json.ts'

export const PHONE_TOOL_PREFIX = 'mcp__phone__'
export const DEFAULT_IDENTITY_TIMEOUT_MS = 5_000

export interface ToolExecution {
  readonly name: string
  readonly arguments?: unknown
  readonly callId?: string
  readonly agent?: Agent
  readonly signal?: AbortSignal
}

export interface IdentityRegistration {
  readonly ok: boolean
  readonly id?: string
  readonly error?: string
}

export interface IdentityBridgeConfig {
  readonly runtimeId?: string
  readonly bridgeUrl?: string
  readonly bridgeToken?: string
  readonly timeoutMs?: number
  readonly request?: (payload: Record<string, unknown>, options: { timeoutMs: number; signal?: AbortSignal }) => Promise<IdentityRegistration>
  readonly cancel?: (id: string, options: { timeoutMs: number; signal?: AbortSignal }) => Promise<void>
}

export function argsDigest(value: unknown): string {
  return createHash('sha256').update(canonicalStringify(value ?? {})).digest('hex')
}

export function dispatchArguments(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

export function identityRegisterUrl(env: NodeJS.ProcessEnv = process.env): string {
  const raw = String(env.PDSH_PHONE_MCP_URL ?? '').trim()
  if (!raw) return ''
  const url = new URL(raw)
  url.pathname = url.pathname.replace(/\/mcp\/?$/, '/identity/register')
  return url.toString()
}

export function createKeyedMutex() {
  const states = new Map<string, { busy: boolean; waiters: { resolve: (release: () => void) => void; reject: (error: Error) => void; signal?: AbortSignal }[] }>()

  function acquire(key: string, signal?: AbortSignal): Promise<() => void> {
    if (signal?.aborted) return Promise.reject(new Error('identity registration wait was aborted'))
    let state = states.get(key)
    if (!state) {
      state = { busy: false, waiters: [] }
      states.set(key, state)
    }
    const makeRelease = (): (() => void) => {
      let released = false
      return () => {
        if (released) return
        released = true
        state!.busy = false
        const waiter = state!.waiters.shift()
        if (waiter) {
          state!.busy = true
          waiter.resolve(makeRelease())
        } else if (!state!.busy && state!.waiters.length === 0) {
          states.delete(key)
        }
      }
    }
    if (!state.busy) {
      state.busy = true
      return Promise.resolve(makeRelease())
    }
    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, signal }
      state!.waiters.push(waiter)
      signal?.addEventListener('abort', () => {
        const index = state!.waiters.indexOf(waiter)
        if (index >= 0) state!.waiters.splice(index, 1)
        reject(new Error('identity registration wait was aborted'))
      }, { once: true })
    })
  }

  return { acquire, size: () => states.size }
}

async function postJson(url: string, token: string, payload: Record<string, unknown>, timeoutMs: number, signal?: AbortSignal): Promise<IdentityRegistration> {
  if (!url) throw new Error('[identity-bridge] App MCP URL is not configured')
  if (!token) throw new Error('[identity-bridge] App MCP token is not configured')
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new Error('identity registration timed out')), timeoutMs)
  const abort = (): void => controller.abort(signal?.reason)
  signal?.addEventListener('abort', abort, { once: true })
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify(payload),
      signal: signal === undefined ? controller.signal : AbortSignal.any([controller.signal, signal]),
    })
    if (!response.ok) throw new Error(`[identity-bridge] App MCP rejected registration (HTTP ${response.status})`)
    const value = await response.json() as IdentityRegistration
    if (!value || value.ok !== true || typeof value.id !== 'string' || value.id === '') {
      throw new Error('[identity-bridge] App MCP did not accept the call registration')
    }
    return value
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

export interface BotIdentityCall {
  readonly sessionId: string
  readonly name: string
  readonly arguments: Record<string, unknown>
  readonly argsDigest: string
}

export default function dshbotIdentityBridge(ctx: Context, config: IdentityBridgeConfig = {}): void {
  const runtimeId = config.runtimeId ?? 'phone-worker'
  const bridgeUrl = config.bridgeUrl ?? identityRegisterUrl()
  const bridgeToken = config.bridgeToken ?? String(process.env.PDSH_PHONE_MCP_TOKEN ?? '')
  const timeoutMs = config.timeoutMs ?? DEFAULT_IDENTITY_TIMEOUT_MS
  const send = config.request ?? ((payload: Record<string, unknown>, options: { timeoutMs: number; signal?: AbortSignal }) => postJson(bridgeUrl, bridgeToken, payload, options.timeoutMs, options.signal))
  const cancelUrl = bridgeUrl.replace(/\/identity\/register$/, '/identity/cancel')
  const cancel = config.cancel ?? (async (id: string, options: { timeoutMs: number; signal?: AbortSignal }) => {
    await postJson(cancelUrl, bridgeToken, { id }, options.timeoutMs, options.signal)
  })
  const mutex = createKeyedMutex()
  const dispatchChain = new AsyncLocalStorage<string>()
  const calls = new Map<string, BotIdentityCall>()
  const callKey = (sessionId: string, callId: string): string => `${sessionId}\0${callId}`
  ctx.provide('dshbotIdentityCalls', {
    get(callId: string, sessionId?: string): BotIdentityCall | undefined {
      if (sessionId !== undefined) return calls.get(callKey(sessionId, callId))
      const matches = [...calls.values()].filter(call => call.name !== '' && call.argsDigest !== ''
        && calls.get(callKey(call.sessionId, callId)) === call)
      return matches.length === 1 ? matches[0] : undefined
    },
  })

  async function register(exec: ToolExecution) {
    const args = dispatchArguments(exec.arguments)
    const digest = argsDigest(args)
    const sessionId = String(exec.agent?.id ?? exec.agent?.session?.id ?? '')
    const callId = String(exec.callId ?? '')
    const release = await mutex.acquire(`${runtimeId}\0${sessionId}\0${callId}\0${digest}`, exec.signal)
    try {
      if (!sessionId) throw new Error('[identity-bridge] tool execution has no session identity')
      if (!callId) throw new Error('[identity-bridge] tool execution has no callId')
      const registration = await send({
        runtimeId, sessionId, callId, name: exec.name, arguments: args, argsDigest: digest,
      }, { timeoutMs, signal: exec.signal })
      calls.set(callKey(sessionId, callId), { sessionId, name: exec.name, arguments: args, argsDigest: digest })
      return { registration, release, sessionId }
    } catch (error) {
      release()
      throw error
    }
  }

  ctx.on('tools/pre-execute', async (exec: ToolExecution, next: () => Promise<any>) => {
    const registered = await register(exec)
    registered.release()
    return next()
  })

  ctx.on('tools/execute', async (exec: ToolExecution, next: () => Promise<any>) => {
    const sessionId = String(exec.agent?.id ?? exec.agent?.session?.id ?? '')
    if (dispatchChain.getStore() === sessionId) throw new Error('[identity-bridge] re-entrant phone tool call is not allowed')
    const registered = await register(exec)
    try {
      return await dispatchChain.run(sessionId, () => next())
    } finally {
      calls.delete(callKey(registered.sessionId, String(exec.callId ?? '')))
      if (registered.registration.id !== undefined) {
        try { await cancel(registered.registration.id, { timeoutMs, signal: exec.signal }) } catch {}
      }
      registered.release()
    }
  })
}

dshbotIdentityBridge.inject = ['tools']
