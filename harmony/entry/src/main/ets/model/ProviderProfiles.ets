// SPDX-License-Identifier: MIT
// Pure provider-profile domain: the device record of the model services this
// host applies. One document holds many profiles and one selected id, mirroring
// Android `ProviderConfigStore` / iOS `PhoneModelProviderSettings`. A selected
// profile is the one the next host boot applies; a non-selected profile is an
// editable alternative.
//
// This module has no kit imports and no UI state: every rule below is a pure
// function so the same acceptance logic can be exercised without a device. The
// legacy single provider in `Settings.provider` is migrated into one profile on
// read with its id and AssetStore alias unchanged, so an existing key is not
// lost. Nothing here ever holds a credential value: a profile stores only the
// non-secret AssetStore alias (`credentialRef`) that the caller may resolve.
//
// The Worker projection below mirrors `plugins/phone-model-config` version 1:
// id, displayName, baseURL, model, optional models and optional reasoningEffort.
// Encoder, credentialRef and key state are host-only and never enter it.

export const PROVIDER_KEY_PREFIX: string = 'pdsh.model.key.';
export const PROVIDER_DEFAULT_MODEL: string = 'deepseek-flash';
export const PROVIDER_DEFAULT_ENCODER: string = 'deepseek';
export const PROVIDER_DEFAULT_BASE_URL: string = 'https://api.deepseek.com/anthropic';
export const PROVIDER_DEFAULT_EFFORT: string = 'high';
export const PROVIDER_MIN_KEY_LENGTH: number = 8;
export const PROVIDER_LAUNCH_FILE: string = 'model-launch.json';
export const PROVIDER_LAUNCH_VERSION: number = 1;

/** One model definition a profile exposes, including the optional source fields. */
export class ProviderModelRecord {
  id: string = '';
  name: string = '';
  /** Zero means "not stated"; Android stores an optional positive integer. */
  contextWindow: number = 0;
  /** Zero means "not stated"; Android stores an optional positive integer. */
  maxTokens: number = 0;
  textInput: boolean = true;
  imageInput: boolean = false;
  reasoningEffort: string = '';
}

/** The non-secret half of one model service. */
export class ProviderProfileRecord {
  id: string = '';
  name: string = '';
  baseUrl: string = '';
  model: string = '';
  encoder: string = PROVIDER_DEFAULT_ENCODER;
  effort: string = PROVIDER_DEFAULT_EFFORT;
  reasoningEffort: string = '';
  models: ProviderModelRecord[] = [];
  /** The AssetStore alias for this profile; empty means derive from `id`. */
  credentialRef: string = '';
  /** A UI fact read back from the store, never persisted as a credential. */
  keyConfigured: boolean = false;
}

/** The whole provider document: many profiles and one selected id. */
export class ProviderProfilesState {
  profiles: ProviderProfileRecord[] = [];
  selectedId: string = '';
}

/** The legacy single-service shape this document migrates from. */
export interface LegacyProvider {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  effort: string;
  encoder: string;
}

/** The stable AssetStore alias Android calls `provider.<id>`. */
export function providerAliasForId(id: string): string {
  let safe: string = id.trim();
  safe = safe.replace(/[^A-Za-z0-9._-]/g, '_');
  if (safe.length === 0) {
    safe = 'default';
  }
  return `${PROVIDER_KEY_PREFIX}${safe}`;
}

/** The alias this profile's key lives under; a migrated row keeps its legacy ref. */
export function providerCredentialAlias(profile: ProviderProfileRecord): string {
  const ref: string = profile.credentialRef.trim();
  return ref.length > 0 ? ref : providerAliasForId(profile.id);
}

export function cloneProviderModel(source: ProviderModelRecord): ProviderModelRecord {
  const next = new ProviderModelRecord();
  next.id = source.id;
  next.name = source.name;
  next.contextWindow = source.contextWindow;
  next.maxTokens = source.maxTokens;
  next.textInput = source.textInput;
  next.imageInput = source.imageInput;
  next.reasoningEffort = source.reasoningEffort;
  return next;
}

