// SPDX-License-Identifier: MIT
// Native palette and metrics. Values are the reviewed spec/ui/v1 tokens:
// HomeSpec colours, typography bases, geometry and Android PR22 settings chrome.
// No page invents a colour outside these roles.
import i18n from '@ohos.i18n';
import {
  ShellSnapshot, Workspace, Settings, ProviderConfig, ProviderModelRecord, ProviderProfileRecord,
  EnginePhase, LoadPhase, FontRole
} from '../model/Contracts';
import { LocaleText } from '../model/LocaleText';
import { detachedSettings } from '../model/SnapshotProjection';

export class Palette {
  dark: boolean = false;

  constructor(dark: boolean) {
    this.dark = dark;
  }

  get page(): string { return this.dark ? '#101012' : '#F7F7F7'; }
  get surface(): string { return this.dark ? '#1C1C1E' : '#FFFFFF'; }
  get userBubble(): string { return this.dark ? '#2C2C2E' : '#E8E8E8'; }
  get primaryButton(): string { return this.dark ? '#F2F2F2' : '#191919'; }
  get primaryButtonInk(): string { return this.dark ? '#111111' : '#FFFFFF'; }
  get textPrimary(): string { return this.dark ? '#F2F2F2' : '#1A1A1A'; }
  get textSecondary(): string { return this.dark ? '#B8B8BD' : '#555555'; }
  get textTertiary(): string { return this.dark ? '#9C9CA2' : '#717171'; }
  get textSubtle(): string { return this.dark ? '#8E8E93' : '#7D7D7D'; }
  get textPlaceholder(): string { return this.dark ? '#7C7C82' : '#888888'; }
  get inputPlaceholder(): string { return this.dark ? '#7C7C82' : '#8B8B8B'; }
  get iconMuted(): string { return this.dark ? '#6C6C70' : '#A9A9A9'; }
  get success(): string { return '#3DC979'; }
  get borderList(): string { return this.dark ? '#2E2E32' : '#E2E2E2'; }
  get borderL1(): string { return this.dark ? '#0FFFFFFF' : '#0A000000'; }
  get borderL2(): string { return this.dark ? '#1FFFFFFF' : '#1A000000'; }
  get sessionPrimary(): string { return this.dark ? '#F9FAFB' : '#1A1A1A'; }
  get sessionSecondary(): string { return this.dark ? '#CFD3D6' : '#555555'; }
  get sessionTertiary(): string { return this.dark ? '#ADB2B8' : '#888888'; }
  get sessionCaption(): string { return this.dark ? '#81858C' : '#A9A9A9'; }
  get sessionAccent(): string { return this.dark ? '#679EFE' : '#1A1A1A'; }
  get sessionDanger(): string { return this.dark ? '#F25A5A' : '#EC1313'; }
  get sessionWarning(): string { return '#DD8629'; }
  get sessionSuccess(): string { return this.dark ? '#22C55E' : '#3DC979'; }
  get pageWarning(): string { return this.dark ? '#F5B45A' : '#B45309'; }
  get pageDanger(): string { return this.dark ? '#F25A5A' : '#C0392B'; }
  get sessionSelector(): string { return this.dark ? '#353638' : '#EFEFEF'; }
  get sessionCode(): string { return this.dark ? '#1B1B1E' : '#FFFFFF'; }
  get sessionCodeBanner(): string { return this.dark ? '#232325' : '#EFEFEF'; }
  get sessionInlineCode(): string { return this.dark ? '#2A2A2C' : '#EFEFEF'; }
  get scrim(): string { return '#48000000'; }
}

