// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import { ToolCallId } from '@deepseek-ai/dsh-llm'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'
import ToolRuntime, { defineTool } from '@deepseek-ai/dsh-tools'
import dshbotIdentityBridge, { argsDigest } from '../bot-runtime/src/identity-bridge.ts'
import dshbotApprovalGate, { phoneEffectsForApproval } from '../bot-runtime/src/approval-gate.ts'

function agent(id = 'gb-main'): Agent {
  return { id, session: { id } } as unknown as Agent
}

async function mount(withBot = true): Promise<Context> {
  const ctx = new Context()
  await ctx.plugin(SystemPrompt, {})
  await ctx.plugin(ToolRuntime, {})
  if (withBot) registerProbe(ctx, 'mcp__phone__BotVolatileContext')
  return ctx
}

function registerProbe(ctx: Context, name = 'mcp__phone__ListRoutines'): void {
  ctx.tools.register(defineTool({
    name,
    description: 'probe',
    parameters: {},
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
    execute: async () => 'ok',
  }))
}

test('ordinary DSH without Bot MCP never calls Bot identity or approval endpoints', async () => {
  const ctx = await mount(false)
  let calls = 0
  try {
    registerProbe(ctx, 'bash')
    await ctx.plugin(dshbotIdentityBridge, {
      request: async () => { calls += 1; return { ok: true, id: 'unexpected' } },
      cancel: async () => {},
    })
    await ctx.plugin(dshbotApprovalGate, {
      request: async () => { calls += 1; return { ok: true, allowed: true } },
    })
    const result = await ctx.tools.execute({
      callId: ToolCallId('ordinary-bash'), name: 'bash',
      arguments: { command: 'rm -rf /tmp/wp7-artifact' },
      signal: new AbortController().signal, agent: agent(),
    })
    assert.equal(result.isError, false)
    assert.equal(calls, 0)
  } finally {
    await ctx.fiber.dispose()
  }
})

test('pre-execute and execute share the pinned identity and cancel once', async () => {
  assert.equal(argsDigest({ secret: 'not-recorded' }), '9c58b105c0e3377b8052885445b4db03b3a2024b847396617dfd5152c05e9c06')
  assert.equal(argsDigest({ b: [1, true, null], a: { nested: '值' } }), argsDigest({ a: { nested: '值' }, b: [1, true, null] }))
  const ctx = await mount()
  const registrations: Record<string, unknown>[] = []
  const cancellations: string[] = []
  try {
    registerProbe(ctx)
    await ctx.plugin(dshbotIdentityBridge, {
      request: async payload => {
        registrations.push(payload)
        return { ok: true, id: 'registration-1' }
      },
      cancel: async id => { cancellations.push(id) },
    })
    const result = await ctx.tools.execute({
      callId: ToolCallId('call-1'), name: 'mcp__phone__ListRoutines',
      arguments: { value: 1 }, signal: new AbortController().signal, agent: agent(),
    })
    assert.equal(result.isError, false)
    assert.equal(registrations.length, 2)
    assert.deepEqual(registrations[0], registrations[1])
    assert.equal(registrations[0]!.runtimeId, 'phone-worker')
    assert.equal(registrations[0]!.sessionId, 'gb-main')
    assert.equal(registrations[0]!.callId, 'call-1')
    assert.equal(registrations[0]!.argsDigest, argsDigest({ value: 1 }))
    assert.deepEqual(registrations[0]!.arguments, { value: 1 })
    assert.deepEqual(cancellations, ['registration-1'])
  } finally {
    await ctx.fiber.dispose()
  }
})

test('identical call IDs in independent sessions do not deadlock or overwrite identity', async () => {
  const ctx = await mount()
  const registrations: Record<string, unknown>[] = []
  let parentDidStart!: () => void
  const parentStarted = new Promise<void>(resolve => { parentDidStart = resolve })
  let childDidStart!: () => void
  const childStarted = new Promise<void>(resolve => { childDidStart = resolve })
  let finish!: () => void
  const gate = new Promise<void>(resolve => { finish = resolve })
  try {
    ctx.tools.register(defineTool({
      name: 'mcp__phone__Task', description: 'probe', parameters: {},
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      execute: async (_args, exec) => {
        if (exec.agent?.id === 'gb-parent') parentDidStart()
        else childDidStart()
        await gate
        return exec.agent?.id === 'gb-parent' ? 'parent' : 'child'
      },
    }))
    await ctx.plugin(dshbotIdentityBridge, {
      request: async payload => {
        registrations.push(payload)
        return { ok: true, id: `${String(payload.sessionId)}-registration` }
      },
      cancel: async () => {},
    })
    const args = { prompt: 'same', task_title: 'same' }
    const parent = ctx.tools.execute({
      callId: ToolCallId('shared'), name: 'mcp__phone__Task', arguments: args,
      signal: new AbortController().signal, agent: agent('gb-parent'),
    })
    await parentStarted
    const child = ctx.tools.execute({
      callId: ToolCallId('shared'), name: 'mcp__phone__Task', arguments: args,
      signal: new AbortController().signal, agent: agent('gb-child'),
    })
    const childState = await Promise.race([
      childStarted.then(() => 'started'),
      new Promise<string>(resolve => { setTimeout(() => { resolve('waiting') }, 100) }),
    ])
    const identities = ctx.get('dshbotIdentityCalls') as {
      get(callId: string, sessionId?: string): { sessionId: string } | undefined
    }
    const parentSession = identities.get('shared', 'gb-parent')?.sessionId
    const childSession = identities.get('shared', 'gb-child')?.sessionId
    finish()
    await parent
    await child
    assert.equal(childState, 'started')
    assert.equal(parentSession, 'gb-parent')
    assert.equal(childSession, 'gb-child')
    assert.equal(registrations.length, 4)
    assert.deepEqual(new Set(registrations.map(registration => registration.sessionId)),
      new Set(['gb-parent', 'gb-child']))
  } finally {
    finish()
    await ctx.fiber.dispose()
  }
})