export function cloneProviderProfile(source: ProviderProfileRecord): ProviderProfileRecord {
  const next = new ProviderProfileRecord();
  next.id = source.id;
  next.name = source.name;
  next.baseUrl = source.baseUrl;
  next.model = source.model;
  next.encoder = source.encoder;
  next.effort = source.effort;
  next.reasoningEffort = source.reasoningEffort;
  next.credentialRef = source.credentialRef;
  next.keyConfigured = source.keyConfigured;
  next.models = [];
  for (let index = 0; index < source.models.length; index++) {
    next.models.push(cloneProviderModel(source.models[index]));
  }
  return next;
}

export function cloneProviderProfiles(source: ProviderProfileRecord[]): ProviderProfileRecord[] {
  const next: ProviderProfileRecord[] = [];
  for (let index = 0; index < source.length; index++) {
    next.push(cloneProviderProfile(source[index]));
  }
  return next;
}

export function cloneProviderState(source: ProviderProfilesState): ProviderProfilesState {
  const next = new ProviderProfilesState();
  next.profiles = cloneProviderProfiles(source.profiles);
  next.selectedId = source.selectedId;
  return next;
}

/** The one accepted endpoint shape: an absolute https URL with a host. */
export function providerEndpointRejection(raw: string): string {
  const text: string = raw.trim();
  if (text.length === 0) {
    return '模型服务地址不能为空';
  }
  const lower: string = text.toLowerCase();
  if (lower.startsWith('http://')) {
    return '密钥只会发往 https 地址；不支持明文 http';
  }
  if (!lower.startsWith('https://')) {
    return '模型服务地址必须是绝对 https URL';
  }
  if (text.includes('?') || text.includes('#')) {
    return '模型服务地址不能带查询参数或片段';
  }
  const rest: string = text.substring(8);
  let authority: string = rest;
  const slash: number = rest.indexOf('/');
  if (slash >= 0) {
    authority = rest.substring(0, slash);
  }
  if (authority.length === 0 || authority.includes('@') || authority.includes(' ') ||
    authority.includes('\t') || authority.includes('\\')) {
    return '模型服务地址格式不正确';
  }
  let host: string = authority;
  const colon: number = authority.lastIndexOf(':');
  if (colon >= 0) {
    host = authority.substring(0, colon);
    const portText: string = authority.substring(colon + 1);
    if (portText.length === 0 || !/^[0-9]+$/.test(portText)) {
      return '模型服务地址格式不正确';
    }
    const port: number = Number(portText);
    if (port <= 0 || port > 65535) {
      return '模型服务地址格式不正确';
    }
  }
  if (host.length === 0 || host.includes('[') || host.includes(']')) {
    return '模型服务地址格式不正确';
  }
  return '';
}

/** The pure acceptance rules the page's draft must satisfy before storage. */
export function providerProfileRejection(profile: ProviderProfileRecord): string {
  if (profile.id.trim().length === 0) {
    return '模型服务标识不能为空';
  }
  if (profile.name.trim().length === 0) {
    return '模型服务名称不能为空';
  }
  if (profile.model.trim().length === 0) {
    return '模型名不能为空';
  }
  return providerEndpointRejection(profile.baseUrl);
}

/** The key rule `HostSecure` enforces, checked before the write. */
export function providerKeyRejection(raw: string): string {
  const text: string = raw.trim();
  if (text.length < PROVIDER_MIN_KEY_LENGTH) {
    return '密钥需至少 8 个可打印 ASCII 字符，本次未保存';
  }
  for (let index = 0; index < text.length; index++) {
    const code: number = text.charCodeAt(index);
    if (code < 33 || code > 126) {
      return '密钥需至少 8 个可打印 ASCII 字符，本次未保存';
    }
  }
  return '';
}

function normalizedModel(source: ProviderModelRecord): ProviderModelRecord {
  const next = cloneProviderModel(source);
  next.id = next.id.trim();
  next.name = next.name.trim();
  next.reasoningEffort = next.reasoningEffort.trim();
  if (next.contextWindow < 0) {
    next.contextWindow = 0;
  }
  if (next.maxTokens < 0) {
    next.maxTokens = 0;
  }
  if (!next.textInput && !next.imageInput) {
    next.textInput = true;
  }
  return next;
}

