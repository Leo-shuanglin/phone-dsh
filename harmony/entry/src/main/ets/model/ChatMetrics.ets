// SPDX-License-Identifier: MIT
// Pure data + functions for the Android chat metrics surface.
//
// Source of every field and formula: Android `SessionStats`, `SessionTokenUsage`,
// `ContextPressure`, `ContextBreakdown` (`ui/state/ChatScreenState.kt`), the
// `turnProcessLabel`/`runDuration` helpers (`ui/page/ChatRows.kt`) and the
// composer footer formatters (`ui/surface/Composer.kt`). The values come from
// the Worker `session/projections` result; nothing here invents a token count or
// a clock. A missing or malformed projection stays `null` and the UI hides it.

/** Closed-turn outcome, the Android `ChatTurnState` vocabulary the page renders. */
export enum TurnOutcome {
  Running = 'running',
  Completed = 'completed',
  Stopped = 'stopped',
  Failed = 'failed',
}

/** Session activity totals (`sessionStats` projection). All figures are Worker facts. */
export class SessionStatsDTO {
  turns: number = 0;
  steps: number = 0;
  llmMs: number = 0;
  toolMs: number = 0;
  ttftMs: number = 0;
  ttftSteps: number = 0;
  decodeMs: number = 0;
  decodeTokens: number = 0;
}

/** Provider-reported token buckets (`tokenUsage` projection). */
export class TokenUsageDTO {
  uncachedInputTokens: number = 0;
  outputTokens: number = 0;
  cacheReadTokens: number = 0;
  cacheWriteTokens: number = 0;
}

/** Context-window occupancy (`contextPressure` projection). */
export class ContextPressureDTO {
  pressureTokens: number = -1;
  projectedTokens: number = -1;
  contextWindow: number = 0;
}

/** Estimated prompt composition (`contextBreakdown` projection). */
export class ContextBreakdownDTO {
  systemTokens: number = 0;
  toolsTokens: number = 0;
  messageTokens: number = 0;
}

/** One closed turn's timing, built only from the Worker's `turn/start` + `turn/end` times. */
export class TurnSummaryDTO {
  turn: number = 0;
  outcome: TurnOutcome = TurnOutcome.Completed;
  startedAtMs: number = 0;
  endedAtMs: number = 0;
}

/** The four optional projection objects, kept together so a poll updates all or none. */
export class SessionMetrics {
  stats: SessionStatsDTO | null = null;
  usage: TokenUsageDTO | null = null;
  pressure: ContextPressureDTO | null = null;
  breakdown: ContextBreakdownDTO | null = null;
}

function asRecord(value: Object | null | undefined): Record<string, Object> | null {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== 'object' || Array.isArray(value)) {
    return null;
  }
  return value as Record<string, Object>;
}

function finiteNumber(record: Record<string, Object> | null, key: string): number | null {
  if (record === null) {
    return null;
  }
  const value: Object | undefined = record[key];
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return null;
  }
  return value;
}

function nonNegative(record: Record<string, Object> | null, key: string): number | null {
  const value = finiteNumber(record, key);
  return value !== null && value >= 0 ? value : null;
}

function safeSum(values: number[]): number | null {
  let sum = 0;
  for (let index = 0; index < values.length; index++) {
    const value = values[index];
    if (value < 0 || Number.MAX_SAFE_INTEGER - sum < value) {
      return null;
    }
    sum += value;
  }
  return sum;
}

function parseStats(value: Object | null | undefined): SessionStatsDTO | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const fields: number[] = [];
  const keys: string[] = ['turns', 'steps', 'ttftSteps', 'decodeTokens'];
  for (let index = 0; index < keys.length; index++) {
    const parsed = nonNegative(row, keys[index]);
    if (parsed === null) {
      return null;
    }
    fields.push(parsed);
  }
  const times: string[] = ['llmMs', 'toolMs', 'ttftMs', 'decodeMs'];
  for (let index = 0; index < times.length; index++) {
    const parsed = nonNegative(row, times[index]);
    if (parsed === null) {
      return null;
    }
    fields.push(parsed);
  }
  const stats = new SessionStatsDTO();
  stats.turns = fields[0];
  stats.steps = fields[1];
  stats.ttftSteps = fields[2];
  stats.decodeTokens = fields[3];
  stats.llmMs = fields[4];
  stats.toolMs = fields[5];
  stats.ttftMs = fields[6];
  stats.decodeMs = fields[7];
  return stats;
}

