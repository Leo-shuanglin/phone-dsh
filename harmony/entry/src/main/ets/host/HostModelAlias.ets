// SPDX-License-Identifier: MIT
// Pure URI mapping for the Worker's model alias.
//
// The Worker is handed `DEEPSEEK_BASE_URL=http://127.0.0.1:<native port>` and
// llm-bridge wraps every model URL in `POST /v1/http`, keeping the same loopback
// URL in `x-pdsh-target-url`. Android mounts those model-protocol paths on its
// loopback origin and forwards them to the configured provider; the Worker never
// sees the real origin. This helper is the same mapping table, kept pure so the
// proxy can resolve it without NetworkKit and a URI case can exercise it.
//
// It maps only the exact loopback paths the model protocols use (plus the
// descendant forms Android mounts). Every other path is unmatched, so /api,
// /assets, another port or another host never get a credential-carrying target.
// The result is always the configured https provider origin with the provider's
// own path spelling, never an arbitrary http relay.

class ParsedModelUrl {
  scheme: string = '';
  host: string = '';
  port: number = 0;
  path: string = '/';
  query: string = '';
}

export class NativeModelOrigin {
  scheme: string = '';
  host: string = '';
  port: number = 0;
}

export class AliasResolution {
  /** True only when the target is on the exact bound native origin and a model path. */
  matched: boolean = false;
  /** The configured https target, or empty when the provider base is unusable. */
  target: string = '';
}

class AliasRoute {
  loopbackPath: string = '';
  suffix: string = '';
  scope: string = 'base';
  includesDescendants: boolean = false;

  constructor(loopbackPath: string, suffix: string, scope: string, includesDescendants: boolean) {
    this.loopbackPath = loopbackPath;
    this.suffix = suffix;
    this.scope = scope;
    this.includesDescendants = includesDescendants;
  }
}

const SCOPE_BASE: string = 'base';
const SCOPE_ROOT: string = 'root';
const SCOPE_ORIGIN: string = 'origin';

// The loopback side is the canonical set the Worker appends to its base URL,
// mirroring Android ModelProtocolPaths; `/chat/completions` and `/responses`
// are the spellings the native direct-route table already accepts, so the
// wrapped form of those exact requests maps to the same configured path.
const ALIAS_ROUTES: AliasRoute[] = [
  new AliasRoute('/v1/messages/count_tokens', '/messages/count_tokens', SCOPE_ROOT, false),
  new AliasRoute('/v1/chat/completions', '/chat/completions', SCOPE_BASE, false),
  new AliasRoute('/v1/completions', '/completions', SCOPE_BASE, false),
  new AliasRoute('/v1/responses', '/responses', SCOPE_BASE, true),
  new AliasRoute('/v1/messages', '/messages', SCOPE_ROOT, false),
  new AliasRoute('/v1/models', '/models', SCOPE_ROOT, true),
  new AliasRoute('/v1/files', '/files', SCOPE_ROOT, true),
  new AliasRoute('/chat/completions', '/chat/completions', SCOPE_BASE, false),
  new AliasRoute('/responses', '/responses', SCOPE_BASE, false),
  new AliasRoute('/models', '/models', SCOPE_ORIGIN, true)
];

function urlPathForbidden(path: string): boolean {
  const lowered = path.toLowerCase();
  if (lowered.includes('%2e') || lowered.includes('%2f') || lowered.includes('%5c')) {
    return true;
  }
  if (path.includes('\\') || path.includes('\0')) {
    return true;
  }
  const segments = path.split('/');
  for (let index = 0; index < segments.length; index++) {
    if (segments[index] === '..') {
      return true;
    }
  }
  return false;
}

