// SPDX-License-Identifier: MIT
// Pure native page-stack vocabulary.
//
// Android's host keeps navigation in `PdshNavState` (PdshRoute.kt): a plain list
// of routes, with `push`, a root-stable `pop`, and the concrete route carrying
// its own id / file context. This module is the Harmony equivalent: a value
// stack of typed frames plus the few pure operations the host needs. It never
// talks to the engine, the clock or the filesystem, and every operation returns
// a value so the caller can apply it to the snapshot without re-entering
// `navigate` (which would push the route a second time).
import { Page } from './Contracts';

/**
 * One addressable page on the native stack.
 *
 * `id` names the resource the page opened (session, folder, preview target);
 * `filePath` carries the directory context of a Files page or the parent
 * directory a Preview must return to. Keeping both makes back a pure pop: the
 * previous frame already knows which directory it was showing.
 */
export class NavigationFrame {
  page: Page = Page.Chat;
  id: string = '';
  filePath: string = '';

  constructor(page: Page, id?: string, filePath?: string) {
    this.page = page;
    this.id = id === undefined ? '' : id;
    this.filePath = filePath === undefined ? '' : filePath;
  }

  clone(): NavigationFrame {
    return new NavigationFrame(this.page, this.id, this.filePath);
  }

  matches(other: NavigationFrame): boolean {
    return this.page === other.page && this.id === other.id && this.filePath === other.filePath;
  }
}

/** The stack floor: 对话 is the root native face and can never be popped away. */
export function navRootFrame(): NavigationFrame {
  return new NavigationFrame(Page.Chat);
}

/** A fresh stack containing only the root face. Used by 新会话/打开会话. */
export function navReset(): NavigationFrame[] {
  return [navRootFrame()];
}

/** The visible frame; an empty value is repaired to the root instead of crashing. */
export function navCurrentFrame(stack: NavigationFrame[]): NavigationFrame {
  if (stack.length === 0) {
    return navRootFrame();
  }
  return stack[stack.length - 1];
}

/** True when back pops a page instead of leaving the app. */
export function navCanGoBack(stack: NavigationFrame[]): boolean {
  return stack.length > 1;
}

/**
 * Pushes one frame, unless it is already the visible frame.
 *
 * Repeated taps on the same row (or a retried navigation after a failed body
 * load) must not grow the stack with identical frames, matching the Android
 * navigator's single-push intent while still allowing A -> B -> A nesting.
 */
export function navPushFrame(stack: NavigationFrame[], frame: NavigationFrame): NavigationFrame[] {
  if (navCurrentFrame(stack).matches(frame)) {
    return stack;
  }
  const next: NavigationFrame[] = [];
  for (let index = 0; index < stack.length; index++) {
    next.push(stack[index].clone());
  }
  next.push(frame.clone());
  return next;
}

/** Pops one level; the root is a fixed point. */
export function navPopFrame(stack: NavigationFrame[]): NavigationFrame[] {
  if (stack.length <= 1) {
    return stack;
  }
  const next: NavigationFrame[] = [];
  for (let index = 0; index < stack.length - 1; index++) {
    next.push(stack[index].clone());
  }
  return next;
}

/** The 工作区文件树 route: its directory is both the target and the parent context. */
export function navFilesFrame(path: string): NavigationFrame {
  return new NavigationFrame(Page.Files, path, path);
}

/**
 * The 文件预览 route. `directory` is the Files page the preview was opened
 * from, so 返回 re-lists that parent instead of dropping to 对话.
 */
export function navPreviewFrame(path: string, directory: string): NavigationFrame {
  const parent = directory.length > 0 ? directory : navParentPath(path);
  return new NavigationFrame(Page.Preview, path, parent);
}

/** Parent directory of one workspace path; root and relative names stay stable. */
export function navParentPath(path: string): string {
  let value = path;
  while (value.length > 1 && value.endsWith('/')) {
    value = value.substring(0, value.length - 1);
  }
  if (value.length === 0) {
    return '';
  }
  const separator = value.lastIndexOf('/');
  if (separator < 0) {
    return '';
  }
  if (separator === 0) {
    return '/';
  }
  return value.substring(0, separator);
}
