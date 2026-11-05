// SPDX-License-Identifier: MIT
// Private host persistence. Settings live in this app's preferences store; the
// native UI's session projection and drafts live in one JSON file written with
// fsync + rename. The Worker's WAL and /dsh tree are owned by the native host
// and are never written here.
import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { fileIo } from '@kit.CoreFileKit';
import {
  ChatRow,
  LoadPhase,
  ProviderConfig,
  ProviderModelRecord,
  ProviderProfileRecord,
  RowKind,
  SessionArtifact,
  SessionRecord,
  Settings,
  FontRole,
  FontSizes,
  Workspace
} from '../model/Contracts';
import {
  PROVIDER_DEFAULT_ENCODER,
  PROVIDER_DEFAULT_EFFORT,
  PROVIDER_DEFAULT_MODEL
} from '../model/ProviderProfiles';
import { TurnOutcome } from '../model/ChatMetrics';
import {
  asArray,
  asRecord,
  encodeUtf8,
  jsonBool,
  jsonNumber,
  jsonString,
  parseJsonObject,
  stringArray,
  toArrayBuffer,
  uniqueId
} from './HostUtil';

export class DraftRecord {
  sessionId: string = '';
  text: string = '';
}

export class StoredChatDocument {
  version: number = 2;
  activeSessionId: string = '';
  activeFolderId: string = 'default';
  sessions: SessionRecord[] = [];
  folders: Workspace[] = [];
  drafts: DraftRecord[] = [];
}

export class SettingsLoadResult {
  settings: Settings = new Settings();
  failure: string = '';
}

export class ChatLoadResult {
  document: StoredChatDocument = new StoredChatDocument();
  failure: string = '';
  repaired: boolean = false;
}

function turnOutcomeFromStored(value: string): TurnOutcome {
  if (value === 'failed') {
    return TurnOutcome.Failed;
  }
  if (value === 'stopped') {
    return TurnOutcome.Stopped;
  }
  if (value === 'running') {
    return TurnOutcome.Running;
  }
  return TurnOutcome.Completed;
}

function rowKind(value: string): RowKind {
  if (value === 'user') {
    return RowKind.User;
  }
  if (value === 'thinking') {
    return RowKind.Thinking;
  }
  if (value === 'tool') {
    return RowKind.Tool;
  }
  if (value === 'system') {
    return RowKind.System;
  }
  if (value === 'error') {
    return RowKind.Error;
  }
  return RowKind.Assistant;
}

function hydrateProvider(value: Object | null): ProviderConfig {
  const provider = new ProviderConfig();
  const row = asRecord(value);
  if (row === null) {
    return provider;
  }
  provider.id = jsonString(row, 'id', provider.id);
  provider.name = jsonString(row, 'name', provider.name);
  provider.baseUrl = jsonString(row, 'baseUrl', provider.baseUrl);
  provider.model = jsonString(row, 'model', provider.model);
  provider.effort = jsonString(row, 'effort', provider.effort);
  provider.encoder = jsonString(row, 'encoder', provider.encoder);
  provider.keyConfigured = false;
  return provider;
}

function hydrateProviderModel(value: Object | null): ProviderModelRecord | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const id = jsonString(row, 'id', '').trim();
  if (id.length === 0) {
    return null;
  }
  const model = new ProviderModelRecord();
  model.id = id;
  model.name = jsonString(row, 'name', '').trim();
  const contextWindow = jsonNumber(row, 'contextWindow', 0);
  model.contextWindow = contextWindow > 0 ? Math.floor(contextWindow) : 0;
  const maxTokens = jsonNumber(row, 'maxTokens', 0);
  model.maxTokens = maxTokens > 0 ? Math.floor(maxTokens) : 0;
  model.textInput = jsonBool(row, 'textInput', true);
  model.imageInput = jsonBool(row, 'imageInput', false);
  if (!model.textInput && !model.imageInput) {
    model.textInput = true;
  }
  model.reasoningEffort = jsonString(row, 'reasoningEffort', '').trim();
  return model;
}

