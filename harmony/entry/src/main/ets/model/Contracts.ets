import { ImageLoadControl } from './ImageLoadControl';
// SPDX-License-Identifier: MIT
import {
  SessionModelOption,
  SessionModelSelection,
  SessionPermissionOption
} from './SessionControls';
import { ComposerCommand } from './ComposerInput';
import { PendingQuestion } from './QuestionAnswers';
import {
  ProviderModelRecord,
  ProviderProfileRecord
} from './ProviderProfiles';
import {
  ContextBreakdownDTO,
  ContextPressureDTO,
  SessionStatsDTO,
  TokenUsageDTO,
  TurnOutcome
} from './ChatMetrics';
import {
  QueueItem,
  QueueItemAction,
  QueueItemState,
  QueueOrigin
} from './QueueModel';

export {
  ProviderModelRecord,
  ProviderProfileRecord,
  PendingQuestion,
  QueueItem,
  QueueItemAction,
  QueueItemState,
  QueueOrigin
};
export enum Page {
  Chat = 'Chat',
  Sessions = 'Sessions',
  WorkspaceFolders = 'WorkspaceFolders',
  Files = 'Files',
  Automation = 'Automation',
  Configuration = 'Configuration',
  Settings = 'Settings',
  ModelProvider = 'ModelProvider',
  WebSearch = 'WebSearch',
  Mcp = 'Mcp',
  McpEditor = 'McpEditor',
  Runtime = 'Runtime',
  Egress = 'Egress',
  Workbench = 'Workbench',
  Tools = 'Tools',
  Export = 'Export',
  DeviceConnection = 'DeviceConnection',
  DeviceSession = 'DeviceSession',
  SessionArtifacts = 'SessionArtifacts',
  AgentPresets = 'AgentPresets',
  PluginInventory = 'PluginInventory',
  PhonePermissions = 'PhonePermissions',
  ChatPreferences = 'ChatPreferences',
  SettingsDocument = 'SettingsDocument',
  Shortcuts = 'Shortcuts',
  FontSize = 'FontSize',
  ArchivedSessions = 'ArchivedSessions',
  About = 'About',
  Licenses = 'Licenses',
  Preview = 'Preview',
  Probes = 'Probes',
}
export enum LoadPhase { Unknown = 'unknown', Loading = 'loading', Ready = 'ready', Failed = 'failed' }
export enum EnginePhase { Booting = 'booting', Ready = 'ready', Stopped = 'stopped', Failed = 'failed', Recovering = 'recovering' }
export enum RowKind { User = 'user', Assistant = 'assistant', Thinking = 'thinking', Tool = 'tool', System = 'system', Error = 'error' }
export class ChatRow {
  id: string = '';
  kind: RowKind = RowKind.Assistant;
  text: string = '';
  detail: string = '';
  tool: string = '';
  status: string = '';
  path: string = '';
  createdAt: number = 0;
  // Live turn facts the Worker event stream supplied. Empty/zero means the row
  // was not observed closing a turn; the page then shows no summary.
  turnId: string = '';
  turnOutcome: TurnOutcome = TurnOutcome.Completed;
  turnStartedAt: number = 0;
  turnEndedAt: number = 0;
}
/**
 * One workspace file a measured turn wrote. `path` is the absolute VFS path
 * (`/dsh/workspace/...`); `mseq` stays a decimal string because it is a u64 on
 * the native side.
 */
