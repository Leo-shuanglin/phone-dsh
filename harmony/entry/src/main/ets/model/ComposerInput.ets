// SPDX-License-Identifier: MIT
// The composer's registered slash-command model. This module is pure: it reads
// the Worker's `commands/list` shape and decides whether a submitted line is a
// real registered command. HostService owns the RPC calls; ChatView renders
// exactly this catalog.
export class ComposerCommand {
  name: string = '';
  description: string = '';
  hasInput: boolean = false;
}

export class ComposerCommandExecution {
  accepted: boolean = false;
  commandId: string = '';
  kind: string = '';
  text: string = '';
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

function catalogRows(value: Object | null | undefined): Object[] {
  if (value === null || value === undefined) {
    return [];
  }
  if (Array.isArray(value)) {
    return value as Object[];
  }
  const root = asRecord(value);
  if (root === null) {
    return [];
  }
  const direct = root['commands'];
  if (Array.isArray(direct)) {
    return direct as Object[];
  }
  const wrapped = root['value'];
  if (Array.isArray(wrapped)) {
    return wrapped as Object[];
  }
  return [];
}

/**
 * `commands/list` returns either the array itself or `{commands: array}` /
 * `{value: array}`. Rows without a non-blank name or a description are skipped,
 * matching Android's catalog parser; no command is invented.
 */
export function commandsFromCatalog(value: Object | null | undefined): ComposerCommand[] {
  const rows = catalogRows(value);
  const commands: ComposerCommand[] = [];
  for (let index = 0; index < rows.length; index++) {
    const row = asRecord(rows[index]);
    if (row === null) {
      continue;
    }
    const nameValue = row['name'];
    const descriptionValue = row['description'];
    if (typeof nameValue !== 'string' || typeof descriptionValue !== 'string') {
      continue;
    }
    const name = (nameValue as string).trim();
    if (name.length === 0) {
      continue;
    }
    const command = new ComposerCommand();
    command.name = name;
    command.description = descriptionValue as string;
    const input = row['input'];
    command.hasInput = input !== null && input !== undefined;
    commands.push(command);
  }
  return commands;
}

/** Android `typed.removePrefix("/").substringBefore(' ')`, on a trimmed line. */
export function commandNameOf(line: string): string {
  const typed = line.trim();
  const body = typed.startsWith('/') ? typed.substring(1) : typed;
  const separator = body.indexOf(' ');
  return separator < 0 ? body : body.substring(0, separator);
}

/**
 * Android `submitChat`: only a trimmed line starting with `/`, with no
 * attachments, whose first token names a real catalog command is intercepted.
 * Unknown slash text and absolute paths stay ordinary prompts.
 */
export function isRegisteredSlashCommand(line: string, hasAttachments: boolean,
  commands: ComposerCommand[]): boolean {
  const typed = line.trim();
  if (!typed.startsWith('/') || hasAttachments) {
    return false;
  }
  const name = commandNameOf(typed);
  if (name.length === 0) {
    return false;
  }
  for (let index = 0; index < commands.length; index++) {
    if (commands[index].name === name) {
      return true;
    }
  }
  return false;
}

/** Prefix suggestions for the current single-token slash draft, from the catalog only. */
export function commandSuggestions(draft: string, commands: ComposerCommand[]): ComposerCommand[] {
  const typed = draft.trim();
  if (!typed.startsWith('/')) {
    return [];
  }
  const rest = typed.substring(1);
  if (rest.indexOf(' ') >= 0 || rest.indexOf('\t') >= 0) {
    return [];
  }
  const query = rest.toLowerCase();
  const suggestions: ComposerCommand[] = [];
  for (let index = 0; index < commands.length; index++) {
    const command = commands[index];
    if (query.length === 0 || command.name.toLowerCase().startsWith(query)) {
      suggestions.push(command);
    }
  }
  return suggestions;
}

/**
 * `commands/execute` response: `result.kind` must exist and differ from
 * `error`; `commandId` and `result.text` are the acknowledged facts.
 */
export function parseCommandExecution(value: Object | null | undefined): ComposerCommandExecution {
  const execution = new ComposerCommandExecution();
  const root = asRecord(value);
  if (root === null) {
    return execution;
  }
  const idValue = root['commandId'];
  if (typeof idValue === 'string') {
    execution.commandId = idValue as string;
  }
  const result = asRecord(root['result']);
  if (result === null) {
    return execution;
  }
  const kindValue = result['kind'];
  if (typeof kindValue === 'string') {
    execution.kind = kindValue as string;
  }
  const textValue = result['text'];
  if (typeof textValue === 'string') {
    execution.text = textValue as string;
  }
  execution.accepted = execution.kind.length > 0 && execution.kind !== 'error';
  return execution;
}
