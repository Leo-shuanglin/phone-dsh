// SPDX-License-Identifier: MIT
// The host-edge model proxy. Native delegates only the five model HTTP routes to
// ArkTS. The Worker never holds the real key: this class strips every Worker
// credential, validates the configured https provider scope, injects the
// AssetStore key on the NetworkKit request, refuses redirects, and streams the
// upstream bytes back through native.respond as head -> chunk* -> end.
import { http } from '@kit.NetworkKit';
import { rcp } from '@kit.RemoteCommunicationKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  AliasResolution,
  NativeModelOrigin,
  parseNativeModelOrigin,
  providerModelPathAdmitted,
  resolveNativeModelAlias,
  resolveOfficialWebToolTarget
} from './HostModelAlias';
import {
  asRecord,
  bounded,
  decodeBase64,
  decodeUtf8,
  encodeUtf8,
  jsonNumber,
  jsonString,
  toArrayBuffer
} from './HostUtil';
import { ProxyStreamState, proxyRejectMessage } from './HostProxyStreamState';

export class ModelRouteConfig {
  baseUrl: string = '';
  model: string = '';
  encoder: string = 'deepseek';
  /** Frozen at boot: the official Search/Fetch grant is on for this run only. */
  officialWebTools: boolean = false;
}

export class ProviderProbeResult {
  ok: boolean = false;
  status: number = 0;
  message: string = '';
}

export interface ProxyResponder {
  respond(id: number, kind: number, status: number, headersJson: string, body: ArrayBuffer): void;
}

class ParsedUrl {
  scheme: string = '';
  host: string = '';
  port: number = 443;
  path: string = '/';
  query: string = '';
}

class HttpExchange {
  id: number = 0;
  session: rcp.Session | null = null;
  request: rcp.Request | null = null;
  timer: number = -1;
  state: ProxyStreamState = new ProxyStreamState();
  headers: Record<string, string> = {};
  pending: ArrayBuffer[] = [];
  chunkCount: number = 0;
  keyBytes: Uint8Array = new Uint8Array(0);
  method: string = 'POST';
}

const MAX_BODY_BYTES: number = 4 * 1024 * 1024;
const MAX_PENDING_BYTES: number = 1024 * 1024;
const EXCHANGE_TIMEOUT_MS: number = 10 * 60 * 1000;
const STREAM_CHUNK_BYTES: number = 16 * 1024;

const DIRECT_ROUTES: string[] = [
  '/chat/completions',
  '/v1/chat/completions',
  '/responses',
  '/v1/messages'
];

const REQUEST_DENY: string[] = [
  'authorization', 'proxy-authorization', 'host', 'connection', 'content-length',
  'transfer-encoding', 'accept-encoding', 'expect', 'upgrade', 'te', 'trailer',
  'proxy-connection', 'cookie', 'cookie2', 'referer', 'referrer', 'x-api-key',
  'api-key', 'x-dsh-auth-token', 'x-pdsh-upstream-authorization'
];

