// SPDX-License-Identifier: MIT
// The composer's live session controls (model route and current permission).
// This module is pure: it only reads the Worker's catalog/projection shapes and
// never reports a selection the Worker did not return. HostService owns the RPC
// calls; ChatView renders exactly these values.
export class SessionModelEffort {
  id: string = '';
  name: string = '';
}

export class SessionModelOption {
  provider: string = '';
  model: string = '';
  efforts: SessionModelEffort[] = [];
  defaultEffort: string = '';
}

export class SessionModelSelection {
  provider: string = '';
  model: string = '';
  effort: string = '';
}

export class SessionPermissionOption {
  id: string = '';
  title: string = '';
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

function asArray(value: Object | null | undefined): Object[] {
  if (value === null || value === undefined || !Array.isArray(value)) {
    return [];
  }
  return value as Object[];
}

function textOf(record: Record<string, Object> | null, key: string): string {
  if (record === null) {
    return '';
  }
  const value: Object | undefined = record[key];
  return typeof value === 'string' ? value as string : '';
}

/** DSH effort ids mapped the way Android's `effortTitle` maps them. */
export function effortLabel(id: string, name: string): string {
  const lower = id.toLowerCase();
  if (lower === 'off' || lower === 'none' || lower === 'disabled') {
    return '关';
  }
  if (lower === 'low' || lower === 'minimal') {
    return '低';
  }
  if (lower === 'medium' || lower === 'mid') {
    return '中';
  }
  if (lower === 'high') {
    return '高';
  }
  if (lower === 'max' || lower === 'maximum' || lower === 'xhigh') {
    return '最高';
  }
  const trimmed = name.trim();
  return trimmed.length > 0 ? trimmed : id;
}

/** DSH permission ids mapped the way Android's `accessModeTitle` maps them. */
export function permissionTitle(id: string, name: string): string {
  if (id === 'read-only' || id === 'phone-read-only') {
    return '仅可查看';
  }
  if (id === 'workspace-write' || id === 'phone') {
    return '工作区内修改';
  }
  if (id === 'danger-full-access' || id === 'bypassPermissions') {
    return '完全权限';
  }
  if (id === 'auto') {
    return '自动判断';
  }
  if (id === 'plan') {
    return '只出方案';
  }
  if (id === 'acceptEdits') {
    return '改前不问';
  }
  const trimmed = name.trim();
  return trimmed.length > 0 ? trimmed : id;
}

/**
 * `session/modelCatalog`: groups[{id, models[{id, reasoning:{efforts[{id,name}],
 * defaultEffort}}]}]. Missing or malformed rows are skipped, never invented.
 */
export function modelOptionsFromCatalog(root: Record<string, Object> | null): SessionModelOption[] {
  const options: SessionModelOption[] = [];
  const groups = asArray(root === null ? null : root['groups']);
  for (let groupIndex = 0; groupIndex < groups.length; groupIndex++) {
    const group = asRecord(groups[groupIndex]);
    if (group === null) {
      continue;
    }
    const provider = textOf(group, 'id');
    if (provider.length === 0) {
      continue;
    }
    const models = asArray(group['models']);
    for (let modelIndex = 0; modelIndex < models.length; modelIndex++) {
      const model = asRecord(models[modelIndex]);
      if (model === null) {
        continue;
      }
      const modelId = textOf(model, 'id');
      if (modelId.length === 0) {
        continue;
      }
      const option = new SessionModelOption();
      option.provider = provider;
      option.model = modelId;
      const reasoning = asRecord(model['reasoning']);
      if (reasoning !== null) {
        option.defaultEffort = textOf(reasoning, 'defaultEffort');
        const efforts = asArray(reasoning['efforts']);
        for (let effortIndex = 0; effortIndex < efforts.length; effortIndex++) {
          const effort = asRecord(efforts[effortIndex]);
          if (effort === null) {
            continue;
          }
          const effortId = textOf(effort, 'id');
          if (effortId.length === 0) {
            continue;
          }
          const item = new SessionModelEffort();
          item.id = effortId;
          item.name = effortLabel(effortId, textOf(effort, 'name'));
          option.efforts.push(item);
        }
      }
      options.push(option);
    }
  }
  return options;
}

/**
 * `session/projections` values.modelSelection.next|lastUsed
 * {provider, model, reasoningEffort}. An unreported selection is empty: the
 * composer then keeps the configured-provider boot overlay, never a stale value.
 */
export function selectionFromProjections(root: Record<string, Object> | null): SessionModelSelection {
  const selection = new SessionModelSelection();
  const values = asRecord(root === null ? null : root['values']);
  if (values === null) {
    return selection;
  }
  const modelSelection = asRecord(values['modelSelection']);
  if (modelSelection === null) {
    return selection;
  }
  let chosen = asRecord(modelSelection['next']);
  if (chosen === null) {
    chosen = asRecord(modelSelection['lastUsed']);
  }
  if (chosen === null) {
    return selection;
  }
  selection.provider = textOf(chosen, 'provider');
  selection.model = textOf(chosen, 'model');
  selection.effort = textOf(chosen, 'reasoningEffort');
  return selection;
}

/** `session/projections` values.permissions.currentValue. */
export function permissionFromProjections(root: Record<string, Object> | null): string {
  const values = asRecord(root === null ? null : root['values']);
  if (values === null) {
    return '';
  }
  const permissions = asRecord(values['permissions']);
  return textOf(permissions, 'currentValue');
}

/** `permissionPresets/catalog` options[{value,name}], `defaultOptions` as fallback. */
export function permissionOptionsFromCatalog(root: Record<string, Object> | null): SessionPermissionOption[] {
  const options: SessionPermissionOption[] = [];
  let rows = asArray(root === null ? null : root['options']);
  if (rows.length === 0) {
    rows = asArray(root === null ? null : root['defaultOptions']);
  }
  for (let index = 0; index < rows.length; index++) {
    const row = asRecord(rows[index]);
    if (row === null) {
      continue;
    }
    const id = textOf(row, 'value');
    if (id.length === 0) {
      continue;
    }
    const item = new SessionPermissionOption();
    item.id = id;
    item.title = permissionTitle(id, textOf(row, 'name'));
    options.push(item);
  }
  return options;
}

/** Mirrors Android `selectChatModel`: provider/model route plus effort carry-over. */
export function resolveModelSelection(
  options: SessionModelOption[],
  current: SessionModelSelection,
  provider: string,
  model: string,
  explicitEffort: string
): SessionModelSelection | null {
  let route: SessionModelOption | null = null;
  for (let index = 0; index < options.length; index++) {
    if (options[index].model === model && options[index].provider === provider) {
      route = options[index];
      break;
    }
  }
  if (route === null) {
    for (let index = 0; index < options.length; index++) {
      if (options[index].model === model) {
        route = options[index];
        break;
      }
    }
  }
  if (route === null) {
    return null;
  }
  let kept = explicitEffort.trim();
  if (kept.length === 0 && current.effort.length > 0 && current.model !== route.model &&
    hasEffort(route, current.effort)) {
    kept = current.effort;
  }
  if (kept.length > 0 && !hasEffort(route, kept)) {
    return null;
  }
  const selection = new SessionModelSelection();
  selection.provider = route.provider;
  selection.model = route.model;
  selection.effort = kept;
  return selection;
}

export function hasEffort(option: SessionModelOption, effortId: string): boolean {
  for (let index = 0; index < option.efforts.length; index++) {
    if (option.efforts[index].id === effortId) {
      return true;
    }
  }
  return false;
}
