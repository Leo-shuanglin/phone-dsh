// SPDX-License-Identifier: MIT
// Pure resource-identity policy for Markdown images and resource links.
//
// Ported from the canonical Android sources (not re-derived):
// - surface/MarkdownImage.kt: remote/local image source rules, 8 MiB body,
//   3 redirects, 2048 px decode bound.
// - nav/MarkdownResourcePreview.kt: file/sandbox/dsh-resource session rules.
// - surface/preview/PreviewMarkdown.kt: safe-root resolution is still enforced
//   by the Worker VFS; this module only computes the identity/path to ask for.
//
// No SDK imports: this file must stay importable by the Node pure tests.

export enum ResourceKind {
  Remote = 'remote',
  Workspace = 'workspace',
  Session = 'session',
  Refused = 'refused'
}

export enum ResourceRefusal {
  Invalid = 'invalid',
  AbsoluteResource = 'absolute-resource',
  UnsafePath = 'unsafe-path',
  MissingPath = 'missing-path',
  NoCurrentSession = 'no-current-session',
  DifferentSession = 'different-session',
  NoVerifiedWorkspace = 'no-verified-workspace',
  UnsupportedScheme = 'unsupported-scheme'
}

export class ResourceContext {
  /** The Worker session id currently selected by the host, or ''. */
  currentWorkerSessionId: string = '';
  /** Worker session ids the host has a verified workspace mapping for. */
  knownWorkerSessionIds: string[] = [];
  /** True only when the Worker VFS workspace is ready for the active session. */
  hasVerifiedWorkspace: boolean = false;
}

export class ResolvedResource {
  kind: ResourceKind = ResourceKind.Refused;
  source: string = '';
  /** Absolute Worker VFS path, or the remote URL when kind is Remote. */
  target: string = '';
  relativePath: string = '';
  sessionId: string = '';
  reason: ResourceRefusal = ResourceRefusal.Invalid;
}

export class UriParts {
  scheme: string = '';
  host: string = '';
  path: string = '';
  userInfo: string = '';
  raw: string = '';
}

export class DecodeSize {
  width: number = 0;
  height: number = 0;
}

/** Android `MAX_REMOTE_IMAGE_BYTES`. */
export const MAX_REMOTE_IMAGE_BYTES: number = 8 * 1024 * 1024;
/** Android `MAX_REDIRECTS`; only http/https hops are followed, each revalidated. */
export const MAX_REMOTE_REDIRECTS: number = 3;
/** Android `MAX_DECODED_IMAGE_DIMENSION`. */
export const MAX_DECODED_IMAGE_DIMENSION: number = 2048;
/** image-resource-4 frozen Worker binary snapshot limits. */
export const MAX_BINARY_SNAPSHOT_BYTES: number = 16 * 1024 * 1024;
export const MAX_BINARY_READERS: number = 4;
export const BINARY_SNAPSHOT_TTL_MS: number = 60000;
export const BINARY_CHUNK_BYTES: number = 256 * 1024;
export const WORKSPACE_ROOT: string = '/dsh/workspace';

const IMAGE_EXTENSIONS: string[] = [
  'png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'heic', 'heif', 'ico', 'avif'
];

function hasControlCharacter(text: string): boolean {
  for (let index: number = 0; index < text.length; index++) {
    const code: number = text.charCodeAt(index);
    if (code < 0x20 || code === 0x7f) {
      return true;
    }
  }
  return false;
}

/** Minimal URI splitter; deliberately does not percent-decode so `%2e%2e` stays a filename. */
export function parseUri(text: string): UriParts | null {
  const trimmed: string = text.trim();
  if (trimmed.length === 0 || hasControlCharacter(trimmed)) {
    return null;
  }
  const colon: number = trimmed.indexOf(':');
  if (colon <= 0) {
    return null;
  }
  const scheme: string = trimmed.substring(0, colon).toLowerCase();
  if (!/^[a-z][a-z0-9+.-]*$/.test(scheme)) {
    return null;
  }
  const rest: string = trimmed.substring(colon + 1);
  const parts = new UriParts();
  parts.scheme = scheme;
  parts.raw = trimmed;
  if (rest.startsWith('//')) {
    const slash: number = rest.indexOf('/', 2);
    const authority: string = slash < 0 ? rest.substring(2) : rest.substring(2, slash);
    parts.path = slash < 0 ? '' : rest.substring(slash);
    const at: number = authority.lastIndexOf('@');
    if (at >= 0) {
      parts.userInfo = authority.substring(0, at);
      parts.host = authority.substring(at + 1);
    } else {
      parts.host = authority;
    }
    const port: number = parts.host.lastIndexOf(':');
    if (port > 0 && parts.host.indexOf(']') < 0) {
      parts.host = parts.host.substring(0, port);
    }
  } else {
    parts.path = rest;
  }
  return parts;
}