function hydrateProviderProfile(value: Object | null): ProviderProfileRecord | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const profile = new ProviderProfileRecord();
  profile.id = jsonString(row, 'id', '').trim();
  if (profile.id.length === 0) {
    return null;
  }
  profile.name = jsonString(row, 'name', '').trim();
  if (profile.name.length === 0) {
    return null;
  }
  profile.baseUrl = jsonString(row, 'baseUrl', '').trim();
  if (profile.baseUrl.length === 0) {
    return null;
  }
  profile.model = jsonString(row, 'model', PROVIDER_DEFAULT_MODEL).trim();
  if (profile.model.length === 0) {
    profile.model = PROVIDER_DEFAULT_MODEL;
  }
  profile.encoder = jsonString(row, 'encoder', PROVIDER_DEFAULT_ENCODER).trim();
  if (profile.encoder.length === 0) {
    profile.encoder = PROVIDER_DEFAULT_ENCODER;
  }
  profile.effort = jsonString(row, 'effort', PROVIDER_DEFAULT_EFFORT).trim();
  if (profile.effort.length === 0) {
    profile.effort = PROVIDER_DEFAULT_EFFORT;
  }
  profile.reasoningEffort = jsonString(row, 'reasoningEffort', profile.effort).trim();
  if (profile.reasoningEffort.length === 0) {
    profile.reasoningEffort = profile.effort;
  }
  profile.credentialRef = jsonString(row, 'credentialRef', '').trim();
  profile.keyConfigured = false;
  const models = asArray(row['models']);
  for (let index = 0; index < models.length; index++) {
    const model = hydrateProviderModel(models[index]);
    if (model !== null) {
      profile.models.push(model);
    }
  }
  if (profile.models.length === 0) {
    const model = new ProviderModelRecord();
    model.id = profile.model;
    profile.models.push(model);
  }
  return profile;
}

function hydrateProviderProfiles(value: Object | null): ProviderProfileRecord[] {
  const profiles: ProviderProfileRecord[] = [];
  const rows = asArray(value);
  for (let index = 0; index < rows.length; index++) {
    const profile = hydrateProviderProfile(rows[index]);
    if (profile !== null) {
      let duplicate = false;
      for (let other = 0; other < profiles.length; other++) {
        if (profiles[other].id === profile.id) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        profiles.push(profile);
      }
    }
  }
  return profiles;
}

function hydrateSettings(value: Object | null): Settings {
  const settings = new Settings();
  const row = asRecord(value);
  if (row === null) {
    return settings;
  }
  settings.provider = hydrateProvider(row['provider']);
  settings.providerProfiles = hydrateProviderProfiles(row['providerProfiles']);
  settings.selectedProviderId = jsonString(row, 'selectedProviderId', '').trim();
  settings.permission = jsonString(row, 'permission', settings.permission);
  settings.busyEnter = jsonString(row, 'busyEnter', settings.busyEnter);
  settings.theme = jsonString(row, 'theme', settings.theme);
  settings.language = jsonString(row, 'language', settings.language);
  // Legacy `fontSize` is the body role; new optional roles default to Android's
  // sizes when an older document has no key, and every stored value is clamped
  // to the Android range so an old out-of-range choice cannot leak into the UI.
  settings.fontSize = FontSizes.clamp(FontRole.Text,
    jsonNumber(row, 'fontSize', FontSizes.textDefault));
  settings.codeFontSize = FontSizes.clamp(FontRole.Code,
    jsonNumber(row, 'codeFontSize', FontSizes.codeDefault));
  settings.terminalFontSize = FontSizes.clamp(FontRole.Terminal,
    jsonNumber(row, 'terminalFontSize', FontSizes.terminalDefault));
  settings.showProcess = jsonBool(row, 'showProcess', settings.showProcess);
  const performanceUsage = jsonString(row, 'performanceUsage', 'detailed');
  settings.performanceUsage = performanceUsage === 'compact' ? 'compact' : 'detailed';
  settings.backgroundKeepAlive = jsonBool(row, 'backgroundKeepAlive', false);
  settings.webSearch = jsonBool(row, 'webSearch', false);
  settings.egressHosts = stringArray(row['egressHosts']);
  return settings;
}