test('approval memory effect matches the Swift execution resource and sensitive reads never reach approval', async () => {
  const effects = phoneEffectsForApproval('mcp__phone__UpdateMemory', {
    action: 'forget', scope: 'user', fact: '  private   fact ',
  })
  assert.equal(effects.length, 1)
  assert.equal(effects[0]!.resource, 'memory:user&fact=private fact')
  assert.equal(effects[0]!.execution, 'memory')
  assert.equal(phoneEffectsForApproval('mcp__phone__show_table', {}).length, 1)

  const ctx = await mount()
  let sent = 0
  try {
    registerProbe(ctx, 'bash')
    await ctx.plugin(dshbotApprovalGate, {
      request: async () => { sent += 1; return { ok: true, allowed: true } },
    })
    const result = await ctx.tools.execute({
      callId: ToolCallId('sensitive'), name: 'bash',
      arguments: { command: 'cat ~/.ssh/id_rsa' }, signal: new AbortController().signal, agent: agent(),
    })
    assert.equal(result.isError, true)
    assert.equal(sent, 0)
  } finally {
    await ctx.fiber.dispose()
  }
})

test('approval gate forwards the full effect before allowing the real tool body', async () => {
  const ctx = await mount()
  const payloads: Record<string, unknown>[] = []
  try {
    registerProbe(ctx, 'mcp__phone__UpdateMemory')
    await ctx.plugin(dshbotApprovalGate, {
      request: async payload => {
        payloads.push(payload)
        return { ok: true, allowed: true }
      },
    })
    const result = await ctx.tools.execute({
      callId: ToolCallId('forget-user'), name: 'mcp__phone__UpdateMemory',
      arguments: { action: 'forget', scope: 'user', fact: 'private' },
      signal: new AbortController().signal, agent: agent(),
    })
    assert.equal(result.isError, false)
    assert.equal(payloads.length, 1)
    assert.equal(payloads[0]!.callId, 'forget-user')
    assert.equal(payloads[0]!.argsDigest, argsDigest({ action: 'forget', scope: 'user', fact: 'private' }))
    assert.equal(payloads[0]!.alwaysScope, 'until')
    assert.equal(payloads[0]!.deferConsumption, true)
    const effects = payloads[0]!.effects as { resource: string }[]
    assert.equal(effects[0]!.resource, 'memory:user&fact=private')
  } finally {
    await ctx.fiber.dispose()
  }
})

test('structural bash approval carries recomputable effects and consumes at its own execution point', async () => {
  const ctx = await mount()
  const payloads: Record<string, unknown>[] = []
  const args = { command: 'rm -rf /tmp/wp7-artifact' }
  try {
    registerProbe(ctx, 'bash')
    await ctx.plugin(dshbotApprovalGate, {
      request: async payload => {
        payloads.push(payload)
        return { ok: true, allowed: true }
      },
    })
    const result = await ctx.tools.execute({
      callId: ToolCallId('destructive'), name: 'bash', arguments: args,
      signal: new AbortController().signal, agent: agent(),
    })
    assert.equal(result.isError, false)
    assert.equal(payloads.length, 1)
    assert.equal(payloads[0]!.command, args.command)
    assert.equal(payloads[0]!.argsDigest, argsDigest(args))
    assert.equal(payloads[0]!.alwaysScope, 'until')
    assert.equal(payloads[0]!.deferConsumption, false)
    const effects = payloads[0]!.effects as { operation: string; resource: string }[]
    assert.deepEqual(effects.map(effect => effect.operation), ['rm-destructive'])
    assert.equal(effects[0]!.resource, '/tmp/wp7-artifact')
  } finally {
    await ctx.fiber.dispose()
  }
})