export function isSafeRelativePath(path: string): boolean {
  const trimmed: string = path.trim();
  if (trimmed.length === 0 || hasControlCharacter(trimmed)) {
    return false;
  }
  if (trimmed.startsWith('/') || trimmed.startsWith('\\')) {
    return false;
  }
  const components: string[] = trimmed.split('/');
  for (let index: number = 0; index < components.length; index++) {
    const component: string = components[index];
    if (component.length === 0 || component === '.' || component === '..') {
      return false;
    }
  }
  return true;
}

/** `/dsh/workspace/a.png` -> `a.png`; null when outside the mounted workspace. */
export function workspaceRelativePath(path: string): string | null {
  const clean: string = path.split('#')[0].split('?')[0];
  if (!clean.startsWith(WORKSPACE_ROOT + '/')) {
    return null;
  }
  const relative: string = clean.substring(WORKSPACE_ROOT.length + 1);
  return isSafeRelativePath(relative) ? relative : null;
}

export function workerPathFor(relative: string): string {
  return WORKSPACE_ROOT + '/' + relative;
}

function refused(source: string, reason: ResourceRefusal): ResolvedResource {
  const value = new ResolvedResource();
  value.kind = ResourceKind.Refused;
  value.source = source;
  value.reason = reason;
  return value;
}

function workspaceResource(source: string, relative: string): ResolvedResource {
  const value = new ResolvedResource();
  value.kind = ResourceKind.Workspace;
  value.source = source;
  value.relativePath = relative;
  value.target = workerPathFor(relative);
  return value;
}

function sessionResource(source: string, sessionId: string, relative: string): ResolvedResource {
  const value = new ResolvedResource();
  value.kind = ResourceKind.Session;
  value.source = source;
  value.sessionId = sessionId;
  value.relativePath = relative;
  value.target = workerPathFor(relative);
  return value;
}

/** Resolves a Markdown image/link source into a Worker identity or a visible refusal. */
export function resolveMarkdownResource(source: string, context: ResourceContext): ResolvedResource {
  const raw: string = source.trim();
  if (raw.length === 0 || hasControlCharacter(raw)) {
    return refused(raw, ResourceRefusal.Invalid);
  }
  const uri: UriParts | null = parseUri(raw);
  if (uri === null) {
    if (raw.startsWith('/dsh/workspace/')) {
      const relative: string | null = workspaceRelativePath(raw);
      if (relative === null) {
        return refused(raw, ResourceRefusal.UnsafePath);
      }
      return context.hasVerifiedWorkspace
        ? workspaceResource(raw, relative) : refused(raw, ResourceRefusal.NoVerifiedWorkspace);
    }
    if (raw.startsWith('/') || raw.startsWith('\\')) {
      return refused(raw, ResourceRefusal.AbsoluteResource);
    }
    const normal: string = raw.split('\\').join('/');
    if (!isSafeRelativePath(normal)) {
      return refused(raw, ResourceRefusal.UnsafePath);
    }
    return context.hasVerifiedWorkspace
      ? workspaceResource(raw, normal) : refused(raw, ResourceRefusal.NoVerifiedWorkspace);
  }
  if (uri.scheme === 'http' || uri.scheme === 'https') {
    if (uri.host.length === 0 || uri.userInfo.length > 0 || uri.raw.indexOf('#') === 0) {
      return refused(raw, ResourceRefusal.Invalid);
    }
    const remote = new ResolvedResource();
    remote.kind = ResourceKind.Remote;
    remote.source = raw;
    remote.target = raw;
    return remote;
  }
  if (uri.scheme === 'file') {
    if (uri.userInfo.length > 0) {
      return refused(raw, ResourceRefusal.Invalid);
    }
    const relative: string | null = workspaceRelativePath(uri.path);
    if (relative === null) {
      return refused(raw, ResourceRefusal.AbsoluteResource);
    }
    return context.hasVerifiedWorkspace
      ? workspaceResource(raw, relative) : refused(raw, ResourceRefusal.NoVerifiedWorkspace);
  }
  if (uri.scheme === 'sandbox') {
    const path: string = uri.path;
    let relative: string;
    if (path.startsWith(WORKSPACE_ROOT + '/')) {
      const stripped: string | null = workspaceRelativePath(path);
      if (stripped === null) {
        return refused(raw, ResourceRefusal.UnsafePath);
      }
      relative = stripped;
    } else {
      relative = path.startsWith('/') ? path.substring(1) : path;
      if (!isSafeRelativePath(relative)) {
        return refused(raw, ResourceRefusal.UnsafePath);
      }
    }
    return context.hasVerifiedWorkspace
      ? workspaceResource(raw, relative) : refused(raw, ResourceRefusal.NoVerifiedWorkspace);
  }
  if (uri.scheme === 'dsh-resource') {
    if (uri.host.toLowerCase() !== 'file') {
      return refused(raw, ResourceRefusal.Invalid);
    }
    const segments: string[] = uri.path.split('/').filter((part: string) => part.length > 0);
    if (segments.length === 0) {
      return refused(raw, ResourceRefusal.Invalid);
    }
    if (segments[0] === 'absolute') {
      return refused(raw, ResourceRefusal.AbsoluteResource);
    }
    if (segments[0] !== 'session') {
      return refused(raw, ResourceRefusal.Invalid);
    }
    if (segments.length < 3) {
      return refused(raw, ResourceRefusal.Invalid);
    }
    const sessionId: string = segments[1];
    if (context.currentWorkerSessionId.length === 0) {
      return refused(raw, ResourceRefusal.NoCurrentSession);
    }
    if (sessionId !== context.currentWorkerSessionId ||
      context.knownWorkerSessionIds.indexOf(sessionId) < 0) {
      return refused(raw, ResourceRefusal.DifferentSession);
    }
    if (!context.hasVerifiedWorkspace) {
      return refused(raw, ResourceRefusal.NoVerifiedWorkspace);
    }
    let tail: string[] = [];
    for (let index: number = 2; index < segments.length; index++) {
      tail.push(segments[index]);
    }
    if (tail.length >= 2 && tail[0] === 'dsh' && tail[1] === 'workspace') {
      tail = tail.slice(2);
    }
    const relative: string = tail.join('/');
    if (!isSafeRelativePath(relative)) {
      return refused(raw, relative.length === 0 ? ResourceRefusal.MissingPath : ResourceRefusal.UnsafePath);
    }
    return sessionResource(raw, sessionId, relative);
  }
  return refused(raw, ResourceRefusal.UnsupportedScheme);
}

