// SPDX-License-Identifier: MIT
// Pure `@`-reference model. Android reads these candidates locally: workspace
// relative file paths (cap 200) and the titles of the other non-archived chats
// (trimmed, distinct, cap 100). A choice replaces the whole draft with
// `<relative-path> ` for a file or `@<title> ` for a conversation; there is no
// structured reference payload on the wire, and this module invents none.

/** Android `CHAT_MENTION_FILE_LIMIT`: candidates read from the workspace. */
export const REFERENCE_FILE_LIMIT: number = 200;
/** Android `CHAT_MENTION_SESSION_LIMIT`: other-chat title candidates. */
export const REFERENCE_SESSION_LIMIT: number = 100;
/** Android `MAX_COMMANDS`: file rows the in-place `@` menu shows. */
export const REFERENCE_FILE_MENU_LIMIT: number = 6;
/** Android `MAX_SESSIONS`: conversation rows the in-place `@` menu shows. */
export const REFERENCE_SESSION_MENU_LIMIT: number = 3;
/**
 * The Worker `$listFiles` command reads one directory, not a tree. HostService
 * walks the verified workspace with a bounded scan; this keeps that walk finite
 * even when the cap of 200 regular files is never reached.
 */
export const REFERENCE_DIRECTORY_LIMIT: number = 200;

/**
 * The only session fields the reference model reads. HostService maps its
 * durable session records into this fact class, so the pure model needs no
 * dependency on Contracts and stays testable on its own.
 */
export class ComposerReferenceSessionFact {
  id: string = '';
  title: string = '';
  archived: boolean = false;
}

/** One projected `@` menu. Empty `files`/`sessions` with `open` false is closed. */
export class ComposerReferenceMenu {
  open: boolean = false;
  token: string = '';
  files: string[] = [];
  sessions: string[] = [];
}

function isWhitespace(ch: string): boolean {
  return ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r' ||
    ch === '\f' || ch === '\v' || ch === '\u00a0' || ch === '\u3000';
}

/**
 * Android `triggerToken(draft, '@')`: the trimmed draft must start with `@` and
 * the rest must have no whitespace. A non-trigger returns null, never ''.
 */
export function referenceToken(draft: string): string | null {
  const trimmed: string = draft.trim();
  if (!trimmed.startsWith('@')) {
    return null;
  }
  const body: string = trimmed.substring(1);
  for (let index: number = 0; index < body.length; index++) {
    if (isWhitespace(body.charAt(index))) {
      return null;
    }
  }
  return body;
}

/**
 * A workspace path as the relative path the `@` menu inserts. Paths outside the
 * verified root (or the root itself) yield '' and are never offered.
 */
export function referenceRelativePath(root: string, path: string): string {
  if (root.length === 0 || path.length === 0) {
    return '';
  }
  const base: string = root.endsWith('/') ? root.substring(0, root.length - 1) : root;
  const prefix: string = base + '/';
  if (!path.startsWith(prefix)) {
    return '';
  }
  return path.substring(prefix.length);
}

/**
 * Android `chatMentionSessionTitles`: non-archived records other than the one on
 * screen, trimmed non-empty titles, exact distinct, capped in record order.
 */
export function referenceSessionTitles(records: ComposerReferenceSessionFact[],
  currentSessionId: string, limit: number = REFERENCE_SESSION_LIMIT): string[] {
  const titles: string[] = [];
  for (let index: number = 0; index < records.length && titles.length < limit; index++) {
    const record: ComposerReferenceSessionFact = records[index];
    if (record.archived || record.id === currentSessionId) {
      continue;
    }
    const title: string = record.title.trim();
    if (title.length === 0 || titles.indexOf(title) >= 0) {
      continue;
    }
    titles.push(title);
  }
  return titles;
}

function containsIgnoreCase(value: string, token: string): boolean {
  return value.toLowerCase().indexOf(token.toLowerCase()) >= 0;
}

/**
 * Android `referenceMenu()` for an already-open trigger token. An empty token
 * shows the first files only; conversations appear once a token is typed.
 */
export function referenceMenuFromToken(token: string, files: string[], sessions: string[],
  fileLimit: number = REFERENCE_FILE_MENU_LIMIT,
  sessionLimit: number = REFERENCE_SESSION_MENU_LIMIT): ComposerReferenceMenu {
  const menu = new ComposerReferenceMenu();
  menu.open = true;
  menu.token = token;
  const matchedFiles: string[] = [];
  for (let index: number = 0; index < files.length && matchedFiles.length < fileLimit; index++) {
    if (token.length === 0 || containsIgnoreCase(files[index], token)) {
      matchedFiles.push(files[index]);
    }
  }
  menu.files = matchedFiles;
  const matchedSessions: string[] = [];
  if (token.length > 0) {
    for (let index: number = 0; index < sessions.length && matchedSessions.length < sessionLimit; index++) {
      if (containsIgnoreCase(sessions[index], token)) {
        matchedSessions.push(sessions[index]);
      }
    }
  }
  menu.sessions = matchedSessions;
  return menu;
}

/** Android `referenceMenu()` as a whole: closed while the draft is no trigger. */
export function referenceMenuForDraft(draft: string, files: string[],
  sessions: string[]): ComposerReferenceMenu {
  const token: string | null = referenceToken(draft);
  if (token === null) {
    return new ComposerReferenceMenu();
  }
  return referenceMenuFromToken(token, files, sessions);
}

/**
 * Android `ChatPage`: `if (value in menu.sessions) "@$value " else "$value "`.
 * A file path that happens to equal a conversation title follows Android and
 * becomes the `@` form; callers pass the exact values the menu projected.
 */
export function referenceSelection(value: string, sessions: string[]): string {
  for (let index: number = 0; index < sessions.length; index++) {
    if (sessions[index] === value) {
      return '@' + value + ' ';
    }
  }
  return value + ' ';
}