function parseAbsolute(raw: string, allowHttp: boolean): ParsedModelUrl | null {
  const text = raw.trim();
  let scheme = '';
  let offset = 0;
  if (text.startsWith('https://') || text.startsWith('HTTPS://')) {
    scheme = 'https';
    offset = 8;
  } else if (allowHttp && (text.startsWith('http://') || text.startsWith('HTTP://'))) {
    scheme = 'http';
    offset = 7;
  } else {
    return null;
  }
  let rest = text.substring(offset);
  const hash = rest.indexOf('#');
  if (hash >= 0) {
    rest = rest.substring(0, hash);
  }
  let query = '';
  const queryAt = rest.indexOf('?');
  if (queryAt >= 0) {
    query = rest.substring(queryAt + 1);
    rest = rest.substring(0, queryAt);
  }
  let authority = rest;
  const slash = rest.indexOf('/');
  if (slash >= 0) {
    authority = rest.substring(0, slash);
  }
  if (authority.length === 0 || authority.includes('@') || authority.startsWith('[')) {
    return null;
  }
  let host = authority;
  let port = scheme === 'https' ? 443 : 80;
  const colon = authority.lastIndexOf(':');
  if (colon >= 0) {
    host = authority.substring(0, colon);
    const portText = authority.substring(colon + 1);
    if (portText.length === 0 || !/^[0-9]+$/.test(portText)) {
      return null;
    }
    port = Number(portText);
    if (port <= 0 || port > 65535) {
      return null;
    }
  }
  if (host.length === 0 || host.includes(' ') || host.includes('\t') || host.includes('/')) {
    return null;
  }
  let path = slash >= 0 ? rest.substring(slash) : '/';
  if (path.length === 0) {
    path = '/';
  }
  if (urlPathForbidden(path)) {
    return null;
  }
  const parsed = new ParsedModelUrl();
  parsed.scheme = scheme;
  parsed.host = host.toLowerCase();
  parsed.port = port;
  parsed.path = path;
  parsed.query = query;
  return parsed;
}

/** Parses the one trusted native start origin; only the loopback host is allowed. */
export function parseNativeModelOrigin(raw: string): NativeModelOrigin | null {
  const text = raw.trim();
  if (text.length === 0 || text.includes('?') || text.includes('#')) {
    return null;
  }
  const parsed = parseAbsolute(text, true);
  if (parsed === null) {
    return null;
  }
  if (parsed.scheme !== 'http' || parsed.host !== '127.0.0.1' || parsed.path !== '/') {
    return null;
  }
  const origin = new NativeModelOrigin();
  origin.scheme = parsed.scheme;
  origin.host = parsed.host;
  origin.port = parsed.port;
  return origin;
}

function normalizePath(raw: string): string {
  let path = raw.length === 0 ? '/' : raw;
  if (!path.startsWith('/')) {
    path = '/' + path;
  }
  while (path.indexOf('//') >= 0) {
    path = path.split('//').join('/');
  }
  const segments = path.split('/');
  const kept: string[] = [];
  for (let index = 0; index < segments.length; index++) {
    const segment = segments[index];
    if (segment.length === 0 || segment === '.') {
      continue;
    }
    if (segment === '..') {
      if (kept.length > 0) {
        kept.length = kept.length - 1;
      }
      continue;
    }
    kept.push(segment);
  }
  if (kept.length === 0) {
    return '/';
  }
  return '/' + kept.join('/');
}

function providerRoot(basePath: string): string {
  if (basePath === '/') {
    return '/v1';
  }
  if (basePath.endsWith('/v1')) {
    return basePath;
  }
  return basePath + '/v1';
}

function upstreamBase(route: AliasRoute, basePath: string, rootPath: string): string {
  if (route.scope === SCOPE_ROOT) {
    return normalizePath(rootPath + route.suffix);
  }
  if (route.scope === SCOPE_ORIGIN) {
    return normalizePath(route.suffix);
  }
  return normalizePath(basePath + route.suffix);
}

function matchRoute(path: string): AliasRoute | null {
  for (let index = 0; index < ALIAS_ROUTES.length; index++) {
    const route = ALIAS_ROUTES[index];
    if (path === route.loopbackPath) {
      return route;
    }
    if (route.includesDescendants && path.startsWith(route.loopbackPath + '/')) {
      return route;
    }
  }
  return null;
}

function hostPort(parsed: ParsedModelUrl): string {
  if (parsed.port === 443) {
    return parsed.host;
  }
  return `${parsed.host}:${parsed.port}`;
}

/**
 * Resolves one `/v1/http` target against the exact bound native origin.
 *
 * `matched` is false for an https target (the normal provider path) or any URL
 * that is not this origin plus a model-protocol path. When `matched` is true but
 * `target` is empty, the configured provider base is unusable; the caller must
 * report a credential/configuration failure without opening a socket.
 */