/** Mirrors Android `validateRemoteUri` after each redirect hop. */
export function isValidRemoteUri(text: string): boolean {
  const uri: UriParts | null = parseUri(text);
  if (uri === null || (uri.scheme !== 'http' && uri.scheme !== 'https')) {
    return false;
  }
  return uri.host.length > 0 && uri.userInfo.length === 0 && !hasControlCharacter(text);
}

/** Resolves one redirect Location and revalidates it; '' means refuse the hop. */
export function nextRemoteRedirect(current: string, location: string): string {
  const trimmed: string = location.trim();
  if (trimmed.length === 0 || hasControlCharacter(trimmed)) {
    return '';
  }
  const currentUri: UriParts | null = parseUri(current);
  if (currentUri === null) {
    return '';
  }
  let next: string = trimmed;
  const hasScheme: boolean = /^[a-z][a-z0-9+.-]*:/i.test(trimmed);
  if (hasScheme && !trimmed.startsWith('http://') && !trimmed.startsWith('https://')) {
    // A non-HTTP Location (file:, data:, javascript:, …) is never followed.
    return '';
  }
  if (!hasScheme && !trimmed.startsWith('http://') && !trimmed.startsWith('https://')) {
    if (trimmed.startsWith('//')) {
      next = currentUri.scheme + ':' + trimmed;
    } else if (trimmed.startsWith('/')) {
      next = currentUri.scheme + '://' + currentUri.host + trimmed;
    } else {
      const path: string = currentUri.path.split('#')[0].split('?')[0];
      const slash: number = path.lastIndexOf('/');
      next = currentUri.scheme + '://' + currentUri.host +
        (slash < 0 ? '/' : path.substring(0, slash + 1)) + trimmed;
    }
  }
  if (!isValidRemoteUri(next)) {
    return '';
  }
  return next;
}

/** Android `sampleMarkdownImageSize`: integer sample factor for a bounded decode. */
export function sampleMarkdownImageSize(width: number, height: number,
  maxDimension: number = MAX_DECODED_IMAGE_DIMENSION): number {
  if (width <= 0 || height <= 0 || maxDimension <= 0) {
    return 1;
  }
  let sample: number = 1;
  while (width / (sample * 2) >= maxDimension && height / (sample * 2) >= maxDimension) {
    sample *= 2;
  }
  while (width / sample > maxDimension || height / sample > maxDimension) {
    sample *= 2;
  }
  return sample > 0 ? sample : 1;
}

