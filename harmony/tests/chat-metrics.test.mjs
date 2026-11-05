// SPDX-License-Identifier: MIT
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const directory = resolve(dirname(fileURLToPath(import.meta.url)), '../entry/src/main/ets/model');
function moduleUrl(path) {
  let text = readFileSync(path, 'utf8');
  text = text.replace(/from\s+(['"])(\.[^'"]+)\1/g, (_all, quote, target) =>
    'from ' + quote + moduleUrl(resolve(dirname(path), target + '.ets')) + quote);
  const output = stripTypeScriptTypes(text, { mode: 'transform' });
  return 'data:text/javascript;base64,' + Buffer.from(output).toString('base64');
}
const metrics = await import(moduleUrl(resolve(directory, 'ChatMetrics.ets')));

// A real `session/projections` shape: `values` owns the four units.
function projection(overrides = {}) {
  return {
    values: {
      sessionStats: {
        turns: 7, steps: 9, llmMs: 12000, toolMs: 3000, ttftMs: 800,
        ttftSteps: 4, decodeMs: 1000, decodeTokens: 263
      },
      tokenUsage: {
        uncachedInputTokens: 1000, outputTokens: 500,
        cacheReadTokens: 24000, cacheWriteTokens: 0
      },
      contextPressure: { pressureTokens: 98500, contextWindow: 100000 },
      contextBreakdown: { systemTokens: 1200, toolsTokens: 800, messageTokens: 3000 },
      ...overrides
    }
  };
}

test('reads the exact Worker projection units, not a re-derived guess', () => {
  const parsed = metrics.parseSessionMetrics(projection());
  assert.equal(parsed.stats.turns, 7);
  assert.equal(parsed.stats.steps, 9);
  assert.equal(parsed.stats.decodeTokens, 263);
  assert.equal(parsed.usage.cacheReadTokens, 24000);
  assert.equal(parsed.pressure.contextWindow, 100000);
  assert.equal(parsed.breakdown.systemTokens, 1200);
});

test('an absent or malformed unit stays null so the footer hides it', () => {
  const missing = metrics.parseSessionMetrics({ values: { tokenUsage: projection().values.tokenUsage } });
  assert.equal(missing.stats, null);
  assert.equal(missing.pressure, null);
  assert.equal(missing.breakdown, null);
  assert.notEqual(missing.usage, null);

  const bad = projection({
    sessionStats: { turns: -1, steps: 9, llmMs: 0, toolMs: 0, ttftMs: 0, ttftSteps: 0, decodeMs: 0, decodeTokens: 0 },
    contextPressure: { contextWindow: 0 }
  });
  const parsed = metrics.parseSessionMetrics(bad);
  assert.equal(parsed.stats, null);
  assert.equal(parsed.pressure, null);
});

test('formatting matches the Android composer helpers', () => {
  assert.equal(metrics.compactMetricTokens(98500), '98.5K');
  assert.equal(metrics.compactMetricTokens(1200000), '1.2M');
  assert.equal(metrics.compactMetricTokens(999), '999');
  assert.equal(metrics.formatMetricPercent(96), '96');
  assert.equal(metrics.formatMetricPercent(9.44), '9.4');
  assert.equal(metrics.formatMetricDuration(12500), '12.5秒');
  assert.equal(metrics.formatMetricDuration(65000), '1分5秒');
  assert.equal(metrics.runDuration(1000), '1秒');
  assert.equal(metrics.runDuration(125000), '2分5秒');
});

test('speed, totals, cache hit and context percent follow the DTO getters', () => {
  const parsed = metrics.parseSessionMetrics(projection());
  assert.equal(metrics.tokensPerSecond(parsed.stats), 263);
  assert.equal(metrics.totalTokens(parsed.usage), 25500);
  assert.equal(metrics.billedInputTokens(parsed.usage), 25000);
  assert.equal(metrics.cacheHitPercent(parsed.usage), 96);
  assert.equal(metrics.contextUsedTokens(parsed.pressure), 98500);
  assert.equal(metrics.contextPercent(parsed.pressure), 99);
  assert.equal(metrics.tokensPerSecond(null), null);
  assert.equal(metrics.cacheHitPercent(null), null);
  assert.equal(metrics.contextPercent(null), null);
});

test('turn summaries only render from real closed-turn Worker facts', () => {
  assert.equal(metrics.turnOutcomeOf('completed'), metrics.TurnOutcome.Completed);
  assert.equal(metrics.turnOutcomeOf('cancelled'), metrics.TurnOutcome.Stopped);
  assert.equal(metrics.turnOutcomeOf('error'), metrics.TurnOutcome.Failed);
  assert.equal(metrics.turnProcessLabel(metrics.TurnOutcome.Completed, 1000), '已完成，用时 1秒');
  assert.equal(metrics.turnProcessLabel(metrics.TurnOutcome.Stopped, 4000), '已停止');
  assert.equal(metrics.turnProcessLabel(metrics.TurnOutcome.Running, 0), '正在处理');
  assert.equal(metrics.turnProcessLabel(metrics.TurnOutcome.Completed, -1), '已完成');
  assert.equal(metrics.elapsedMillis(1000, 3500), 2500);
  assert.equal(metrics.elapsedMillis(3500, 1000), -1);
  assert.equal(metrics.clockLabel(0), '');
});

test('the metrics DTOs are data-only; behavior lives in exported functions', () => {
  const parsed = metrics.parseSessionMetrics(projection());
  for (const value of [parsed.stats, parsed.usage, parsed.pressure, parsed.breakdown]) {
    assert.deepEqual(
      Object.getOwnPropertyNames(Object.getPrototypeOf(value)).filter(key => key !== 'constructor'),
      []
    );
  }
  assert.deepEqual(JSON.parse(JSON.stringify(parsed)).stats.turns, 7);
});
