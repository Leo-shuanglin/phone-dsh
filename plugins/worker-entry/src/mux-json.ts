// SPDX-License-Identifier: MIT
import { compareByCodePoint } from '../../channel/src/canonical-json.ts'

/**
 * Application JSON for the opaque API body. Keep stable key ordering while
 * preserving finite numbers; channel control JSON retains its integer rule.
 * Reject invalid values rather than silently turning NaN/Infinity into null.
 */
export function stringifyMuxJson(value: unknown): string {
  return encode(value, new Set())
}

function encode(value: unknown, stack: Set<object>): string {
  if (value === null) return 'null'
  switch (typeof value) {
    case 'boolean': return value ? 'true' : 'false'
    case 'string': return JSON.stringify(value)
    case 'number':
      if (!Number.isFinite(value)) throw new TypeError('mux JSON rejects non-finite numbers')
      return JSON.stringify(value)
    case 'object': {
      if (stack.has(value)) throw new TypeError('mux JSON rejects circular structures')
      stack.add(value)
      try {
        if (Array.isArray(value)) {
          return `[${Array.from(value, member => encode(member, stack)).join(',')}]`
        }
        const members = Object.entries(value).sort(([a], [b]) => compareByCodePoint(a, b))
        return `{${members.map(([key, member]) => `${JSON.stringify(key)}:${encode(member, stack)}`).join(',')}}`
      } finally {
        stack.delete(value)
      }
    }
    default: throw new TypeError(`mux JSON cannot encode a ${typeof value}`)
  }
}