export class SessionArtifact {
  path: string = '';
  size: number = 0;
  mseq: string = '';
}
export class SessionRecord {
  id: string = '';
  title: string = '新会话';
  folderId: string = 'default';
  workerSessionId: string = '';
  updatedAt: number = 0;
  pinned: boolean = false;
  archived: boolean = false;
  rows: ChatRow[] = [];
  // Optional measured workspace writes. A legacy record has neither field, so
  // the page reports Unknown instead of inventing an empty measurement. A
  // measured empty array means "measured and this session wrote nothing".
  artifacts?: SessionArtifact[];
  artifactsPhase?: LoadPhase;
  artifactsFromMseq?: string;
}
export class Workspace {
  id: string = '';
  name: string = '';
  path: string = '';
}
export class FileEntry {
  name: string = '';
  path: string = '';
  directory: boolean = false;
  size: number = 0;
  modifiedAt: number = 0;
}
export class NamedItem {
  id: string = '';
  title: string = '';
  subtitle: string = '';
  enabled: boolean = true;
  detail: string = '';
  // Typed configuration items: '' is not writable through saveConfigField,
  // `number`/`boolean`/`modelSelection` pick the matching editor. `options`
  // and `selected` carry exact model routes read from session/modelCatalog.
  valueType: string = '';
  options: string[] = [];
  selected: string[] = [];
}
export class ProviderConfig {
  id: string = 'deepseek';
  name: string = 'DeepSeek（本机）';
  baseUrl: string = 'https://api.deepseek.com/anthropic';
  model: string = 'deepseek-flash';
  effort: string = 'high';
  encoder: string = 'deepseek';
  keyConfigured: boolean = false;
}
/** One adjustable text role, matching Android's `FontRole`. */
export enum FontRole {
  Text = 'text',
  Code = 'code',
  Terminal = 'terminal'
}

/** Android `FontRole` defaults and clamp ranges (`PreferenceStore.kt`). */
export class FontSizes {
  static readonly textDefault: number = 16;
  static readonly codeDefault: number = 14;
  static readonly terminalDefault: number = 13;
  static readonly textMin: number = 10;
  static readonly textMax: number = 22;
  static readonly codeMin: number = 10;
  static readonly codeMax: number = 16;
  static readonly terminalMin: number = 10;
  static readonly terminalMax: number = 20;

  static defaultFor(role: FontRole): number {
    if (role === FontRole.Code) { return FontSizes.codeDefault; }
    if (role === FontRole.Terminal) { return FontSizes.terminalDefault; }
    return FontSizes.textDefault;
  }

  static minFor(role: FontRole): number {
    if (role === FontRole.Code) { return FontSizes.codeMin; }
    if (role === FontRole.Terminal) { return FontSizes.terminalMin; }
    return FontSizes.textMin;
  }

  static maxFor(role: FontRole): number {
    if (role === FontRole.Code) { return FontSizes.codeMax; }
    if (role === FontRole.Terminal) { return FontSizes.terminalMax; }
    return FontSizes.textMax;
  }

  /** Clamps a stored/edited size into the role's Android range. */
  static clamp(role: FontRole, value: number): number {
    if (!Number.isFinite(value)) {
      return FontSizes.defaultFor(role);
    }
    const rounded: number = Math.round(value);
    const minimum: number = FontSizes.minFor(role);
    const maximum: number = FontSizes.maxFor(role);
    if (rounded < minimum) { return minimum; }
    if (rounded > maximum) { return maximum; }
    return rounded;
  }
}

export class Settings {
  // The legacy single-service record stays readable and is projected from the
  // selected profile; `providerProfiles`/`selectedProviderId` are the v2
  // document. A first read migrates the legacy row without changing its id or
  // AssetStore alias.
  provider: ProviderConfig = new ProviderConfig();
  providerProfiles: ProviderProfileRecord[] = [];
  selectedProviderId: string = '';
  permission: string = 'workspace-write';
  busyEnter: string = 'queue';
  theme: string = 'system';
  language: string = 'system';
  // Legacy `fontSize` stays the body role. The code/terminal fields are
  // optional on the wire; missing keys fall back to Android's defaults.
  fontSize: number = FontSizes.textDefault;
  codeFontSize?: number = FontSizes.codeDefault;
  terminalFontSize?: number = FontSizes.terminalDefault;
  showProcess: boolean = true;
  // Android `performanceUsage`: `detailed` (default) or `compact`.
  performanceUsage?: string = 'detailed';
  backgroundKeepAlive: boolean = false;
  webSearch: boolean = false;
  egressHosts: string[] = [];

}