function parseUsage(value: Object | null | undefined): TokenUsageDTO | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const uncached = nonNegative(row, 'uncachedInputTokens');
  const output = nonNegative(row, 'outputTokens');
  const cacheRead = nonNegative(row, 'cacheReadTokens');
  const cacheWrite = nonNegative(row, 'cacheWriteTokens');
  if (uncached === null || output === null || cacheRead === null || cacheWrite === null) {
    return null;
  }
  if (safeSum([uncached, cacheRead, cacheWrite, output]) === null) {
    return null;
  }
  const usage = new TokenUsageDTO();
  usage.uncachedInputTokens = uncached;
  usage.outputTokens = output;
  usage.cacheReadTokens = cacheRead;
  usage.cacheWriteTokens = cacheWrite;
  return usage;
}

function parsePressure(value: Object | null | undefined): ContextPressureDTO | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const window = finiteNumber(row, 'contextWindow');
  if (window === null || window <= 0) {
    return null;
  }
  const pressure = nonNegative(row, 'pressureTokens');
  const projected = nonNegative(row, 'projectedTokens');
  if (pressure === null && row['pressureTokens'] !== undefined && row['pressureTokens'] !== null) {
    return null;
  }
  if (projected === null && row['projectedTokens'] !== undefined && row['projectedTokens'] !== null) {
    return null;
  }
  const dto = new ContextPressureDTO();
  dto.pressureTokens = pressure === null ? -1 : pressure;
  dto.projectedTokens = projected === null ? -1 : projected;
  dto.contextWindow = window;
  return dto;
}

function parseBreakdown(value: Object | null | undefined): ContextBreakdownDTO | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const system = nonNegative(row, 'systemTokens');
  const tools = nonNegative(row, 'toolsTokens');
  const messages = nonNegative(row, 'messageTokens');
  if (system === null || tools === null || messages === null) {
    return null;
  }
  const dto = new ContextBreakdownDTO();
  dto.systemTokens = system;
  dto.toolsTokens = tools;
  dto.messageTokens = messages;
  return dto;
}

/**
 * Reads one `session/projections` result. The Android client reads the same four
 * keys under `values`; a missing or invalid object stays `null` (hidden), never 0.
 */
export function parseSessionMetrics(root: Object | null | undefined): SessionMetrics {
  const result = new SessionMetrics();
  const outer = asRecord(root);
  if (outer === null) {
    return result;
  }
  const values = asRecord(outer['values']);
  const source = values === null ? outer : values;
  result.stats = parseStats(source['sessionStats']);
  result.usage = parseUsage(source['tokenUsage']);
  result.pressure = parsePressure(source['contextPressure']);
  result.breakdown = parseBreakdown(source['contextBreakdown']);
  return result;
}

/** Android `SessionStats.tokensPerSecond`, `null` when decode time is absent/zero. */
export function tokensPerSecond(stats: SessionStatsDTO | null): number | null {
  if (stats === null || !Number.isFinite(stats.decodeMs) || stats.decodeMs <= 0) {
    return null;
  }
  const value = stats.decodeTokens / (stats.decodeMs / 1000);
  return Number.isFinite(value) && value >= 0 ? value : null;
}

/** Android `SessionTokenUsage.billedInputTokens`; `null` for a missing/overflowing total. */
export function billedInputTokens(usage: TokenUsageDTO | null): number | null {
  if (usage === null) {
    return null;
  }
  return safeSum([usage.uncachedInputTokens, usage.cacheReadTokens, usage.cacheWriteTokens]);
}

/** Android `SessionTokenUsage.totalTokens`; `null` for a missing/overflowing total. */
export function totalTokens(usage: TokenUsageDTO | null): number | null {
  if (usage === null) {
    return null;
  }
  return safeSum([usage.uncachedInputTokens, usage.cacheReadTokens, usage.cacheWriteTokens, usage.outputTokens]);
}

/** Android `SessionTokenUsage.cacheHitPercent`, `null` when no billed input exists. */
export function cacheHitPercent(usage: TokenUsageDTO | null): number | null {
  const billed = billedInputTokens(usage);
  if (usage === null || billed === null || billed <= 0) {
    return null;
  }
  const value = usage.cacheReadTokens * 100 / billed;
  return Number.isFinite(value) ? value : null;
}

/** The exact Android `usedTokens` rule: projected first, pressure second, else unknown. */
export function contextUsedTokens(pressure: ContextPressureDTO | null): number | null {
  if (pressure === null) {
    return null;
  }
  if (pressure.projectedTokens >= 0) {
    return pressure.projectedTokens;
  }
  if (pressure.pressureTokens >= 0) {
    return pressure.pressureTokens;
  }
  return null;
}