function hydrateRow(value: Object | null, repaired: boolean[]): ChatRow | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const chat = new ChatRow();
  chat.id = jsonString(row, 'id', '');
  chat.kind = rowKind(jsonString(row, 'kind', 'assistant'));
  chat.text = jsonString(row, 'text', '');
  chat.detail = jsonString(row, 'detail', '');
  chat.tool = jsonString(row, 'tool', '');
  chat.status = jsonString(row, 'status', '');
  chat.path = jsonString(row, 'path', '');
  chat.createdAt = jsonNumber(row, 'createdAt', 0);
  // Closed-turn timing is read from the Worker's own turn events when it was
  // observed; older documents leave the fields at their absent values.
  chat.turnId = jsonString(row, 'turnId', '');
  chat.turnOutcome = turnOutcomeFromStored(jsonString(row, 'turnOutcome', ''));
  chat.turnStartedAt = jsonNumber(row, 'turnStartedAt', 0);
  chat.turnEndedAt = jsonNumber(row, 'turnEndedAt', 0);
  if (chat.id.length === 0) {
    chat.id = uniqueId('row');
  }
  if (chat.status === 'streaming' || chat.status === 'running') {
    chat.status = 'cancelled';
    if (chat.detail.length > 0) {
      chat.detail = `${chat.detail}\n上次运行中断，已取消`;
    } else {
      chat.detail = '上次运行中断，已取消';
    }
    if (chat.kind === RowKind.Assistant && chat.text.length === 0) {
      chat.text = '上次回复在完成前中断。';
    }
    repaired.push(true);
  }
  return chat;
}

function hydrateSession(value: Object | null, repaired: boolean[]): SessionRecord | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const session = new SessionRecord();
  session.id = jsonString(row, 'id', '');
  if (session.id.length === 0) {
    return null;
  }
  session.title = jsonString(row, 'title', '新会话');
  session.folderId = jsonString(row, 'folderId', 'default');
  session.workerSessionId = jsonString(row, 'workerSessionId', '');
  session.updatedAt = jsonNumber(row, 'updatedAt', 0);
  session.pinned = jsonBool(row, 'pinned', false);
  session.archived = jsonBool(row, 'archived', false);
  const rows = asArray(row['rows']);
  const repairedBefore = repaired.length;
  for (let index = 0; index < rows.length; index++) {
    const chat = hydrateRow(rows[index], repaired);
    if (chat !== null) {
      session.rows.push(chat);
    }
  }
  // Artifact measurement is optional. An absent field stays undefined, which
  // the artifacts page reads as Unknown; it must never become an empty Ready.
  const measuredFrom = jsonString(row, 'artifactsFromMseq', '');
  if (/^(0|[1-9][0-9]{0,19})$/.test(measuredFrom)) {
    session.artifactsFromMseq = measuredFrom;
  }
  const phaseText = jsonString(row, 'artifactsPhase', '');
  if (phaseText === LoadPhase.Ready || phaseText === LoadPhase.Failed ||
    phaseText === LoadPhase.Unknown) {
    session.artifactsPhase = phaseText as LoadPhase;
  }
  if (repaired.length > repairedBefore) {
    // An interrupted turn never got its final ACK. The previous known paths stay
    // readable, but this session's measurement range is no longer current.
    session.artifactsPhase = LoadPhase.Unknown;
  }
  const artifacts = asArray(row['artifacts']);
  if (artifacts.length > 0) {
    const measured: SessionArtifact[] = [];
    for (let index = 0; index < artifacts.length && measured.length < 2000; index++) {
      const artifact = asRecord(artifacts[index]);
      if (artifact === null) {
        continue;
      }
      const path = jsonString(artifact, 'path', '');
      const mseq = jsonString(artifact, 'mseq', '');
      if (path.length === 0 || !path.startsWith('/dsh/workspace/')) {
        continue;
      }
      const entry = new SessionArtifact();
      entry.path = path;
      entry.size = jsonNumber(artifact, 'size', 0);
      entry.mseq = mseq;
      measured.push(entry);
    }
    session.artifacts = measured;
  }
  return session;
}

function hydrateWorkspace(value: Object | null): Workspace | null {
  const row = asRecord(value);
  if (row === null) {
    return null;
  }
  const id = jsonString(row, 'id', '');
  if (id.length === 0) {
    return null;
  }
  const workspace = new Workspace();
  workspace.id = id;
  workspace.name = jsonString(row, 'name', id);
  workspace.path = jsonString(row, 'path', '/dsh/workspace');
  return workspace;
}