export function boundedDecodeSize(width: number, height: number): DecodeSize {
  const size = new DecodeSize();
  const sample: number = sampleMarkdownImageSize(width, height);
  size.width = Math.max(1, Math.floor(width / sample));
  size.height = Math.max(1, Math.floor(height / sample));
  return size;
}

export function isImagePath(path: string): boolean {
  const clean: string = path.split('#')[0].split('?')[0].toLowerCase();
  const dot: number = clean.lastIndexOf('.');
  if (dot < 0) {
    return false;
  }
  return IMAGE_EXTENSIONS.indexOf(clean.substring(dot + 1)) >= 0;
}

export function mimeForPath(path: string): string {
  const clean: string = path.split('#')[0].split('?')[0].toLowerCase();
  const dot: number = clean.lastIndexOf('.');
  const extension: string = dot < 0 ? '' : clean.substring(dot + 1);
  if (extension === 'png') return 'image/png';
  if (extension === 'jpg' || extension === 'jpeg') return 'image/jpeg';
  if (extension === 'webp') return 'image/webp';
  if (extension === 'gif') return 'image/gif';
  if (extension === 'bmp') return 'image/bmp';
  if (extension === 'heic' || extension === 'heif') return 'image/heic';
  if (extension === 'ico') return 'image/x-icon';
  if (extension === 'avif') return 'image/avif';
  return 'application/octet-stream';
}

/** True when PreviewView should render one source through the image surface. */
export function isImageSource(source: string): boolean {
  const trimmed: string = source.trim();
  const uri: UriParts | null = parseUri(trimmed);
  if (uri !== null && (uri.scheme === 'http' || uri.scheme === 'https')) {
    return true;
  }
  return isImagePath(trimmed);
}

export function refusalText(reason: ResourceRefusal): string {
  if (reason === ResourceRefusal.AbsoluteResource) return '绝对资源路径不在工作区内';
  if (reason === ResourceRefusal.UnsafePath) return '资源路径不安全';
  if (reason === ResourceRefusal.MissingPath) return '资源路径为空';
  if (reason === ResourceRefusal.NoCurrentSession) return '当前没有可用的会话';
  if (reason === ResourceRefusal.DifferentSession) return '资源属于其他会话';
  if (reason === ResourceRefusal.NoVerifiedWorkspace) return '当前会话还没有已验证的工作区';
  if (reason === ResourceRefusal.UnsupportedScheme) return '不支持该资源协议';
  return '资源地址无效';
}

// ------------------------------------------------------------ byte chunks

const BASE64_ALPHABET: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/**
 * Converts the Worker's latin1 byte string without UTF-8 re-encoding.
 * `charCodeAt(i) & 255` is the frozen contract for `$readBinaryChunk` base64
 * payloads already decoded by the caller or raw byte strings.
 */
export function latin1ByteStringToBytes(text: string): Uint8Array {
  const bytes: Uint8Array = new Uint8Array(text.length);
  for (let index: number = 0; index < text.length; index++) {
    bytes[index] = text.charCodeAt(index) & 0xff;
  }
  return bytes;
}

/** Pure base64 decode used for every 256 KiB Worker chunk. */
export function decodeBase64(text: string): Uint8Array {
  if (text.length === 0) {
    return new Uint8Array(0);
  }
  let padding: number = 0;
  if (text.endsWith('==')) {
    padding = 2;
  } else if (text.endsWith('=')) {
    padding = 1;
  }
  const output: Uint8Array = new Uint8Array(Math.floor(text.length * 3 / 4) - padding);
  let out: number = 0;
  let buffer: number = 0;
  let bits: number = 0;
  for (let index: number = 0; index < text.length; index++) {
    const code: number = text.charCodeAt(index);
    if (code === 0x3d) {
      break;
    }
    const value: number = BASE64_ALPHABET.indexOf(text.charAt(index));
    if (value < 0) {
      throw new Error('无效的 base64 数据');
    }
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      output[out] = (buffer >> bits) & 0xff;
      out += 1;
    }
  }
  return out === output.length ? output : output.slice(0, out);
}

export function concatBytes(chunks: Uint8Array[], total: number): Uint8Array {
  const output: Uint8Array = new Uint8Array(total);
  let offset: number = 0;
  for (let index: number = 0; index < chunks.length; index++) {
    const chunk: Uint8Array = chunks[index];
    output.set(chunk, offset);
    offset += chunk.length;
  }
  return output;
}

/** Component owns the ArrayBuffer; never stored in ShellSnapshot. */
export function exactArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}