/** Android `ContextPressure.percent`, rounded and clamped to 0..100. */
export function contextPercent(pressure: ContextPressureDTO | null): number | null {
  const used = contextUsedTokens(pressure);
  if (used === null || pressure === null || pressure.contextWindow <= 0) {
    return null;
  }
  const percent = Math.round(used * 100 / pressure.contextWindow);
  return percent < 0 ? 0 : (percent > 100 ? 100 : percent);
}

/** Android `compactMetricTokens`: 98.5K / 1.2M, value-preserving. */
export function compactMetricTokens(value: number): string {
  if (!Number.isFinite(value) || value < 0) {
    return '';
  }
  if (value < 1000) {
    return Math.round(value).toString();
  }
  if (value < 1000000) {
    return scaledMetric(value / 1000) + 'K';
  }
  return scaledMetric(value / 1000000) + 'M';
}

function scaledMetric(value: number): string {
  if (value >= 100) {
    return Math.round(value).toString();
  }
  return (Math.round(value * 10) / 10).toString();
}

/** Android `formatMetricPercent`: whole number at >=10, one decimal below. */
export function formatMetricPercent(value: number): string {
  if (!Number.isFinite(value) || value < 0) {
    return '';
  }
  return scaledMetric(value);
}

/** Android `formatMetricDuration`: "12.3秒" under a minute, else "1分5秒". */
export function formatMetricDuration(milliseconds: number): string {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) {
    return '';
  }
  const seconds = milliseconds / 1000;
  if (seconds < 60) {
    return (Math.round(seconds * 10) / 10).toString() + '秒';
  }
  const whole = Math.round(seconds);
  return Math.floor(whole / 60).toString() + '分' + (whole % 60).toString() + '秒';
}

/** Android `runDuration` for the completed-turn label (minimum one second). */
export function runDuration(elapsedMillis: number): string {
  const total = Math.floor(Math.max(elapsedMillis, 1000) / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor(total / 60) % 60;
  const seconds = total % 60;
  let text = '';
  if (hours > 0) {
    text += hours.toString() + '小时';
  }
  if (total >= 60) {
    text += minutes.toString() + '分';
  }
  return text + seconds.toString() + '秒';
}

/** Android `turnProcessLabel`; empty output means the summary is not renderable. */
export function turnProcessLabel(outcome: TurnOutcome, elapsedMillis: number): string {
  if (outcome === TurnOutcome.Running) {
    return '正在处理';
  }
  if (outcome === TurnOutcome.Stopped) {
    return '已停止';
  }
  if (outcome === TurnOutcome.Failed) {
    return '处理失败';
  }
  if (outcome === TurnOutcome.Completed) {
    return Number.isFinite(elapsedMillis) && elapsedMillis >= 0
      ? '已完成，用时 ' + runDuration(elapsedMillis)
      : '已完成';
  }
  return '';
}

/** Maps the Worker `turn/end.data.reason.kind` to Android's `ChatTurnState`. */
export function turnOutcomeOf(reasonKind: string): TurnOutcome {
  const kind = reasonKind.toLowerCase();
  if (kind === 'error' || kind === 'failed') {
    return TurnOutcome.Failed;
  }
  if (kind === 'cancelled' || kind === 'canceled' || kind === 'aborted'
    || kind === 'interrupted' || kind === 'stopped') {
    return TurnOutcome.Stopped;
  }
  return TurnOutcome.Completed;
}

/** Non-negative elapsed time between two Worker event clocks, or -1 when absent. */
export function elapsedMillis(startedAtMs: number, endedAtMs: number): number {
  if (!Number.isFinite(startedAtMs) || !Number.isFinite(endedAtMs)
    || startedAtMs < 0 || endedAtMs < 0) {
    return -1;
  }
  return endedAtMs >= startedAtMs ? endedAtMs - startedAtMs : -1;
}

/** A compact clock for the assistant tail; `''` hides a missing timestamp. */
export function clockLabel(timestampMillis: number): string {
  if (!Number.isFinite(timestampMillis) || timestampMillis <= 0) {
    return '';
  }
  const date = new Date(timestampMillis);
  const hours = date.getHours();
  const minutes = date.getMinutes();
  return (hours < 10 ? '0' + hours.toString() : hours.toString())
    + ':' + (minutes < 10 ? '0' + minutes.toString() : minutes.toString());
}