/** Normalize a stored/drafted profile without inventing an endpoint or key. */
export function normalizeProviderProfile(source: ProviderProfileRecord): ProviderProfileRecord {
  const next = cloneProviderProfile(source);
  next.id = next.id.trim();
  next.name = next.name.trim();
  next.baseUrl = next.baseUrl.trim();
  next.model = next.model.trim();
  next.encoder = next.encoder.trim();
  if (next.encoder.length === 0) {
    next.encoder = PROVIDER_DEFAULT_ENCODER;
  }
  next.effort = next.effort.trim();
  if (next.effort.length === 0) {
    next.effort = PROVIDER_DEFAULT_EFFORT;
  }
  next.reasoningEffort = next.reasoningEffort.trim();
  if (next.reasoningEffort.length === 0) {
    next.reasoningEffort = next.effort;
  }
  next.credentialRef = next.credentialRef.trim();
  const models: ProviderModelRecord[] = [];
  for (let index = 0; index < next.models.length; index++) {
    const model: ProviderModelRecord = normalizedModel(next.models[index]);
    if (model.id.length > 0) {
      models.push(model);
    }
  }
  if (models.length === 0) {
    const fallback = new ProviderModelRecord();
    fallback.id = next.model.length > 0 ? next.model : PROVIDER_DEFAULT_MODEL;
    models.push(fallback);
  }
  next.models = models;
  next.keyConfigured = false;
  return next;
}

function findIndex(state: ProviderProfilesState, id: string): number {
  const wanted: string = id.trim();
  for (let index = 0; index < state.profiles.length; index++) {
    if (state.profiles[index].id === wanted) {
      return index;
    }
  }
  return -1;
}

export function findProviderProfile(state: ProviderProfilesState, id: string): ProviderProfileRecord | null {
  const index: number = findIndex(state, id);
  return index >= 0 ? state.profiles[index] : null;
}

/** The explicitly selected profile, or the first one as the document default. */
export function selectedProviderProfile(state: ProviderProfilesState): ProviderProfileRecord | null {
  const index: number = findIndex(state, state.selectedId);
  if (index >= 0) {
    return state.profiles[index];
  }
  return state.profiles.length > 0 ? state.profiles[0] : null;
}

/**
 * Migrate the legacy single-service settings into a profile document.
 *
 * A document that already has profiles is only normalized, never merged with
 * the legacy row. A migrated row keeps the legacy id and the legacy AssetStore
 * alias, so the key the person already saved keeps working.
 */
export function migrateProviderProfiles(
  legacy: LegacyProvider,
  profiles: ProviderProfileRecord[],
  selectedId: string
): ProviderProfilesState {
  const state = new ProviderProfilesState();
  if (profiles.length > 0) {
    for (let index = 0; index < profiles.length; index++) {
      const profile: ProviderProfileRecord = normalizeProviderProfile(profiles[index]);
      if (profile.id.length > 0 && findIndex(state, profile.id) < 0) {
        state.profiles.push(profile);
      }
    }
    state.selectedId = findIndex(state, selectedId) >= 0
      ? selectedId.trim()
      : (state.profiles.length > 0 ? state.profiles[0].id : '');
    return state;
  }
  const migrated = new ProviderProfileRecord();
  const legacyId: string = legacy.id.trim();
  migrated.id = legacyId.length > 0 ? legacyId : PROVIDER_DEFAULT_MODEL;
  migrated.name = legacy.name.trim().length > 0 ? legacy.name.trim() : 'DeepSeek（本机）';
  migrated.baseUrl = legacy.baseUrl.trim().length > 0 ? legacy.baseUrl.trim() : PROVIDER_DEFAULT_BASE_URL;
  migrated.model = legacy.model.trim().length > 0 ? legacy.model.trim() : PROVIDER_DEFAULT_MODEL;
  migrated.encoder = legacy.encoder.trim().length > 0 ? legacy.encoder.trim() : PROVIDER_DEFAULT_ENCODER;
  migrated.effort = legacy.effort.trim().length > 0 ? legacy.effort.trim() : PROVIDER_DEFAULT_EFFORT;
  migrated.reasoningEffort = migrated.effort;
  migrated.credentialRef = providerAliasForId(migrated.id);
  const model = new ProviderModelRecord();
  model.id = migrated.model;
  migrated.models = [model];
  state.profiles = [migrated];
  state.selectedId = migrated.id;
  return state;
}