export function fontSizeValue(settings: Settings, role: FontRole): number {
  if (role === FontRole.Code) {
    return settings.codeFontSize !== undefined ? FontSizes.clamp(role, settings.codeFontSize) : FontSizes.codeDefault;
  }
  if (role === FontRole.Terminal) {
    return settings.terminalFontSize !== undefined ? FontSizes.clamp(role, settings.terminalFontSize) : FontSizes.terminalDefault;
  }
  return FontSizes.clamp(FontRole.Text, settings.fontSize);
}

export function setFontSize(settings: Settings, role: FontRole, value: number): void {
  const size: number = FontSizes.clamp(role, value);
  if (role === FontRole.Code) settings.codeFontSize = size;
  else if (role === FontRole.Terminal) settings.terminalFontSize = size;
  else settings.fontSize = size;
}

/**
 * One decoded image payload owned by the calling UI component. ShellSnapshot
 * intentionally never carries this object (no ArrayBuffer/PixelMap in state);
 * the component releases its PixelMap on replace/exit.
 */
export class ImageBytes {
  data: ArrayBuffer = new ArrayBuffer(0);
  mime: string = '';
}

export class PendingRequest {
  id: string = '';
  kind: string = '';
  title: string = '';
  detail: string = '';
  choices: string[] = [];
  // Exact DSH question projection for a real user-questions request. Permission
  // cards leave this empty; a question card owns the composer seat.
  questions: PendingQuestion[] = [];
}
export class ShellSnapshot {
  revision: number = 0;
  page: Page = Page.Chat;
  dark: boolean = false;
  // Debug build fact from the installed application info. The generic snapshot
  // projection carries it to the UI; release builds keep it false.
  debuggable: boolean = false;
  engine: EnginePhase = EnginePhase.Booting;
  engineText: string = '正在启动本地引擎';
  runtimeUrl: string = '';
  notice: string = '';
  failure: string = '';
  sessionsPhase: LoadPhase = LoadPhase.Unknown;
  sessions: SessionRecord[] = [];
  folders: Workspace[] = [];
  activeSessionId: string = '';
  activeFolderId: string = 'default';
  title: string = '新会话';
  rows: ChatRow[] = [];
  draft: string = '';
  running: boolean = false;
  stopping: boolean = false;
  streamingText: string = '';
  reasoningText: string = '';
  // Durable DSH inbox rows. `null` means the sequenced projection was not read
  // or was invalid/migration-required; `[]` is a confirmed empty queue. Display
  // must never derive a queue count from local turn bookkeeping.
  queueItems: QueueItem[] | null = null;
  localQueueItems: QueueItem[] = [];
  queueFailure: string = '';
  settings: Settings = new Settings();
  pending: PendingRequest[] = [];
  filesPhase: LoadPhase = LoadPhase.Unknown;
  files: FileEntry[] = [];
  filePath: string = '/dsh/workspace';
  previewPath: string = '';
  previewText: string = '';
  itemsPhase: LoadPhase = LoadPhase.Unknown;
  items: NamedItem[] = [];
  runtimeDetail: string = '';
  command: string = '';
  commandOutput: string = '';
  goal: string = '';
  detail: string = '';
  attachmentPaths: string[] = [];
  // Registered slash-command catalog from `commands/list`; suggestions and
  // dispatches read only these rows. `commandError` keeps catalog failures
  // visible instead of silently showing an empty menu.
  commands: ComposerCommand[] = [];
  commandError: string = '';
  lastCommandId: string = '';
  // In-place `@` reference candidates (Android `chatMentionFacts`): workspace
  // relative file paths and the titles of other non-archived chats. The phase
  // and error make a failed local read visible instead of an empty success.
  referenceFiles: string[] = [];
  referenceSessions: string[] = [];
  referencesPhase: LoadPhase = LoadPhase.Unknown;
  referencesError: string = '';
  // Live composer controls. These mirror the Worker's session catalog and
  // projections; empty values keep the configured-provider boot overlay in the
  // UI instead of inventing a selection.
  modelOptions: SessionModelOption[] = [];
  modelSelection: SessionModelSelection = new SessionModelSelection();
  modelError: string = '';
  permissionOptions: SessionPermissionOption[] = [];
  currentPermission: string = '';
  permissionError: string = '';
  // Worker `session/projections` metrics. Null/absent means the projection did
  // not report the object; the footer hides the matching pill instead of
  // rendering 0.
  sessionStats?: SessionStatsDTO | null = null;
  tokenUsage?: TokenUsageDTO | null = null;
  contextPressure?: ContextPressureDTO | null = null;
  contextBreakdown?: ContextBreakdownDTO | null = null;
}
export interface ShellActions {
  navigate(page: Page, id?: string): void;
  back(): void;
  clearNotice(): void;
  setDraft(text: string): void;
  send(text: string): Promise<void>;
  /**
   * Mutates one durable `next-turn` row and publishes success only after a
   * fresh sequenced projection confirms the edit/remove/real steer.
   */
  updateQueueItem(workerSessionId: string, itemId: string, action: QueueItemAction,
    replacement?: string): Promise<void>;
  stopTurn(): Promise<void>;
  newSession(folderId?: string): Promise<void>;
  openSession(id: string): Promise<void>;
  renameSession(id: string, title: string): Promise<void>;
  pinSession(id: string, pinned: boolean): Promise<void>;
  archiveSession(id: string, archived: boolean): Promise<void>;
  deleteSession(id: string): Promise<void>;
  createFolder(name: string): Promise<void>;
  renameFolder(id: string, name: string): Promise<void>;
  deleteFolder(id: string): Promise<void>;
  selectFolder(id: string): Promise<void>;
  saveProvider(provider: ProviderConfig, key: string): Promise<void>;
  deleteProviderKey(): Promise<void>;
  testProvider(): Promise<void>;
  saveProviderProfile(profile: ProviderProfileRecord, key: string, isNew: boolean): Promise<void>;
  selectProviderProfile(id: string): Promise<void>;
  deleteProviderProfile(id: string): Promise<void>;
  testProviderProfile(id: string): Promise<void>;
  saveSettings(settings: Settings): Promise<void>;
  selectModel(selection: SessionModelSelection): Promise<void>;
  selectPermission(id: string): Promise<void>;
  startEngine(): Promise<void>;
  stopEngine(): Promise<void>;
  simulateRendererExit(): Promise<void>;
  refresh(): Promise<void>;
  refreshCommands(): Promise<void>;
  /**
   * Reads the in-place `@` candidates locally: workspace-relative file paths
   * through the verified Worker workspace, and other non-archived chat titles.
   * No reference RPC: an unreadable workspace is a visible failure.
   */
  refreshReferences(): Promise<void>;
  openFile(path: string): Promise<void>;
  /**
   * Resolves a Markdown image/resource identity and opens its preview. Refusals
   * are reported through the visible failure surface, never silently rerouted.
   */
  openResource(source: string): Promise<void>;
  /**
   * Loads bytes for one Markdown image source. The returned data stays with the
   * component that requested it; the HostService holds no snapshot copy.
   */
  loadImage(source: string, control?: ImageLoadControl): Promise<ImageBytes>;
  pickFiles(images: boolean): Promise<void>;
  removeAttachment(path: string): void;
  exportSession(): Promise<void>;
  runCommand(command: string): Promise<void>;
  answerRequest(id: string, answer: string): Promise<void>;
  /** Hides one visible pending prompt without answering or cancelling it. */
  dismissPending(id: string): Promise<void>;
  setItemEnabled(id: string, enabled: boolean): Promise<void>;
  saveItem(id: string, value: string): Promise<void>;
  deleteItem(id: string): Promise<void>;
}
