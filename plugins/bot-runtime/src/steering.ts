// SPDX-License-Identifier: MIT
import type { Context } from '@deepseek-ai/cordis'
import type { Agent } from '@deepseek-ai/dsh-agent'
import type { UserMessage } from '@deepseek-ai/dsh-session'

export function steerInsertedMessage(agent: Agent, message: UserMessage, rerouting: WeakSet<UserMessage>): boolean {
  if (agent.status !== 'running' || rerouting.has(message)) return false
  if (!agent.inbox.nextTurn.some(pending => pending.id === message.id)) return false
  let removed = false
  rerouting.add(message)
  try {
    removed = agent.inbox.remove(message.id)
    if (!removed) return false
    agent.steer(message)
    return true
  } catch (error) {
    if (removed) {
      try {
        agent.followup(message)
      } catch {}
    }
    const detail = error instanceof Error ? error.message : String(error)
    console.error(`[steering] could not steer ${message.id}: ${detail}`)
    return false
  } finally {
    rerouting.delete(message)
  }
}

export default function dshbotSteering(ctx: Context): void {
  const rerouting = new WeakSet<UserMessage>()
  ctx.on('agent/inbox/inserted', ({ agent, message }) => {
    steerInsertedMessage(agent, message, rerouting)
  })
}