/** Upsert one validated profile; a new profile is selected by default. */
export function withUpsertedProviderProfile(
  state: ProviderProfilesState,
  profile: ProviderProfileRecord,
  selectAsDefault: boolean
): ProviderProfilesState {
  const next: ProviderProfilesState = cloneProviderState(state);
  const normalized: ProviderProfileRecord = normalizeProviderProfile(profile);
  const index: number = findIndex(next, normalized.id);
  if (index >= 0) {
    next.profiles[index] = normalized;
  } else {
    next.profiles.push(normalized);
  }
  if (selectAsDefault || findIndex(next, next.selectedId) < 0) {
    next.selectedId = normalized.id;
  }
  return next;
}

/** Make an existing profile the selected one; null when it no longer exists. */
export function withSelectedProviderProfile(
  state: ProviderProfilesState,
  id: string
): ProviderProfilesState | null {
  if (findIndex(state, id) < 0) {
    return null;
  }
  const next: ProviderProfilesState = cloneProviderState(state);
  next.selectedId = id.trim();
  return next;
}

/** Remove one profile; the first remaining row becomes selected when needed. */
export function withDeletedProviderProfile(
  state: ProviderProfilesState,
  id: string
): ProviderProfilesState {
  const next: ProviderProfilesState = cloneProviderState(state);
  const remaining: ProviderProfileRecord[] = [];
  for (let index = 0; index < next.profiles.length; index++) {
    if (next.profiles[index].id !== id.trim()) {
      remaining.push(next.profiles[index]);
    }
  }
  next.profiles = remaining;
  if (findIndex(next, next.selectedId) < 0) {
    next.selectedId = remaining.length > 0 ? remaining[0].id : '';
  }
  return next;
}

function publicModel(source: ProviderModelRecord): Record<string, Object> {
  const row: Record<string, Object> = {};
  row['id'] = source.id;
  if (source.name.length > 0) {
    row['name'] = source.name;
  }
  if (source.contextWindow > 0) {
    row['contextWindow'] = source.contextWindow;
  }
  if (source.maxTokens > 0) {
    row['maxTokens'] = source.maxTokens;
  }
  row['textInput'] = source.textInput;
  row['imageInput'] = source.imageInput;
  if (source.reasoningEffort.length > 0) {
    row['reasoningEffort'] = source.reasoningEffort;
  }
  return row;
}

function publicProvider(source: ProviderProfileRecord): Record<string, Object> {
  const row: Record<string, Object> = {};
  row['id'] = source.id;
  row['displayName'] = source.name;
  row['baseURL'] = source.baseUrl;
  row['model'] = source.model;
  const models: Object[] = [];
  for (let index = 0; index < source.models.length; index++) {
    models.push(publicModel(source.models[index]));
  }
  if (models.length > 0) {
    row['models'] = models;
  }
  const effort: string = source.reasoningEffort.length > 0 ? source.reasoningEffort : source.effort;
  if (effort.length > 0) {
    row['reasoningEffort'] = effort;
  }
  return row;
}

/**
 * The non-secret document the Rust boot reader publishes to the Worker.
 *
 * It mirrors `phone-model-config` version 1 exactly. Only the selected
 * provider's `model` is also duplicated to `PDSH_MODEL_NAME`; the selected
 * profile's `baseURL` stays in this document while model traffic continues to
 * use the native loopback origin.
 *
 * `officialWebTools` is a host-private root grant, never part of the public
 * provider rows. It is present only when the host froze the official
 * Search/Fetch capability on for this start (eligible model base plus a live,
 * readable model key); absent or false therefore means disabled to the reader.
 */
export function providerLaunchDocument(state: ProviderProfilesState,
  officialWebTools?: boolean): string {
  const selected: ProviderProfileRecord | null = selectedProviderProfile(state);
  if (selected === null) {
    return '';
  }
  const providers: Object[] = [];
  for (let index = 0; index < state.profiles.length; index++) {
    providers.push(publicProvider(state.profiles[index]));
  }
  const root: Record<string, Object> = {};
  root['version'] = PROVIDER_LAUNCH_VERSION;
  root['selectedId'] = selected.id;
  root['providers'] = providers;
  if (officialWebTools === true) {
    root['officialWebTools'] = true;
  }
  return JSON.stringify(root);
}

/** The selected profile's model id, or empty when there is no profile. */
export function providerLaunchSelectedModel(state: ProviderProfilesState): string {
  const selected: ProviderProfileRecord | null = selectedProviderProfile(state);
  return selected === null ? '' : selected.model;
}
