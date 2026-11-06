// SPDX-License-Identifier: MIT
import type { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import { argsDigest, dispatchArguments, PHONE_TOOL_PREFIX } from './identity-bridge.ts'

export interface ApprovalEffect {
  tool: string
  operation: string
  resource: string
  target: string
  mutability: 'read' | 'write' | 'external-send' | 'destructive'
  policy: 'auto' | 'ask'
  label: string
  detail: string
  ui: boolean
  execution: string | null
  known: boolean
  isMcp: boolean
}

export interface ApprovalOutcome {
  readonly ok: boolean
  readonly allowed?: boolean
  readonly message?: string
  readonly error?: string
}

export interface ApprovalGateConfig {
  readonly approvalUrl?: string
  readonly bridgeToken?: string
  readonly request?: (payload: Record<string, unknown>, signal?: AbortSignal) => Promise<ApprovalOutcome>
}

const UI_TOOLS = new Set([
  'ask_user', 'show_next_steps', 'show_attachment', 'show_document', 'show_progress',
  'show_checklist', 'show_link', 'RequestApproval',
])
const AUTO_TOOLS = new Set([
  'RecallMemory', 'Task', 'AwaitTask', 'StopSubagent', 'UpdateRoutine', 'ListRoutines', 'SendToUser',
])
const SENSITIVE_RE = /(?:^|[\s'"(=,:])((?:~\/|\/)[^\s'"`)\]}>,;]*(?:\.ssh|\.aws|\.gnupg|\.netrc|\.npmrc|\.pypirc|\.git-credentials|\.credentials\.yaml)(?:\/[^\s'"`)\]}>,;]*)?)/i

export function approvalUrl(env: NodeJS.ProcessEnv = process.env): string {
  const raw = String(env.PDSH_PHONE_MCP_URL ?? '').trim()
  if (!raw) return ''
  const url = new URL(raw)
  url.pathname = url.pathname.replace(/\/mcp\/?$/, '/approval')
  return url.toString()
}

function clip(value: unknown, max: number): string {
  return String(value ?? '').trim().replace(/\s+/g, ' ').slice(0, max)
}

function memoryForgetEffect(args: Record<string, unknown>): ApprovalEffect {
  const fact = clip(args.fact ?? args.key, 300)
  const factId = clip(args.fact_id ?? args.factId, 200)
  const resource = `memory:user&${factId ? `fact_id=${factId}` : `fact=${fact}`}`
  return {
    tool: 'memory', operation: 'memory.forget', resource, target: resource,
    mutability: 'destructive', policy: 'ask', label: factId ? 'Forget a memory (fact_id)' : `Forget memory "${clip(fact, 60)}"`,
    detail: 'Remove one fact from the shared user memory.', ui: false, execution: 'memory', known: true, isMcp: true,
  }
}

function unclassifiedEffect(name: string, args: Record<string, unknown>): ApprovalEffect {
  const resource = `unclassified=${name}&args=${argsDigest(args).slice(0, 16)}`
  return {
    tool: name, operation: 'tool.unclassified', resource, target: resource,
    mutability: 'write', policy: 'ask', label: `Run unclassified tool ${name}`,
    detail: 'This tool is not registered in the effect table and needs approval.',
    ui: false, execution: null, known: false, isMcp: true,
  }
}

export function phoneEffectsForApproval(name: string, args: Record<string, unknown>): ApprovalEffect[] {
  const bare = name.startsWith(PHONE_TOOL_PREFIX) ? name.slice(PHONE_TOOL_PREFIX.length) : name
  if (bare === 'BotVolatileContext' || UI_TOOLS.has(bare) || AUTO_TOOLS.has(bare)) return []
  if (bare === 'UpdateMemory') {
    const action = String(args.action ?? 'write')
    const scope = String(args.scope ?? 'agent')
    if (action === 'forget' && scope === 'user') return [memoryForgetEffect(args)]
    return []
  }
  return [unclassifiedEffect(name, args)]
}

function sensitiveRead(exec: { name: string; arguments?: unknown }): boolean {
  const args = dispatchArguments(exec.arguments)
  if (exec.name === 'bash') return SENSITIVE_RE.test(String(args.command ?? ''))
  for (const key of ['file_path', 'path', 'pattern']) {
    if (SENSITIVE_RE.test(String(args[key] ?? ''))) return true
  }
  return false
}

export async function postApproval(url: string, token: string, payload: Record<string, unknown>, signal?: AbortSignal): Promise<ApprovalOutcome> {
  if (!url || !token) return { ok: false, message: 'approval gate is not configured' }
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new Error('approval timed out')), 330_000)
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify(payload),
      signal: signal === undefined ? controller.signal : AbortSignal.any([controller.signal, signal]),
    })
    if (!response.ok) return { ok: false, message: `approval transport failed (HTTP ${response.status})` }
    return await response.json() as ApprovalOutcome
  } catch (error) {
    return { ok: false, message: error instanceof Error ? error.message : String(error) }
  } finally {
    clearTimeout(timer)
  }
}

export default function dshbotApprovalGate(ctx: Context, config: ApprovalGateConfig = {}): void {
  const url = config.approvalUrl ?? approvalUrl()
  const token = config.bridgeToken ?? String(process.env.PDSH_PHONE_MCP_TOKEN ?? '')
  const send = config.request ?? ((payload: Record<string, unknown>, signal?: AbortSignal) => postApproval(url, token, payload, signal))

  ctx.on('tools/pre-execute', async (exec: { name: string; arguments?: unknown; agent?: Agent; callId?: string; signal?: AbortSignal }, next: () => Promise<any>) => {
    if (sensitiveRead(exec)) {
      return { kind: 'deny', reason: '[sensitive read] The operation targets protected credentials and was NOT executed.' }
    }
    const args = dispatchArguments(exec.arguments)
    const effects = phoneEffectsForApproval(exec.name, args)
    if (effects.length === 0 && exec.name !== 'bash' && exec.name !== 'write' && exec.name !== 'edit') return next()
    const first = effects[0]
    const sessionId = String(exec.agent?.id ?? exec.agent?.session?.id ?? '')
    const payload: Record<string, unknown> = {
      source: 'structural', sessionId, tool: exec.name,
      action: first?.label ?? exec.name,
      detail: first?.detail ?? 'Evaluate structural tool effects.',
      command: exec.name === 'bash' ? String(args.command ?? '') : '',
      target: first?.target ?? exec.name,
      askAction: exec.name.startsWith('mcp__') ? 'tool-call' : 'run-command',
      callId: exec.callId ?? '',
      pattern: first?.operation ?? 'structural',
      effects,
      grantSets: effects.map(effect => [effect.resource]),
      deferConsumption: true,
    }
    const decision = await send(payload, exec.signal)
    if (decision.ok && decision.allowed) return next()
    const reason = decision.message ?? decision.error ?? 'approval was denied'
    return {
      kind: 'deny',
      reason: `[structural approval gate] ${reason} The tool call was NOT executed. Do not retry or work around it.`,
    }
  })
}

dshbotApprovalGate.inject = ['tools']