export class Metrics {
  static readonly chromeInset: number = 16;
  static readonly contentInset: number = 20;
  static readonly navTop: number = 8;
  static readonly navButton: number = 44;
  static readonly navPillWidth: number = 92;
  static readonly navIcon: number = 22;
  static readonly headerHeight: number = 44;
  static readonly processRowHeight: number = 24;
  static readonly processIcon: number = 18;
  static readonly listRowHeight: number = 22;
  static readonly sendButton: number = 36;
  static readonly composerMinHeight: number = 104;
  static readonly composerMaxHeight: number = 336;
  static readonly composerControlHeight: number = 44;
  static readonly composerIcon: number = 24;
  static readonly bubbleRadius: number = 24;
  static readonly listCardRadius: number = 18;
  static readonly composerRadius: number = 24;
  static readonly cardRadius: number = 12;
  static readonly settingsCardRadius: number = 26;
  static readonly rowHeight: number = 48;
  static readonly compactRowHeight: number = 44;
  static readonly bubblePaddingH: number = 16;
  static readonly bubblePaddingV: number = 12;
  static readonly bubbleMaxWidth: string = '80%';
  static readonly paragraphGap: number = 16;
  static readonly blockGap: number = 20;
  static readonly disclosureGap: number = 8;
  static readonly cardPadding: number = 14;
  static readonly primaryButtonHeight: number = 48;
  static readonly secondaryButtonHeight: number = 44;
  static readonly homePrimaryButtonHeight: number = 50;
  static readonly pageHeaderMinHeight: number = 44;
  static readonly pageRowMinHeight: number = 44;
  static readonly hairline: number = 0.5;
  static readonly drawerWidth: string = '80%';
  static readonly drawerRowHeight: number = 44;
  static readonly drawerFolderHeight: number = 40;
  static readonly drawerRowRadius: number = 12;
  static readonly statusDot: number = 8;
  static readonly minTouch: number = 44;
  static readonly navTitleSize: number = 17.5;
  static readonly navSubtitleSize: number = 14.5;
  static readonly bodySize: number = 16;
  static readonly bodyLine: number = 24;
  static readonly processSize: number = 15;
  static readonly listItemSize: number = 14;
  static readonly listItemLine: number = 22;
  static readonly secondarySize: number = 13;
  static readonly captionSize: number = 12;
  static readonly pageTitleSize: number = 17;
  static readonly largeTitleSize: number = 28;
  static readonly buttonLabelSize: number = 16;
  static readonly tagLabelSize: number = 12;
}

export class Copy {
  static readonly empty: string = '没有可显示的内容';
  static readonly loading: string = '正在读取…';
  static readonly settings: string = '设置';
  static readonly notifications: string = '通知权限';
  static readonly battery: string = '电池优化';
  static readonly backGesture: string = '返回手势';

  /**
   * The effective UI language: an explicit `zh` / `en` preference wins; a
   * `system` choice resolves against the real system language reported by the
   * platform, cached in AppStorage by HostService / the first call.
   */
  static currentLanguage(): string {
    const setting: string | undefined = AppStorage.get<string>('pdshLocaleSetting');
    const raw: string = setting !== undefined && setting.length > 0 ? setting : 'system';
    return LocaleText.resolve(raw, Copy.systemLanguageTag());
  }

  static systemLanguageTag(): string {
    const stored: string | undefined = AppStorage.get<string>('pdshSystemLanguage');
    if (stored !== undefined && stored.length > 0) {
      return stored;
    }
    let tag: string = '';
    try {
      tag = i18n.System.getSystemLanguage();
    } catch (error) {
      tag = '';
    }
    if (tag.length === 0) { tag = 'zh'; }
    AppStorage.setOrCreate('pdshSystemLanguage', tag);
    return tag;
  }

  /** Static chrome copy lookup; never call this with user/model text. */
  static tr(chinese: string, english?: string): string {
    return LocaleText.tr(Copy.currentLanguage(), chinese, english);
  }

  static fill(chineseTemplate: string, values: string[]): string {
    return LocaleText.fill(Copy.currentLanguage(), chineseTemplate, values);
  }

  static get unknown(): string { return Copy.tr('未知'); }
  static get unavailable(): string { return Copy.tr('不可用'); }
  static get back(): string { return Copy.tr('返回'); }
  static get done(): string { return Copy.tr('完成'); }
  static get cancel(): string { return Copy.tr('取消'); }
  static get save(): string { return Copy.tr('保存'); }
  static get close(): string { return Copy.tr('关闭'); }
  static get delete(): string { return Copy.tr('删除'); }
  static get rename(): string { return Copy.tr('重命名'); }
  static get refresh(): string { return Copy.tr('刷新'); }
  static get retry(): string { return Copy.tr('重试'); }

  static engineLabel(engine: EnginePhase): string {
    if (engine === EnginePhase.Ready) { return Copy.tr('引擎就绪'); }
    if (engine === EnginePhase.Booting) { return Copy.tr('引擎启动中'); }
    if (engine === EnginePhase.Recovering) { return Copy.tr('引擎恢复中'); }
    if (engine === EnginePhase.Stopped) { return Copy.tr('引擎未启动'); }
    if (engine === EnginePhase.Failed) { return Copy.tr('引擎启动失败'); }
    return Copy.unknown;
  }

  static permissionLabel(permission: string): string {
    if (permission === 'read-only') { return Copy.tr('仅可查看'); }
    if (permission === 'workspace-write') { return Copy.tr('工作区内修改'); }
    if (permission === 'full-access') { return Copy.tr('完全权限'); }
    if (permission.length === 0) { return Copy.tr('未设置'); }
    return permission;
  }