const RESPONSE_DENY: string[] = [
  'connection', 'transfer-encoding', 'content-length', 'keep-alive', 'upgrade',
  'trailer', 'set-cookie', 'set-cookie2', 'location', 'content-encoding',
  'proxy-authenticate'
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

function parseUrl(raw: string): ParsedUrl | null {
  const text = raw.trim();
  if (!text.startsWith('https://') && !text.startsWith('HTTPS://')) {
    return null;
  }
  let rest = text.substring(8);
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
  let port = 443;
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
  const parsed = new ParsedUrl();
  parsed.scheme = 'https';
  parsed.host = host.toLowerCase();
  parsed.port = port;
  parsed.path = path;
  parsed.query = query;
  return parsed;
}

export function providerBaseError(raw: string): string {
  const text = raw.trim();
  if (text.length === 0) {
    return '模型服务地址不能为空';
  }
  if (text.startsWith('http://') || text.startsWith('HTTP://')) {
    return '密钥只会发往 https 地址；不支持明文 http';
  }
  if (!text.startsWith('https://') && !text.startsWith('HTTPS://')) {
    return '模型服务地址必须是绝对 https URL';
  }
  if (text.includes('?') || text.includes('#')) {
    return '模型服务地址不能带查询参数或片段';
  }
  const parsed = parseUrl(text);
  if (parsed === null) {
    return '模型服务地址格式不正确';
  }
  const authority = text.substring(8).split('/')[0];
  if (authority.includes('@')) {
    return '模型服务地址不能包含用户名或密码';
  }
  return '';
}

function hostPort(parsed: ParsedUrl): string {
  if (parsed.port === 443) {
    return parsed.host;
  }
  return `${parsed.host}:${parsed.port}`;
}

function remoteMethod(value: string): rcp.HttpMethod {
  if (value === 'GET') {
    return 'GET';
  }
  if (value === 'HEAD') {
    return 'HEAD';
  }
  return 'POST';
}

function remoteHeaders(value: Record<string, string>): rcp.RequestHeaders {
  const result: rcp.RequestHeaders = {};
  const keys = Object.keys(value);
  for (let index = 0; index < keys.length; index++) {
    result[keys[index]] = value[keys[index]];
  }
  return result;
}

function headerText(value: Object | null | undefined): string {
  if (typeof value === 'string') {
    return value;
  }
  if (typeof value === 'number') {
    return String(value);
  }
  if (Array.isArray(value)) {
    const parts: string[] = [];
    const rows = value as Object[];
    for (let index = 0; index < rows.length; index++) {
      if (typeof rows[index] === 'string') {
        parts.push(rows[index] as string);
      }
    }
    return parts.join(', ');
  }
  return '';
}

function lowerHeaders(value: Object | null): Record<string, string> {
  const result: Record<string, string> = {};
  const row = asRecord(value);
  if (row === null) {
    return result;
  }
  const keys = Object.keys(row);
  for (let index = 0; index < keys.length; index++) {
    const key = keys[index].toLowerCase();
    const text = headerText(row[keys[index]]);
    if (key.length > 0 && text.length > 0) {
      result[key] = text;
    }
  }
  return result;
}

function denied(name: string, denylist: string[]): boolean {
  for (let index = 0; index < denylist.length; index++) {
    if (denylist[index] === name) {
      return true;
    }
  }
  return name.startsWith('x-pdsh-') || name.startsWith('x-xl-');
}

function sanitizedRequest(headers: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {};
  const keys = Object.keys(headers);
  for (let index = 0; index < keys.length; index++) {
    const name = keys[index].toLowerCase();
    if (denied(name, REQUEST_DENY)) {
      continue;
    }
    if (headers[keys[index]].includes('phone-host-injects-mcp-key')) {
      continue;
    }
    result[name] = headers[keys[index]];
  }
  return result;
}

function injectCredential(headers: Record<string, string>, key: string, encoder: string): void {
  const lower = encoder.toLowerCase();
  const anthropic = lower.includes('anthropic');
  if ((anthropic || lower.includes('deepseek')) && headers['anthropic-version'] === undefined) {
    headers['anthropic-version'] = '2023-06-01';
  }
  if (anthropic) {
    headers['x-api-key'] = key;
    if (headers['anthropic-version'] === undefined) {
      headers['anthropic-version'] = '2023-06-01';
    }
  } else {
    headers['authorization'] = `Bearer ${key}`;
  }
}

function responseHeaders(value: Record<string, string>, key: string): string {
  const result: Record<string, string> = {};
  const keys = Object.keys(value);
  for (let index = 0; index < keys.length; index++) {
    const name = keys[index].toLowerCase();
    const headerValue = value[keys[index]];
    if (denied(name, RESPONSE_DENY)) {
      continue;
    }
    if (headerValue.includes(key) || (key.length > 0 && name.includes(key))) {
      continue;
    }
    result[name] = headerValue;
  }
  result['cache-control'] = 'no-store';
  return JSON.stringify(result);
}

function redirected(error: Error): boolean {
  const business = error as BusinessError;
  return business !== null && business !== undefined &&
    (business.code === 2300047 || business.code === 1007900047);
}

function transportCode(error: Error): number {
  const business = error as BusinessError;
  if (business !== null && business !== undefined && typeof business.code === 'number') {
    return business.code;
  }
  return 0;
}

function containsSecret(value: ArrayBuffer, needle: Uint8Array): boolean {
  if (needle.byteLength === 0 || value.byteLength < needle.byteLength) {
    return false;
  }
  const haystack = new Uint8Array(value);
  const limit = haystack.byteLength - needle.byteLength;
  for (let start = 0; start <= limit; start++) {
    let matched = true;
    for (let offset = 0; offset < needle.byteLength; offset++) {
      if (haystack[start + offset] !== needle[offset]) {
        matched = false;
        break;
      }
    }
    if (matched) {
      return true;
    }
  }
  return false;
}

export class HostHttpProxy {
  private route: ModelRouteConfig = new ModelRouteConfig();
  private responder: ProxyResponder | null = null;
  private keyProvider: () => Promise<string> = async (): Promise<string> => '';
  private exchanges: Map<number, HttpExchange> = new Map();
  private nativeOrigin: NativeModelOrigin | null = null;
  private disposed: boolean = false;

  setResponder(responder: ProxyResponder): void {
    this.responder = responder;
  }

  setKeyProvider(provider: () => Promise<string>): void {
    this.keyProvider = provider;
  }

  setRoute(route: ModelRouteConfig): void {
    this.route = route;
  }

  /**
   * Binds the trusted native start origin. HostService calls this from the
   * native start result before the Worker loads; it is never derived from a
   * request header, so a Worker cannot widen its own alias scope.
   */
  setNativeOrigin(origin: string): void {
    this.nativeOrigin = parseNativeModelOrigin(origin);
  }

  /**
   * Cancels one native-declared request after the host already made it
   * terminal (host stop, timeout or a confirmed peer abort). Idempotent and
   * local: unknown/finished ids are ignored and nothing is sent back through
   * native.respond. The proxy stays reusable for later requests.
   */
  cancel(id: number): void {
    if (id < 0) {
      return;
    }
    const exchange = this.exchanges.get(id);
    if (exchange === undefined) {
      return;
    }
    this.cancelExchange(exchange);
  }

  /** Cancels every in-flight exchange without disposing this proxy instance. */
  cancelPending(): void {
    const exchanges: HttpExchange[] = [];
    for (const exchange of this.exchanges.values()) {
      exchanges.push(exchange);
    }
    for (let index = 0; index < exchanges.length; index++) {
      this.cancelExchange(exchanges[index]);
    }
  }

  dispose(): void {
    this.disposed = true;
    this.cancelPending();
  }

  async handle(event: Record<string, Object>): Promise<void> {
    const id = jsonNumber(event, 'id', -1);
    if (id < 0) {
      return;
    }
    if (this.disposed) {
      this.fail(id, 503, 'host_stopping');
      return;
    }
    const incoming = lowerHeaders(event['headers']);
    let method = jsonString(event, 'method', 'POST').toUpperCase();
    const path = jsonString(event, 'path', '/');
    let target = '';
    if (path === '/v1/http' || path.startsWith('/v1/http?')) {
      target = incoming['x-pdsh-target-url'] !== undefined ? incoming['x-pdsh-target-url'] : '';
      if (target.length === 0) {
        target = incoming['x-xl-url'] !== undefined ? incoming['x-xl-url'] : '';
      }
      const targetMethod = incoming['x-pdsh-target-method'] !== undefined
        ? incoming['x-pdsh-target-method']
        : (incoming['x-xl-method'] !== undefined ? incoming['x-xl-method'] : '');
      if (targetMethod.length > 0) {
        method = targetMethod.toUpperCase();
      }
    } else {
      const routePath = path.split('?')[0];
      if (DIRECT_ROUTES.indexOf(routePath) < 0) {
        this.fail(id, 404, 'model_route_unknown');
        return;
      }
      target = this.mapProviderPath(path);
    }
    let parsed = parseUrl(target);
    if (parsed === null && this.nativeOrigin !== null) {
      const alias: AliasResolution = resolveNativeModelAlias(this.nativeOrigin, target, this.route.baseUrl);
      if (alias.matched) {
        if (alias.target.length === 0) {
          this.fail(id, 401, 'credential_unavailable: no model provider is configured');
          return;
        }
        target = alias.target;
        parsed = parseUrl(target);
      }
    }
    if (parsed === null) {
      this.fail(id, 400, 'invalid_request');
      return;
    }
    const origin = parseUrl(this.route.baseUrl);
    if (origin === null) {
      this.fail(id, 401, 'credential_unavailable: no model provider is configured');
      return;
    }
    if (parsed.host !== origin.host || parsed.port !== origin.port || parsed.scheme !== origin.scheme) {
      this.fail(id, 403, 'host_not_allowed');
      return;
    }
    const officialTarget: string = this.route.officialWebTools
      ? resolveOfficialWebToolTarget(this.route.baseUrl, method, target)
      : '';
    if (officialTarget.length === 0 && !providerModelPathAdmitted(this.route.baseUrl, parsed.path)) {
      this.fail(id, 403, 'redirect_scope_mismatch');
      return;
    }
    const wireBase64 = jsonString(event, 'bodyBase64', '');
    const bodyBytes = decodeBase64(wireBase64);
    if (bodyBytes.byteLength > MAX_BODY_BYTES) {
      this.fail(id, 413, 'payload_too_large');
      return;
    }
    let key = '';
    try {
      key = (await this.keyProvider()).trim();
    } catch (error) {
      this.fail(id, 502, 'credential_unavailable');
      return;
    }
    if (key.length === 0) {
      this.fail(id, 401, 'credential_unavailable: no model key is configured');
      return;
    }
    if (method !== 'GET' && method !== 'POST' && method !== 'HEAD') {
      this.fail(id, 405, 'method_not_allowed');
      return;
    }
    const outbound = sanitizedRequest(incoming);
    if (officialTarget.length > 0) {
      outbound['authorization'] = `Bearer ${key}`;
    } else {
      injectCredential(outbound, key, this.route.encoder);
    }
    outbound['accept-encoding'] = 'identity';
    const finalTarget = officialTarget.length > 0
      ? officialTarget
      : `https://${hostPort(parsed)}${parsed.path}${parsed.query.length > 0 ? '?' + parsed.query : ''}`;
    this.openExchange(id, method, finalTarget, outbound, toArrayBuffer(bodyBytes), encodeUtf8(key));
  }

  async testProvider(): Promise<ProviderProbeResult> {
    const result = new ProviderProbeResult();
    if (providerBaseError(this.route.baseUrl).length > 0) {
      result.message = providerBaseError(this.route.baseUrl);
      return result;
    }
    if (this.route.model.trim().length === 0) {
      result.message = '模型名是空的';
      return result;
    }
    let key = '';
    try {
      key = (await this.keyProvider()).trim();
    } catch (error) {
      result.message = '读不到已保存的密钥';
      return result;
    }
    if (key.length === 0) {
      result.message = '还没有配置模型密钥；本次没有发送网络请求';
      return result;
    }
    const origin = parseUrl(this.route.baseUrl);
    if (origin === null) {
      result.message = '模型服务地址格式不正确';
      return result;
    }
    const encoder = this.route.encoder.toLowerCase();
    const anthropic = encoder.includes('anthropic') || encoder.includes('deepseek');
    const routePath = anthropic ? '/v1/messages' : '/chat/completions';
    const joined = this.joinBasePath(this.route.baseUrl, routePath);
    const body = anthropic
      ? `{"model":"${this.escapeJson(this.route.model)}","max_tokens":1,"messages":[{"role":"user","content":"ping"}]}`
      : `{"model":"${this.escapeJson(this.route.model)}","max_tokens":1,"stream":false,"messages":[{"role":"user","content":"ping"}]}`;
    const headers: Record<string, string> = {};
    headers['content-type'] = 'application/json';
    injectCredential(headers, key, this.route.encoder);
    const options: http.HttpRequestOptions = {
      method: http.RequestMethod.POST,
      header: headers,
      extraData: body,
      usingCache: false,
      maxRedirects: 0,
      connectTimeout: 15000,
      readTimeout: 120000,
      usingProtocol: http.HttpProtocol.HTTP1_1
    };
    const request = http.createHttp();
    try {
      const response = await request.request(joined, options);
      result.status = response.responseCode;
      if (response.responseCode >= 200 && response.responseCode <= 299) {
        result.ok = true;
        result.message = `连接正常（HTTP ${response.responseCode}）`;
      } else if (response.responseCode === 401 || response.responseCode === 403) {
        result.message = `密钥被服务拒绝（HTTP ${response.responseCode}）`;
      } else if (response.responseCode === 429) {
        result.message = '请求太频繁（HTTP 429）；服务已收到测试请求';
      } else if (response.responseCode >= 300 && response.responseCode <= 399) {
        result.message = `服务要求重定向（HTTP ${response.responseCode}）；为保护密钥未跟随`;
      } else {
        result.message = `测试失败（HTTP ${response.responseCode}）`;
      }
      return result;
    } catch (error) {
      const failure = error as Error;
      if (redirected(failure)) {
        result.message = '服务要求重定向；为保护密钥未跟随';
      } else if (transportCode(failure) === 2300997) {
        result.message = '系统禁止明文流量；请使用 https 地址';
      } else {
        result.message = '连不上模型服务；请检查网络和接口地址';
      }
      return result;
    } finally {
      request.destroy();
    }
  }

  private mapProviderPath(path: string): string {
    return this.joinBasePath(this.route.baseUrl, path);
  }

  private joinBasePath(baseUrl: string, routePath: string): string {
    const origin = parseUrl(baseUrl);
    if (origin === null) {
      return '';
    }
    let suffix = routePath;
    const queryAt = suffix.indexOf('?');
    let query = '';
    if (queryAt >= 0) {
      query = suffix.substring(queryAt);
      suffix = suffix.substring(0, queryAt);
    }
    if (origin.path.endsWith('/v1') && suffix.startsWith('/v1/')) {
      suffix = suffix.substring(3);
    }
    let basePath = origin.path;
    if (basePath === '/') {
      basePath = '';
    }
    const fullPath = `${basePath}${suffix}`;
    return `https://${hostPort(origin)}${fullPath}${query}`;
  }

  private escapeJson(text: string): string {
    return text.split('\\').join('\\\\').split('"').join('\\"');
  }

  private openExchange(id: number, method: string, target: string, headers: Record<string, string>,
    body: ArrayBuffer, keyBytes: Uint8Array): void {
    hilog.info(0x1A21, 'PDSHGateway', 'upstream start id=%{public}d', id);
    const exchange = new HttpExchange();
    exchange.id = id;
    exchange.method = method;
    exchange.keyBytes = keyBytes;
    this.exchanges.set(id, exchange);
    const events: rcp.HttpEventsHandler = {
      onStatusCodeReceive: (code: number): void => {
        this.onStatus(exchange, code);
      },
      onHeaderReceive: (responseHeaders: rcp.ResponseHeaders): void => {
        this.onHeaders(exchange, responseHeaders);
      },
      onDataReceive: (chunk: ArrayBuffer): number => {
        this.onChunk(exchange, chunk);
        return chunk.byteLength;
      },
      onDataEnd: (): void => {
        this.onDataEnd(exchange);
      }
    };
    const configuration: rcp.Configuration = {
      transfer: {
        autoRedirect: false,
        timeout: { connectMs: 15000, transferMs: 120000 }
      },
      security: {
        remoteValidation: 'system'
      },
      tracing: {
        httpEventsHandler: events
      }
    };
    try {
      const session = rcp.createSession({ requestConfiguration: configuration });
      exchange.session = session;
      const request = new rcp.Request(target, remoteMethod(method), remoteHeaders(headers),
        (method === 'POST' || method === 'PUT' || method === 'PATCH') ? body : undefined);
      exchange.request = request;
      exchange.timer = setTimeout(() => {
        this.failExchange(exchange, 504, 'proxy_timeout');
      }, EXCHANGE_TIMEOUT_MS);
      session.fetch(request).then((response: rcp.Response) => {
        this.onResponse(exchange, response);
      }).catch((error: Error) => {
        if (exchange.state.finished) {
          return;
        }
        hilog.warn(0x1A21, 'PDSHGateway', 'upstream transport failure id=%{public}d code=%{public}d',
          id, transportCode(error));
        if (redirected(error)) {
          this.failExchange(exchange, 502, 'upstream_redirect_refused');
        } else {
          this.failExchange(exchange, 502, 'transport_failure');
        }
      });
    } catch (error) {
      this.failExchange(exchange, 502, 'transport_failure');
    }
  }

  private onStatus(exchange: HttpExchange, code: number): void {
    if (!exchange.state.receiveStatus(code)) {
      return;
    }
    hilog.info(0x1A21, 'PDSHGateway', 'upstream status id=%{public}d status=%{public}d head=%{public}d chunks=%{public}d',
      exchange.id, exchange.state.status, exchange.state.headSent ? 1 : 0, exchange.chunkCount);
    this.maybeHead(exchange);
  }

  private onHeaders(exchange: HttpExchange, value: rcp.ResponseHeaders): void {
    if (!exchange.state.receiveHeaders()) {
      return;
    }
    exchange.headers = lowerHeaders(value as Object);
    this.maybeHead(exchange);
  }

  private onChunk(exchange: HttpExchange, chunk: ArrayBuffer): void {
    if (exchange.state.finished) {
      return;
    }
    if (containsSecret(chunk, exchange.keyBytes)) {
      this.failExchange(exchange, 502, 'upstream_echoed_credential');
      return;
    }
    exchange.state.noteData();
    if (exchange.state.rejected) {
      return;
    }
    exchange.chunkCount++;
    const copy = chunk.slice(0) as ArrayBuffer;
    if (!exchange.state.headSent) {
      if (exchange.state.queuePending(copy.byteLength, MAX_PENDING_BYTES)) {
        this.failExchange(exchange, 502, 'upstream_headers_missing');
        return;
      }
      exchange.pending.push(copy);
      this.maybeHead(exchange);
      return;
    }
    this.sendChunk(exchange, copy);
  }

  private onDataEnd(exchange: HttpExchange): void {
    exchange.state.noteDataEnd();
    if (exchange.state.readyToFinish(false)) {
      this.completeExchange(exchange);
    }
  }

  private onResponse(exchange: HttpExchange, response: rcp.Response): void {
    if (exchange.state.finished) {
      return;
    }
    if (!exchange.state.statusKnown) {
      exchange.state.receiveStatus(response.statusCode);
    }
    if (!exchange.state.headersSeen) {
      exchange.state.receiveHeaders();
      exchange.headers = lowerHeaders(response.headers as Object);
    }
    if (!exchange.state.dataSeen && response.body !== undefined && response.body.byteLength > 0) {
      this.onChunk(exchange, response.body);
    }
    if (exchange.state.finished) {
      return;
    }
    this.maybeHead(exchange);
    this.completeExchange(exchange);
  }

  private maybeHead(exchange: HttpExchange): void {
    if (!exchange.state.headReady()) {
      return;
    }
    this.sendHead(exchange);
    this.flushPending(exchange);
  }

  private sendHead(exchange: HttpExchange): void {
    if (this.responder === null || exchange.state.headSent || !exchange.state.success()) {
      return;
    }
    exchange.state.markHeadSent();
    this.responder.respond(exchange.id, 0, exchange.state.status,
      responseHeaders(exchange.headers, decodeUtf8(exchange.keyBytes)), new ArrayBuffer(0));
  }

  private flushPending(exchange: HttpExchange): void {
    if (!exchange.state.headSent) {
      return;
    }
    const pending = exchange.pending;
    exchange.pending = [];
    for (let index = 0; index < pending.length; index++) {
      this.sendChunk(exchange, pending[index]);
    }
  }

  private sendChunk(exchange: HttpExchange, chunk: ArrayBuffer): void {
    if (this.responder === null || !exchange.state.headSent) {
      return;
    }
    if (exchange.method === 'HEAD' || exchange.state.status === 204 ||
      exchange.state.status === 205 || exchange.state.status === 304) {
      return;
    }
    const bytes = new Uint8Array(chunk);
    let offset = 0;
    while (offset < bytes.byteLength) {
      const end = Math.min(offset + STREAM_CHUNK_BYTES, bytes.byteLength);
      const part = bytes.slice(offset, end);
      this.responder.respond(exchange.id, 1, 0, '', toArrayBuffer(part));
      offset = end;
    }
  }

  private completeExchange(exchange: HttpExchange): void {
    if (!exchange.state.readyToFinish(true)) {
      return;
    }
    exchange.state.finish();
    hilog.info(0x1A21, 'PDSHGateway', 'upstream complete id=%{public}d status=%{public}d chunks=%{public}d',
      exchange.id, exchange.state.status, exchange.chunkCount);
    this.releaseExchange(exchange);
    if (this.responder === null) {
      return;
    }
    if (exchange.state.rejected) {
      this.responder.respond(exchange.id, 3, exchange.state.status, '',
        toArrayBuffer(encodeUtf8(proxyRejectMessage(exchange.state.status))));
      return;
    }
    if (!exchange.state.success()) {
      this.responder.respond(exchange.id, 3, 502, '',
        toArrayBuffer(encodeUtf8('upstream_status_missing')));
      return;
    }
    if (!exchange.state.headSent) {
      this.sendHead(exchange);
      this.flushPending(exchange);
    }
    this.responder.respond(exchange.id, 2, 0, '', new ArrayBuffer(0));
  }

  private cancelExchange(exchange: HttpExchange): void {
    if (exchange.state.finished) {
      return;
    }
    // Terminal before the session teardown so late RCP callbacks cannot emit
    // a head/chunk/end after native already cancelled the request.
    exchange.state.finish();
    exchange.pending = [];
    this.releaseExchange(exchange);
  }

  private failExchange(exchange: HttpExchange, status: number, message: string): void {
    if (exchange.state.finished) {
      return;
    }
    exchange.state.finish();
    this.releaseExchange(exchange);
    if (this.responder !== null) {
      this.responder.respond(exchange.id, 3, status, '', toArrayBuffer(encodeUtf8(bounded(message, 400))));
    }
  }

  private releaseExchange(exchange: HttpExchange): void {
    if (exchange.timer >= 0) {
      clearTimeout(exchange.timer);
      exchange.timer = -1;
    }
    this.exchanges.delete(exchange.id);
    const session = exchange.session;
    exchange.session = null;
    if (session !== null) {
      try {
        if (exchange.request !== null) {
          session.cancel(exchange.request);
        } else {
          session.cancel();
        }
      } catch (error) {
      }
      try {
        session.close();
      } catch (error) {
      }
    }
    exchange.request = null;
  }

  private fail(id: number, status: number, message: string): void {
    if (this.responder !== null) {
      this.responder.respond(id, 3, status, '', toArrayBuffer(encodeUtf8(bounded(message, 400))));
    }
  }
}