export class HostPersistence {
  private context: common.UIAbilityContext;
  private store: preferences.Preferences | null = null;
  private initFailure: string = '';
  private chatDir: string;
  private chatPath: string;
  private bundlePath: string;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
    this.chatDir = `${context.filesDir}/host`;
    this.chatPath = `${this.chatDir}/chats.json`;
    this.bundlePath = `${context.filesDir}/runtime-bundle.zip`;
    try {
      const options: preferences.Options = { name: 'pdsh_host_settings' };
      this.store = preferences.getPreferencesSync(context, options);
    } catch (error) {
      this.initFailure = '私有设置存储不可用';
    }
  }

  runtimeBundlePath(): string {
    return this.bundlePath;
  }

  initializationFailure(): string {
    return this.initFailure;
  }

  loadSettings(): SettingsLoadResult {
    const result = new SettingsLoadResult();
    if (this.store === null) {
      result.failure = this.initFailure.length > 0 ? this.initFailure : '私有设置存储不可用';
      return result;
    }
    let raw = '';
    try {
      raw = this.store.getSync('settings', '') as string;
    } catch (error) {
      result.failure = '设置读取失败';
      return result;
    }
    if (raw.length === 0) {
      return result;
    }
    const parsed = parseJsonObject(raw);
    if (parsed === null) {
      result.failure = '设置记录损坏；已使用默认设置，未覆盖原记录';
      return result;
    }
    result.settings = hydrateSettings(parsed);
    return result;
  }

  saveSettings(settings: Settings): string {
    if (this.store === null) {
      return this.initFailure.length > 0 ? this.initFailure : '私有设置存储不可用';
    }
    try {
      this.store.putSync('settings', JSON.stringify(settings));
      this.store.flushSync();
      return '';
    } catch (error) {
      return '设置保存失败';
    }
  }

  loadChats(): ChatLoadResult {
    const result = new ChatLoadResult();
    const document = new StoredChatDocument();
    result.document = document;
    if (!this.exists(this.chatPath)) {
      this.ensureDefaultFolder(document);
      return result;
    }
    let text = '';
    try {
      text = fileIo.readTextSync(this.chatPath);
    } catch (error) {
      result.failure = '会话记录读取失败；没有改动磁盘上的记录';
      return result;
    }
    const parsed = parseJsonObject(text);
    if (parsed === null) {
      this.quarantineCorruptChats();
      result.failure = '会话记录损坏；原文件已保留，当前列表为空';
      this.ensureDefaultFolder(document);
      return result;
    }
    document.version = jsonNumber(parsed, 'version', 2);
    document.activeSessionId = jsonString(parsed, 'activeSessionId', '');
    document.activeFolderId = jsonString(parsed, 'activeFolderId', 'default');
    const repaired: boolean[] = [];
    const sessions = asArray(parsed['sessions']);
    for (let index = 0; index < sessions.length; index++) {
      const session = hydrateSession(sessions[index], repaired);
      if (session !== null) {
        document.sessions.push(session);
      }
    }
    const folders = asArray(parsed['folders']);
    for (let index = 0; index < folders.length; index++) {
      const folder = hydrateWorkspace(folders[index]);
      if (folder !== null) {
        document.folders.push(folder);
      }
    }
    const drafts = asArray(parsed['drafts']);
    for (let index = 0; index < drafts.length; index++) {
      const row = asRecord(drafts[index]);
      if (row === null) {
        continue;
      }
      const draft = new DraftRecord();
      draft.sessionId = jsonString(row, 'sessionId', '');
      draft.text = jsonString(row, 'text', '');
      if (draft.sessionId.length > 0) {
        document.drafts.push(draft);
      }
    }
    result.repaired = repaired.length > 0;
    this.ensureDefaultFolder(document);
    this.ensureSelection(document);
    return result;
  }

  saveChats(document: StoredChatDocument): string {
    try {
      const bytes = encodeUtf8(JSON.stringify(document));
      return this.writeAtomic(this.chatPath, bytes);
    } catch (error) {
      return '会话记录序列化失败';
    }
  }

  async writeBundle(bytes: Uint8Array): Promise<string> {
    const temporary = `${this.bundlePath}.tmp`;
    let fd: number = -1;
    try {
      try {
        if (await fileIo.access(temporary)) {
          await fileIo.unlink(temporary);
        }
      } catch (error) {
      }
      const file = await fileIo.open(temporary, fileIo.OpenMode.CREATE | fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
      fd = file.fd;
      let remaining = toArrayBuffer(bytes);
      let written = 0;
      while (written < bytes.byteLength) {
        const chunk = await fileIo.write(fd, remaining);
        if (chunk <= 0) {
          throw new Error('short bundle write');
        }
        written += chunk;
        if (written < bytes.byteLength) {
          remaining = toArrayBuffer(bytes.subarray(written));
        }
      }
      await fileIo.fsync(fd);
      await fileIo.close(fd);
      fd = -1;
      await fileIo.rename(temporary, this.bundlePath);
      try {
        const directory = await fileIo.open(this.parentDirectory(this.bundlePath), fileIo.OpenMode.READ_ONLY);
        try {
          await fileIo.fsync(directory.fd);
        } finally {
          await fileIo.close(directory);
        }
      } catch (error) {
      }
      return '';
    } catch (error) {
      if (fd >= 0) {
        try {
          await fileIo.close(fd);
        } catch (closeError) {
        }
      }
      try {
        if (await fileIo.access(temporary)) {
          await fileIo.unlink(temporary);
        }
      } catch (cleanupError) {
      }
      return '私有文件写入失败';
    }
  }

  writeAtomic(path: string, bytes: Uint8Array): string {
    const temporary = `${path}.tmp`;
    let fd: number = -1;
    try {
      this.ensureDirectory(this.parentDirectory(path));
      if (this.exists(temporary)) {
        fileIo.unlinkSync(temporary);
      }
      const file = fileIo.openSync(temporary, fileIo.OpenMode.CREATE | fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
      fd = file.fd;
      fileIo.writeSync(fd, toArrayBuffer(bytes));
      fileIo.fsyncSync(fd);
      fileIo.closeSync(fd);
      fd = -1;
      fileIo.renameSync(temporary, path);
      this.fsyncDirectory(this.parentDirectory(path));
      return '';
    } catch (error) {
      if (fd >= 0) {
        try {
          fileIo.closeSync(fd);
        } catch (closeError) {
        }
      }
      try {
        if (this.exists(temporary)) {
          fileIo.unlinkSync(temporary);
        }
      } catch (cleanupError) {
      }
      return '私有文件写入失败';
    }
  }

  private ensureDefaultFolder(document: StoredChatDocument): void {
    for (let index = 0; index < document.folders.length; index++) {
      if (document.folders[index].id === 'default') {
        return;
      }
    }
    const workspace = new Workspace();
    workspace.id = 'default';
    workspace.name = '默认';
    workspace.path = '/dsh/workspace';
    document.folders.unshift(workspace);
  }

  private ensureSelection(document: StoredChatDocument): void {
    let folderFound = false;
    for (let index = 0; index < document.folders.length; index++) {
      if (document.folders[index].id === document.activeFolderId) {
        folderFound = true;
        break;
      }
    }
    if (!folderFound && document.folders.length > 0) {
      document.activeFolderId = document.folders[0].id;
    }
    if (document.activeSessionId.length > 0) {
      for (let index = 0; index < document.sessions.length; index++) {
        if (document.sessions[index].id === document.activeSessionId) {
          return;
        }
      }
    }
    document.activeSessionId = '';
    for (let index = 0; index < document.sessions.length; index++) {
      const session = document.sessions[index];
      if (!session.archived) {
        document.activeSessionId = session.id;
        return;
      }
    }
  }

  private quarantineCorruptChats(): void {
    try {
      const backup = `${this.chatPath}.corrupt-${Date.now()}`;
      fileIo.renameSync(this.chatPath, backup);
    } catch (error) {
    }
  }

  private ensureDirectory(path: string): void {
    if (!this.exists(path)) {
      fileIo.mkdirSync(path, true);
    }
  }

  private parentDirectory(path: string): string {
    const separator = path.lastIndexOf('/');
    if (separator <= 0) {
      return this.chatDir;
    }
    return path.substring(0, separator);
  }

  private exists(path: string): boolean {
    try {
      return fileIo.accessSync(path);
    } catch (error) {
      return false;
    }
  }

  private fileSize(path: string): number {
    try {
      if (!this.exists(path)) {
        return -1;
      }
      return fileIo.statSync(path).size;
    } catch (error) {
      return -1;
    }
  }

  private fsyncDirectory(path: string): void {
    try {
      const directory = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      try {
        fileIo.fsyncSync(directory.fd);
      } finally {
        fileIo.closeSync(directory);
      }
    } catch (error) {
    }
  }
}