export function resolveNativeModelAlias(origin: NativeModelOrigin, rawTarget: string,
  providerBaseUrl: string): AliasResolution {
  const result = new AliasResolution();
  const target = parseAbsolute(rawTarget, true);
  if (target === null) {
    return result;
  }
  if (target.scheme !== origin.scheme || target.host !== origin.host || target.port !== origin.port) {
    return result;
  }
  const route = matchRoute(target.path);
  if (route === null) {
    return result;
  }
  result.matched = true;
  const provider = parseAbsolute(providerBaseUrl, false);
  if (provider === null || provider.scheme !== 'https' || provider.query.length > 0) {
    return result;
  }
  const basePath = normalizePath(provider.path);
  const rootPath = providerRoot(basePath);
  const routePath = upstreamBase(route, basePath, rootPath);
  let descendant = '';
  if (target.path !== route.loopbackPath) {
    descendant = target.path.substring(route.loopbackPath.length);
  }
  const mappedPath = normalizePath(routePath + descendant);
  if (mappedPath !== routePath && !mappedPath.startsWith(routePath + '/')) {
    return result;
  }
  if (!providerModelPathAdmitted(providerBaseUrl, mappedPath)) {
    return result;
  }
  const suffix = target.query.length > 0 ? '?' + target.query : '';
  result.target = `https://${hostPort(provider)}${mappedPath}${suffix}`;
  return result;
}

const OFFICIAL_WEB_HOST: string = 'api.deepseek.com';
const OFFICIAL_WEB_PORT: number = 443;
const OFFICIAL_WEB_BASE_PATHS: string[] = ['/', '/v1', '/anthropic', '/anthropic/v1'];
const OFFICIAL_WEB_SEARCH_URL: string = 'https://api.deepseek.com/v1/tools/search';
const OFFICIAL_WEB_FETCH_URL: string = 'https://api.deepseek.com/v1/tools/fetch';

export function officialWebModelBaseEligible(baseUrl: string): boolean {
  const text = baseUrl.trim();
  if (text.length === 0 || text.includes('?') || text.includes('#')) {
    return false;
  }
  const parsed = parseAbsolute(text, false);
  if (parsed === null) {
    return false;
  }
  if (parsed.scheme !== 'https' || parsed.host !== OFFICIAL_WEB_HOST || parsed.port !== OFFICIAL_WEB_PORT) {
    return false;
  }
  const path = parsed.path;
  for (let index = 0; index < OFFICIAL_WEB_BASE_PATHS.length; index++) {
    const base = OFFICIAL_WEB_BASE_PATHS[index];
    if (path === base) {
      return true;
    }
    if (base !== '/' && path === base + '/') {
      return true;
    }
  }
  return false;
}

export function resolveOfficialWebToolTarget(providerBaseUrl: string, method: string,
  rawTarget: string): string {
  if (!officialWebModelBaseEligible(providerBaseUrl)) {
    return '';
  }
  if (method.trim().toUpperCase() !== 'POST') {
    return '';
  }
  const target = rawTarget.trim();
  if (target === OFFICIAL_WEB_SEARCH_URL) {
    return OFFICIAL_WEB_SEARCH_URL;
  }
  if (target === OFFICIAL_WEB_FETCH_URL) {
    return OFFICIAL_WEB_FETCH_URL;
  }
  return '';
}

/**
 * True when `path` is one of the configured provider's model-protocol paths.
 *
 * This is the Android `ModelProtocolPaths.admits` rule: exact routes plus the
 * descendant forms only for the routes that mount a tail. The current origin and
 * scheme are not re-checked here; the proxy compares them against the base URL.
 */
export function providerModelPathAdmitted(providerBaseUrl: string, path: string): boolean {
  const provider = parseAbsolute(providerBaseUrl, false);
  if (provider === null || provider.scheme !== 'https' || provider.query.length > 0) {
    return false;
  }
  const basePath = normalizePath(provider.path);
  const rootPath = providerRoot(basePath);
  const requested = normalizePath(path);
  for (let index = 0; index < ALIAS_ROUTES.length; index++) {
    const route = ALIAS_ROUTES[index];
    const routePath = upstreamBase(route, basePath, rootPath);
    if (requested === routePath) {
      return true;
    }
    if (route.includesDescendants && requested.startsWith(routePath + '/')) {
      return true;
    }
  }
  return false;
}