  static themeLabel(theme: string): string {
    if (theme === 'system') { return Copy.tr('跟随系统'); }
    if (theme === 'light') { return Copy.tr('浅色'); }
    if (theme === 'dark') { return Copy.tr('深色'); }
    return theme.length > 0 ? theme : Copy.unknown;
  }

  static languageLabel(language: string): string {
    if (language === 'system') { return Copy.tr('跟随系统'); }
    if (language === 'zh') { return Copy.tr('中文'); }
    if (language === 'en') { return Copy.tr('English'); }
    return language.length > 0 ? language : Copy.unknown;
  }

  static phaseLabel(phase: LoadPhase): string {
    if (phase === LoadPhase.Unknown) { return Copy.unknown; }
    if (phase === LoadPhase.Loading) { return Copy.tr(Copy.loading); }
    if (phase === LoadPhase.Ready) { return Copy.tr('已就绪'); }
    if (phase === LoadPhase.Failed) { return Copy.tr('读取失败'); }
    return Copy.unknown;
  }

  static toolStatusLabel(status: string): string {
    if (status === 'running') { return Copy.tr('运行中'); }
    if (status === 'done' || status === 'completed' || status === 'success' || status === 'ok') { return Copy.tr('完成'); }
    if (status === 'failed' || status === 'error') { return Copy.tr('失败'); }
    if (status === 'interrupted' || status === 'cancelled' || status === 'stopped') { return Copy.tr('已停止'); }
    return status.length > 0 ? status : '';
  }

  static relativeTime(timestampMillis: number): string {
    if (timestampMillis <= 0) { return ''; }
    const delta: number = Math.floor((Date.now() - timestampMillis) / 1000);
    if (delta < 60) { return Copy.tr('刚刚'); }
    if (delta < 3600) { return Copy.fill('{0} 分钟前', [Math.floor(delta / 60).toString()]); }
    if (delta < 86400) { return Copy.fill('{0} 小时前', [Math.floor(delta / 3600).toString()]); }
    const date: Date = new Date(timestampMillis);
    return Copy.fill('{0}月{1}日', [(date.getMonth() + 1).toString(), date.getDate().toString()]);
  }

  static folderNameFor(snapshot: ShellSnapshot, folderId: string): string {
    for (let i: number = 0; i < snapshot.folders.length; i++) {
      const folder: Workspace = snapshot.folders[i];
      if (folder.id === folderId) {
        return folder.name.length > 0 ? folder.name : Copy.tr('未命名文件夹');
      }
    }
    if (folderId === 'default' || folderId.length === 0) { return Copy.tr('默认文件夹'); }
    return Copy.unknown;
  }

  static parentPath(path: string): string {
    if (path.length <= 1) { return path; }
    let trimmed: string = path;
    if (trimmed.endsWith('/')) { trimmed = trimmed.substring(0, trimmed.length - 1); }
    const slash: number = trimmed.lastIndexOf('/');
    if (slash <= 0) { return '/'; }
    return trimmed.substring(0, slash);
  }

  static countLabel(count: number): string {
    return Copy.fill('{0} 项', [count.toString()]);
  }

  static cloneProvider(source: ProviderConfig): ProviderConfig {
    const next: ProviderConfig = new ProviderConfig();
    next.id = source.id;
    next.name = source.name;
    next.baseUrl = source.baseUrl;
    next.model = source.model;
    next.effort = source.effort;
    next.encoder = source.encoder;
    next.keyConfigured = source.keyConfigured;
    return next;
  }

  static cloneProviderModel(source: ProviderModelRecord): ProviderModelRecord {
    const next: ProviderModelRecord = new ProviderModelRecord();
    next.id = source.id;
    next.name = source.name;
    next.contextWindow = source.contextWindow;
    next.maxTokens = source.maxTokens;
    next.textInput = source.textInput;
    next.imageInput = source.imageInput;
    next.reasoningEffort = source.reasoningEffort;
    return next;
  }

  static cloneProviderProfile(source: ProviderProfileRecord): ProviderProfileRecord {
    const next: ProviderProfileRecord = new ProviderProfileRecord();
    next.id = source.id;
    next.name = source.name;
    next.baseUrl = source.baseUrl;
    next.model = source.model;
    next.encoder = source.encoder;
    next.effort = source.effort;
    next.reasoningEffort = source.reasoningEffort;
    next.credentialRef = source.credentialRef;
    next.keyConfigured = source.keyConfigured;
    for (let index: number = 0; index < source.models.length; index++) {
      next.models.push(Copy.cloneProviderModel(source.models[index]));
    }
    return next;
  }

  static cloneSettings(source: Settings): Settings {
    return detachedSettings(source);
  }
}
