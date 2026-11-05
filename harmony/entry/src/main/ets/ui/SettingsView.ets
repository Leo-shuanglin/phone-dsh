// SPDX-License-Identifier: MIT
// Settings index and the model/runtime/network settings family. Every action is
// a promised ShellActions call; unread facts stay 未知 rather than a default.

import {
  ShellSnapshot, ShellActions, Page, Settings, ProviderProfileRecord, ProviderModelRecord,
  NamedItem, LoadPhase, EnginePhase
} from '../model/Contracts';
import { Palette, Metrics, Copy } from './Palette';
import {
  NavHeader, SectionLabel, NavRow, InfoRow, SwitchRow, PillButton, EmptyState,
  LoadingBlock, NoticeBanner, Hairline
} from './ShellSupport';
import { NativeIcon } from './NativeIcon';
import { BusinessError } from '@kit.BasicServicesKit';

@Component
export struct SettingsIndexView {
  @Prop @Watch('onSnapshotUpdated') snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State menuKind: string = '';

  onSnapshotUpdated(): void {
    if (this.snapshot.notice.length > 0) {
      this.actionError = '';
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('SettingsIndexView requires ShellActions');
    }
    return actions;
  }

  private run(promise: Promise<void>): void {
    promise.catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private archivedCount(): number {
    let count: number = 0;
    for (let i: number = 0; i < this.snapshot.sessions.length; i++) {
      if (this.snapshot.sessions[i].archived) {
        count++;
      }
    }
    return count;
  }

  private archivedValue(): string {
    if (this.snapshot.sessionsPhase === LoadPhase.Unknown) { return Copy.unknown; }
    if (this.snapshot.sessionsPhase === LoadPhase.Loading) { return Copy.loading; }
    if (this.snapshot.sessionsPhase === LoadPhase.Failed) { return Copy.tr('读取失败'); }
    return Copy.fill('{0} 个会话', [this.archivedCount().toString()]);
  }

  private saveSettings(kind: string, value: string): void {
    const next: Settings = Copy.cloneSettings(this.snapshot.settings);
    if (kind === 'appearance') { next.theme = value; }
    if (kind === 'language') { next.language = value; }
    if (kind === 'permission') { next.permission = value; }
    if (kind === 'busyEnter') { next.busyEnter = value; }
    this.menuKind = '';
    this.run(this.requireActions().saveSettings(next));
  }

  private menuTitle(): string {
    if (this.menuKind === 'appearance') { return Copy.tr('外观'); }
    if (this.menuKind === 'language') { return Copy.tr('语言'); }
    if (this.menuKind === 'permission') { return Copy.tr('新会话默认权限'); }
    if (this.menuKind === 'busyEnter') { return Copy.tr('繁忙时的发送行为'); }
    return '';
  }

  private menuOptionSelected(value: string): boolean {
    if (this.menuKind === 'appearance') { return this.snapshot.settings.theme === value; }
    if (this.menuKind === 'language') { return this.snapshot.settings.language === value; }
    if (this.menuKind === 'permission') { return this.snapshot.settings.permission === value; }
    if (this.menuKind === 'busyEnter') { return this.snapshot.settings.busyEnter === value; }
    return false;
  }

  @Builder
  MenuOption(label: string, value: string) {
    Row() {
      Text(label)
        .fontSize(Metrics.bodySize)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
      if (this.menuOptionSelected(value)) {
        NativeIcon({ name: '已选', symbol: 'CHECK', dark: this.dark, iconSize: 16 })
          .width(16)
          .height(16)
      }
    }
    .width('100%')
    .height(48)
    .padding({ left: 18, right: 18 })
    .id('settings.menu.option.' + this.menuKind + '.' + value)
    .onClick(() => {
      this.saveSettings(this.menuKind, value);
    })
  }

  @Builder
  MenuSheet() {
    if (this.menuKind.length > 0) {
      Column() {
        Column() {
          Text(this.menuTitle())
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.pal().textPrimary)
            .width('100%')
            .padding({ left: 18, right: 18, top: 16, bottom: 8 })
          if (this.menuKind === 'appearance') {
            this.MenuOption(Copy.tr('跟随系统'), 'system')
            this.MenuOption(Copy.tr('浅色'), 'light')
            this.MenuOption(Copy.tr('深色'), 'dark')
          } else if (this.menuKind === 'language') {
            this.MenuOption(Copy.tr('跟随系统'), 'system')
            this.MenuOption(Copy.tr('中文'), 'zh')
            this.MenuOption('English', 'en')
          } else if (this.menuKind === 'busyEnter') {
            this.MenuOption(Copy.tr('排到下一轮'), 'queue')
            this.MenuOption(Copy.tr('补充当前任务'), 'steer')
          } else {
            this.MenuOption(Copy.tr('仅可查看'), 'read-only')
            this.MenuOption(Copy.tr('工作区内修改'), 'workspace-write')
            this.MenuOption(Copy.tr('完全权限'), 'full-access')
            Text(Copy.tr('只影响之后新开的会话，当前会话保持不变。'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .lineHeight(17)
              .padding({ left: 18, right: 18, top: 4, bottom: 8 })
          }
          Text(Copy.tr('取消'))
            .fontSize(15)
            .fontColor(this.pal().textSecondary)
            .height(44)
            .width('100%')
            .textAlign(TextAlign.Center)
            .id('settings.menu.cancel')
            .onClick(() => {
              this.menuKind = '';
            })
        }
        .width('86%')
        .backgroundColor(this.pal().surface)
        .borderRadius(20)
        .padding({ bottom: 6 })
        .shadow({ radius: 24, color: '#44000000', offsetY: 8 })
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.pal().scrim)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)
    }
  }

  @Builder
  ProviderGroup() {
    SectionLabel({ label: Copy.tr('模型与智能体'), dark: this.dark })
    Column() {
      NavRow({
        label: Copy.tr('模型服务'), value: this.snapshot.settings.provider.name, glyph: 'CPU',
        dark: this.dark, rowId: 'settings.row.modelProvider', showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.ModelProvider);
        }
      })
      NavRow({
        label: Copy.tr('Agent 预设'), glyph: 'PEOPLE', dark: this.dark, rowId: 'settings.row.agentPresets',
        showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.AgentPresets);
        }
      })
      NavRow({
        label: Copy.tr('插件与工具'), glyph: 'PUZZLE', dark: this.dark, rowId: 'settings.row.plugins',
        showDivider: false,
        onTap: () => {
          this.requireActions().navigate(Page.Configuration);
        }
      })
    }
    .backgroundColor(this.pal().surface)
    .borderRadius(Metrics.settingsCardRadius)
    .clip(true)
    .margin({ left: 16, right: 16 })
  }

  @Builder
  PermissionGroup() {
    SectionLabel({ label: Copy.tr('权限与隐私'), dark: this.dark })
    Column() {
      NavRow({
        label: Copy.tr('新会话默认权限'), value: Copy.permissionLabel(this.snapshot.settings.permission),
        glyph: 'LOCK_SHIELD', dark: this.dark, rowId: 'settings.row.permission', showDivider: true,
        onTap: () => {
          this.menuKind = 'permission';
        }
      })
      NavRow({
        label: Copy.tr('手机权限'), value: Copy.unknown, glyph: 'HAND', dark: this.dark,
        rowId: 'settings.row.phonePermissions', showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.PhonePermissions);
        }
      })
      NavRow({
        label: Copy.tr('网络访问'), glyph: 'NETWORK', dark: this.dark, rowId: 'settings.row.egress',
        showDivider: false,
        onTap: () => {
          this.requireActions().navigate(Page.Egress);
        }
      })
    }
    .backgroundColor(this.pal().surface)
    .borderRadius(Metrics.settingsCardRadius)
    .clip(true)
    .margin({ left: 16, right: 16 })
  }

  @Builder
  ConversationGroup() {
    SectionLabel({ label: Copy.tr('对话'), dark: this.dark })
    Column() {
      NavRow({
        label: Copy.tr('外观'), value: Copy.themeLabel(this.snapshot.settings.theme), glyph: 'GEAR',
        dark: this.dark, rowId: 'settings.row.appearance', showDivider: true,
        onTap: () => {
          this.menuKind = 'appearance';
        }
      })
      NavRow({
        label: Copy.tr('语言'), value: Copy.languageLabel(this.snapshot.settings.language), glyph: 'GLOBE',
        dark: this.dark, rowId: 'settings.row.language', showDivider: true,
        onTap: () => {
          this.menuKind = 'language';
        }
      })
      NavRow({
        label: Copy.tr('字号'), value: this.snapshot.settings.fontSize.toString() + ' pt', glyph: 'TEXT_SIZE',
        dark: this.dark, rowId: 'settings.row.fontSize', showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.FontSize);
        }
      })
      NavRow({
        label: Copy.tr('工作过程'), glyph: 'LIST', dark: this.dark, rowId: 'settings.row.chatPreferences',
        showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.ChatPreferences);
        }
      })
      NavRow({
        label: Copy.tr('快捷键'), glyph: 'COMMAND', dark: this.dark, rowId: 'settings.row.shortcuts',
        showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.Shortcuts);
        }
      })
      NavRow({
        label: Copy.tr('繁忙时的发送行为'),
        value: Copy.tr(this.snapshot.settings.busyEnter === 'steer' ? '补充当前任务' : '排到下一轮'),
        glyph: 'LIST', dark: this.dark, rowId: 'settings.row.busyEnter', showDivider: false,
        onTap: () => {
          this.menuKind = 'busyEnter';
        }
      })
    }
    .backgroundColor(this.pal().surface)
    .borderRadius(Metrics.settingsCardRadius)
    .clip(true)
    .margin({ left: 16, right: 16 })
  }

  @Builder
  SessionsFilesGroup() {
    SectionLabel({ label: Copy.tr('会话与文件'), dark: this.dark })
    Column() {
      NavRow({
        label: Copy.tr('文件夹'), value: Copy.fill('{0} 个', [this.snapshot.folders.length.toString()]), glyph: 'FOLDER',
        dark: this.dark, rowId: 'settings.row.folders', showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.WorkspaceFolders);
        }
      })
      NavRow({
        label: Copy.tr('已归档会话'), value: this.archivedValue(), glyph: 'ARCHIVE', dark: this.dark,
        rowId: 'settings.row.archived', showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.ArchivedSessions);
        }
      })
      NavRow({
        label: Copy.tr('文件'), glyph: 'DOCUMENT', dark: this.dark, rowId: 'settings.row.files',
        showDivider: false,
        onTap: () => {
          this.requireActions().navigate(Page.Files);
        }
      })
    }
    .backgroundColor(this.pal().surface)
    .borderRadius(Metrics.settingsCardRadius)
    .clip(true)
    .margin({ left: 16, right: 16 })
  }

  @Builder
  EngineGroup() {
    SectionLabel({ label: Copy.tr('引擎'), dark: this.dark })
    Column() {
      InfoRow({
        label: Copy.tr('引擎状态'),
        value: this.snapshot.engineText.length > 0
          ? this.snapshot.engineText : Copy.engineLabel(this.snapshot.engine),
        dark: this.dark,
        showDivider: this.snapshot.engine === EnginePhase.Failed
      })
      if (this.snapshot.engine === EnginePhase.Failed) {
        NavRow({
          label: Copy.tr('重新启动引擎'), glyph: 'REFRESH', dark: this.dark, rowId: 'settings.row.restartEngine',
          showDivider: true,
          onTap: () => {
            this.run(this.requireActions().startEngine());
          }
        })
      }
      NavRow({
        label: Copy.tr('运行状态'), glyph: 'WAVEFORM', dark: this.dark, rowId: 'settings.row.runtime',
        showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.Runtime);
        }
      })
      NavRow({
        label: Copy.tr('配置文件'), glyph: 'DOCUMENT', dark: this.dark, rowId: 'settings.row.document',
        showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.SettingsDocument);
        }
      })
      NavRow({
        label: Copy.tr('导出诊断'), glyph: 'DOCUMENT', dark: this.dark, rowId: 'settings.row.export',
        showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.Export);
        }
      })
      NavRow({
        label: Copy.tr('设备连接'), glyph: 'DEVICE', dark: this.dark, rowId: 'settings.row.device',
        showDivider: false,
        onTap: () => {
          this.requireActions().navigate(Page.DeviceConnection);
        }
      })
    }
    .backgroundColor(this.pal().surface)
    .borderRadius(Metrics.settingsCardRadius)
    .clip(true)
    .margin({ left: 16, right: 16 })
    if (this.snapshot.engine === EnginePhase.Failed) {
      Text(Copy.tr('上次启动失败后引擎不会自动重试。重新启动会确认这次失败并重新启动，会话数据不受影响。'))
        .fontSize(Metrics.captionSize)
        .fontColor(this.pal().textSubtle)
        .lineHeight(17)
        .padding({ left: 24, right: 24, top: 8 })
    }
  }

  @Builder
  AboutGroup() {
    SectionLabel({ label: Copy.tr('关于'), dark: this.dark })
    Column() {
      NavRow({
        label: Copy.tr('关于 Phone DSH'), glyph: 'INFO', dark: this.dark, rowId: 'settings.row.about',
        showDivider: true,
        onTap: () => {
          this.requireActions().navigate(Page.About);
        }
      })
      InfoRow({ label: Copy.tr('版本'), value: Copy.unknown, dark: this.dark, showDivider: true })
      NavRow({
        label: Copy.tr('开源许可'), glyph: 'DOCUMENT', dark: this.dark, rowId: 'settings.row.licenses',
        showDivider: false,
        onTap: () => {
          this.requireActions().navigate(Page.Licenses);
        }
      })
    }
    .backgroundColor(this.pal().surface)
    .borderRadius(Metrics.settingsCardRadius)
    .clip(true)
    .margin({ left: 16, right: 16 })
  }

  @Builder
  PlatformGroup() {
    SectionLabel({ label: Copy.tr('平台差异'), dark: this.dark })
    Column() {
      InfoRow({ label: Copy.tr('通知权限'), value: Copy.unknown, dark: this.dark, showDivider: true })
      InfoRow({ label: Copy.tr('电池优化'), value: Copy.unknown, dark: this.dark, showDivider: true })
      InfoRow({ label: Copy.tr('返回手势'), value: Copy.unknown, dark: this.dark, showDivider: false })
    }
    .backgroundColor(this.pal().surface)
    .borderRadius(Metrics.settingsCardRadius)
    .clip(true)
    .margin({ left: 16, right: 16 })
    Text(Copy.tr('读取不到这项状态，未做任何假设'))
      .fontSize(Metrics.captionSize)
      .fontColor(this.pal().textSubtle)
      .padding({ left: 24, right: 24, top: 8 })
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Column() {
        NavHeader({
          title: Copy.tr('设置'),
          dark: this.dark,
          showBack: false,
          rightText: Copy.tr('完成'),
          backId: 'settings.back',
          rightId: 'settings.done',
          onBack: () => {
            this.requireActions().back();
          },
          onRight: () => {
            this.requireActions().back();
          }
        })
        if (this.actionError.length > 0) {
          Row() {
            NoticeBanner({
              text: this.actionError, dark: this.dark, kind: 'error', showClose: true,
              bannerId: 'settings.error',
              onClose: () => {
                this.actionError = '';
              }
            })
          }
          .padding({ left: 16, right: 16 })
        }
        if (this.snapshot.notice.length > 0) {
          Row() {
            NoticeBanner({
              text: this.snapshot.notice, dark: this.dark, kind: 'info', showClose: true,
              bannerId: 'settings.notice',
              onClose: () => {
                this.requireActions().clearNotice();
              }
            })
          }
          .padding({ left: 16, right: 16 })
        }
        Scroll() {
          Column() {
            this.ProviderGroup()
            this.PermissionGroup()
            this.ConversationGroup()
            this.SessionsFilesGroup()
            this.EngineGroup()
            this.AboutGroup()
            this.PlatformGroup()
            Text('')
              .height(32)
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
        .scrollBar(BarState.Off)
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.pal().page)
      this.MenuSheet()
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
export struct ModelProviderView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State editorOpen: boolean = false;
  @State editIsNew: boolean = false;
  @State editId: string = '';
  @State editCredentialRef: string = '';
  @State editEffort: string = 'high';
  @State editReasoningEffort: string = 'high';
  @State editModels: ProviderModelRecord[] = [];
  @State name: string = '';
  @State baseUrl: string = '';
  @State model: string = '';
  @State encoder: string = '';
  @State apiKey: string = '';
  @State actionError: string = '';
  @State statusLine: string = '';
  @State confirmDeleteId: string = '';
  @State confirmDeleteKey: boolean = false;

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ModelProviderView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private profiles(): ProviderProfileRecord[] {
    return this.snapshot.settings.providerProfiles;
  }

  private selectedId(): string {
    return this.snapshot.settings.selectedProviderId;
  }

  private editKeyConfigured(): boolean {
    if (this.editIsNew) {
      return false;
    }
    const profiles = this.profiles();
    for (let index = 0; index < profiles.length; index++) {
      if (profiles[index].id === this.editId) {
        return profiles[index].keyConfigured;
      }
    }
    return false;
  }

  private startNew(): void {
    this.editorOpen = true;
    this.editIsNew = true;
    this.editId = '';
    this.editCredentialRef = '';
    this.editEffort = 'high';
    this.editReasoningEffort = 'high';
    this.editModels = [];
    this.name = 'DeepSeek';
    this.baseUrl = 'https://api.deepseek.com/anthropic';
    this.model = 'deepseek-flash';
    this.encoder = 'deepseek';
    this.apiKey = '';
    this.actionError = '';
    this.statusLine = '';
    this.confirmDeleteKey = false;
  }

  private startEdit(profile: ProviderProfileRecord): void {
    this.editorOpen = true;
    this.editIsNew = false;
    this.editId = profile.id;
    this.editCredentialRef = profile.credentialRef;
    this.editEffort = profile.effort;
    this.editReasoningEffort = profile.reasoningEffort;
    this.editModels = [];
    for (let index = 0; index < profile.models.length; index++) {
      this.editModels.push(Copy.cloneProviderModel(profile.models[index]));
    }
    this.name = profile.name;
    this.baseUrl = profile.baseUrl;
    this.model = profile.model;
    this.encoder = profile.encoder;
    this.apiKey = '';
    this.actionError = '';
    this.statusLine = '';
    this.confirmDeleteKey = false;
  }

  private closeEditor(): void {
    this.editorOpen = false;
    this.apiKey = '';
    this.actionError = '';
    this.statusLine = '';
    this.confirmDeleteKey = false;
  }

  private providerFromForm(): ProviderProfileRecord {
    const profile = new ProviderProfileRecord();
    profile.id = this.editId;
    profile.name = this.name;
    profile.baseUrl = this.baseUrl;
    profile.model = this.model;
    profile.encoder = this.encoder;
    profile.effort = this.editEffort;
    profile.reasoningEffort = this.editReasoningEffort;
    profile.credentialRef = this.editCredentialRef;
    for (let index = 0; index < this.editModels.length; index++) {
      profile.models.push(Copy.cloneProviderModel(this.editModels[index]));
    }
    return profile;
  }

  private save(): void {
    if (this.name.trim().length === 0 || this.baseUrl.trim().length === 0 || this.model.trim().length === 0) {
      this.statusLine = Copy.tr('显示名称、服务 URL 和默认模型不能为空');
      return;
    }
    if (this.apiKey.trim().length > 0 && this.apiKey.trim().length < 8) {
      this.statusLine = Copy.tr('密钥需至少 8 个可打印 ASCII 字符，本次未保存');
      return;
    }
    this.statusLine = '';
    this.actionError = '';
    this.requireActions().saveProviderProfile(this.providerFromForm(), this.apiKey, this.editIsNew).then(() => {
      this.apiKey = '';
      this.editorOpen = false;
      this.statusLine = Copy.tr('已保存；地址与模型在下次启动本地引擎时生效；密钥用于同一服务的后续请求');
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private test(): void {
    this.statusLine = '';
    this.actionError = '';
    if (this.editIsNew) {
      this.statusLine = Copy.tr('先保存这个模型服务，再测试连接。');
      return;
    }
    this.requireActions().testProviderProfile(this.editId).then(() => {
      this.statusLine = Copy.tr('测试请求已完成，结果见上方提示。');
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private select(profile: ProviderProfileRecord): void {
    this.statusLine = '';
    this.actionError = '';
    this.requireActions().selectProviderProfile(profile.id).then(() => {
      this.statusLine = Copy.tr('已选择；下次启动本地引擎时生效。');
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private deleteProfile(): void {
    const id = this.confirmDeleteId;
    this.confirmDeleteId = '';
    if (id.length === 0) {
      return;
    }
    this.statusLine = '';
    this.actionError = '';
    this.requireActions().deleteProviderProfile(id).then(() => {
      this.statusLine = Copy.tr('已删除本机保存的模型服务与密钥。');
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private deleteKey(): void {
    this.confirmDeleteKey = false;
    this.statusLine = '';
    this.actionError = '';
    this.requireActions().deleteProviderKey().then(() => {
      this.statusLine = Copy.tr('已删除此模型服务的密钥。');
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  @Builder
  FieldLabel(label: string) {
    Text(label)
      .fontSize(Metrics.captionSize)
      .fontColor(this.pal().textSubtle)
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 4 })
  }

  @Builder
  ProfileRow(profile: ProviderProfileRecord) {
    Column() {
      Column() {
        Row() {
          Text(profile.name)
            .fontSize(Metrics.bodySize)
            .fontColor(this.pal().textPrimary)
            .layoutWeight(1)
          Text(profile.id === this.selectedId() ? Copy.tr('当前选择') : (profile.keyConfigured ? Copy.tr('已存密钥') : Copy.tr('无密钥')))
            .fontSize(Metrics.captionSize)
            .fontColor(profile.id === this.selectedId() ? this.pal().sessionAccent : this.pal().textSubtle)
        }
        .width('100%')
        Text(`${profile.model} · ${profile.baseUrl}`)
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .margin({ top: 3 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 16, right: 16, top: 10, bottom: 6 })
      Row() {
        Text(profile.id === this.selectedId() ? Copy.tr('已选择') : Copy.tr('选择'))
          .fontSize(13)
          .fontColor(profile.id === this.selectedId() ? this.pal().textTertiary : this.pal().sessionAccent)
          .height(30)
          .padding({ left: 12, right: 12 })
          .borderRadius(15)
          .backgroundColor(this.pal().sessionSelector)
          .id('provider.select.' + profile.id)
          .onClick(() => {
            if (profile.id !== this.selectedId()) {
              this.select(profile);
            }
          })
        Text(Copy.tr('编辑'))
          .fontSize(13)
          .fontColor(this.pal().textPrimary)
          .height(30)
          .padding({ left: 12, right: 12 })
          .borderRadius(15)
          .backgroundColor(this.pal().sessionSelector)
          .margin({ left: 8 })
          .id('provider.edit.' + profile.id)
          .onClick(() => {
            this.startEdit(profile);
          })
        Text(Copy.tr('删除'))
          .fontSize(13)
          .fontColor(this.pal().pageDanger)
          .height(30)
          .padding({ left: 12, right: 12 })
          .borderRadius(15)
          .backgroundColor(this.pal().sessionSelector)
          .margin({ left: 8 })
          .id('provider.delete.' + profile.id)
          .onClick(() => {
            this.confirmDeleteId = profile.id;
          })
      }
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 10 })
      if (this.confirmDeleteId === profile.id) {
        Column() {
          Text(Copy.tr('删除这个模型服务与它的密钥？'))
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.pal().textPrimary)
          Text(Copy.tr('只删除这个模型服务保存的密钥；其他模型服务的密钥不会被改动。运行中的模型服务需要先停止引擎。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSecondary)
            .lineHeight(17)
            .margin({ top: 4 })
          Row() {
            Text(Copy.tr('取消'))
              .fontSize(13)
              .fontColor(this.pal().textSecondary)
              .height(32)
              .padding({ left: 12, right: 12 })
              .borderRadius(16)
              .backgroundColor(this.pal().sessionSelector)
              .id('provider.delete.cancel')
              .onClick(() => {
                this.confirmDeleteId = '';
              })
            Text(Copy.tr('删除'))
              .fontSize(13)
              .fontColor('#FFFFFF')
              .height(32)
              .padding({ left: 14, right: 14 })
              .borderRadius(16)
              .backgroundColor(this.pal().pageDanger)
              .margin({ left: 8 })
              .id('provider.delete.confirm')
              .onClick(() => {
                this.deleteProfile();
              })
          }
          .width('100%')
          .justifyContent(FlexAlign.End)
          .margin({ top: 10 })
        }
        .width('100%')
        .padding(12)
        .backgroundColor(this.pal().page)
        .borderRadius(10)
      }
      Text('')
        .height(Metrics.hairline)
        .width('100%')
        .backgroundColor(this.pal().borderList)
    }
    .width('100%')
  }

  @Builder
  ListBody() {
    Scroll() {
      Column() {
        SectionLabel({ label: Copy.tr('模型服务'), dark: this.dark })
        Column() {
          if (this.profiles().length === 0) {
            Text(Copy.tr('还没有保存的模型服务。添加一个后，下次启动本地引擎时生效。'))
              .fontSize(Metrics.processSize)
              .fontColor(this.pal().textSecondary)
              .lineHeight(20)
              .padding(14)
          } else {
            ForEach(this.profiles(), (profile: ProviderProfileRecord, index: number) => {
              this.ProfileRow(profile)
            })
          }
        }
        .backgroundColor(this.pal().surface)
        .borderRadius(Metrics.settingsCardRadius)
        .margin({ left: 16, right: 16 })
        Row() {
          PillButton({
            label: Copy.tr('添加模型服务'), dark: this.dark, kind: 'secondary', compact: true,
            buttonId: 'provider.add',
            onTap: () => {
              this.startNew();
            }
          })
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 16 })
        if (this.statusLine.length > 0) {
          Text(this.statusLine)
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSecondary)
            .padding({ left: 24, right: 24, top: 10 })
        }
        SectionLabel({ label: Copy.tr('生效方式'), dark: this.dark })
        Column() {
          Text(this.snapshot.engine === EnginePhase.Ready
            ? Copy.tr('保存或选择后会写入本机配置；模型服务地址与模型从下一次启动本地引擎起装配。密钥按该服务的 AssetStore 别名在每次请求时读取：给同一别名保存新密钥后，后续请求会使用新密钥。')
            : Copy.tr('保存或选择后会写入本机配置；模型服务地址与模型会在下次启动主机时生效。密钥按各服务的 AssetStore 别名在每次请求时读取。'))
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textSecondary)
            .lineHeight(20)
            .padding(14)
        }
        .backgroundColor(this.pal().surface)
        .borderRadius(Metrics.settingsCardRadius)
        .margin({ left: 16, right: 16 })
        Text(Copy.tr('密钥只存在这台手机上；每个模型服务使用自己的 AssetStore 别名。配置、日志、网页里都没有它。'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .lineHeight(17)
          .padding({ left: 24, right: 24, top: 8 })
        Text('')
          .height(28)
      }
      .width('100%')
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
    .scrollBar(BarState.Off)
  }

  @Builder
  EditorBody() {
    Scroll() {
      Column() {
        SectionLabel({ label: Copy.tr('连接信息'), dark: this.dark })
        Column() {
          this.FieldLabel(Copy.tr('显示名称'))
          TextInput({ text: this.name, placeholder: Copy.tr('例如 DeepSeek') })
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textPrimary)
            .placeholderColor(this.pal().inputPlaceholder)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .margin({ left: 16, right: 16 })
            .id('provider.field.name')
            .onChange((value: string) => {
              this.name = value;
            })
          this.FieldLabel(Copy.tr('服务 URL'))
          TextInput({ text: this.baseUrl, placeholder: 'https://api.deepseek.com/anthropic' })
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textPrimary)
            .placeholderColor(this.pal().inputPlaceholder)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .margin({ left: 16, right: 16 })
            .id('provider.field.baseUrl')
            .onChange((value: string) => {
              this.baseUrl = value;
            })
          this.FieldLabel(Copy.tr('默认模型'))
          TextInput({ text: this.model, placeholder: 'deepseek-flash' })
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textPrimary)
            .placeholderColor(this.pal().inputPlaceholder)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .margin({ left: 16, right: 16 })
            .id('provider.field.model')
            .onChange((value: string) => {
              this.model = value;
            })
          this.FieldLabel(Copy.tr('编码'))
          TextInput({ text: this.encoder, placeholder: 'deepseek' })
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textPrimary)
            .placeholderColor(this.pal().inputPlaceholder)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .margin({ left: 16, right: 16 })
            .id('provider.field.encoder')
            .onChange((value: string) => {
              this.encoder = value;
            })
          this.FieldLabel(Copy.tr('推理强度'))
          TextInput({ text: this.editReasoningEffort, placeholder: 'high' })
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textPrimary)
            .placeholderColor(this.pal().inputPlaceholder)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .margin({ left: 16, right: 16, bottom: 14 })
            .id('provider.field.effort')
            .onChange((value: string) => {
              this.editReasoningEffort = value;
              this.editEffort = value;
            })
        }
        .backgroundColor(this.pal().surface)
        .borderRadius(Metrics.settingsCardRadius)
        .margin({ left: 16, right: 16 })
        Text(this.editIsNew
          ? Copy.tr('填写这几项后保存。保存会创建新的模型服务并选中它；地址与模型从下次启动本地引擎起装配；保存的密钥在该服务的后续请求中使用。')
          : Copy.tr('保存会更新这个模型服务；运行中的引擎仍使用启动时冻结的地址与模型。密钥按该服务别名在每次请求时读取，保存新密钥后后续请求会使用它。'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .lineHeight(17)
          .padding({ left: 24, right: 24, top: 8 })

        SectionLabel({ label: Copy.tr('密钥'), dark: this.dark })
        Column() {
          InfoRow({
            label: Copy.tr('密钥状态'),
            value: this.editKeyConfigured() ? '此设备已保存密钥' : '尚未保存密钥',
            dark: this.dark,
            showDivider: true
          })
          this.FieldLabel(Copy.tr('API 密钥'))
          TextInput({
            text: this.apiKey,
            placeholder: this.editKeyConfigured() ? Copy.tr('已保存，输入以替换') : Copy.tr('粘贴或输入 API 密钥')
          })
            .type(InputType.Password)
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textPrimary)
            .placeholderColor(this.pal().inputPlaceholder)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .margin({ left: 16, right: 16 })
            .id('provider.field.key')
            .onChange((value: string) => {
              this.apiKey = value;
            })
          Text(this.editKeyConfigured()
            ? '密钥已保存在这台设备上；界面只显示状态，不显示明文。留空保存不会改动原密钥。'
            : '还没有保存密钥；填入密钥后才能使用这个服务。')
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 16, right: 16, top: 6, bottom: 14 })
        }
        .backgroundColor(this.pal().surface)
        .borderRadius(Metrics.settingsCardRadius)
        .margin({ left: 16, right: 16 })

        Row() {
          PillButton({
            label: Copy.tr('保存'), dark: this.dark, buttonId: 'provider.save',
            onTap: () => {
              this.save();
            }
          })
          Row() {
            PillButton({
              label: Copy.tr('测试'), dark: this.dark, kind: 'secondary',
              disabled: this.editIsNew || !this.editKeyConfigured(),
              buttonId: 'provider.test',
              onTap: () => {
                this.test();
              }
            })
          }
          .margin({ left: 8 })
          if (!this.editIsNew && this.editId === this.selectedId() && this.editKeyConfigured()) {
            Row() {
              PillButton({
                label: Copy.tr('删除密钥'), dark: this.dark, kind: 'secondary',
                compact: true,
                buttonId: 'provider.deleteKey',
                onTap: () => {
                  this.confirmDeleteKey = true;
                }
              })
            }
            .margin({ left: 8 })
          }
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 16 })

        if (this.confirmDeleteKey) {
          Column() {
            Text(Copy.tr('删除已保存的密钥？'))
              .fontSize(16)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.pal().textPrimary)
            Text(Copy.tr('只删除这个模型服务的密钥；配置仍在，但在重新填入密钥前无法使用。'))
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSecondary)
              .lineHeight(19)
              .margin({ top: 6 })
            Row() {
              Text(Copy.tr('取消'))
                .fontSize(14)
                .fontColor(this.pal().textSecondary)
                .height(36)
                .padding({ left: 14, right: 14 })
                .borderRadius(18)
                .backgroundColor(this.pal().sessionSelector)
                .id('provider.deleteKey.cancel')
                .onClick(() => {
                  this.confirmDeleteKey = false;
                })
              Text(Copy.tr('删除'))
                .fontSize(14)
                .fontColor('#FFFFFF')
                .height(36)
                .padding({ left: 16, right: 16 })
                .borderRadius(18)
                .backgroundColor(this.pal().pageDanger)
                .margin({ left: 8 })
                .id('provider.deleteKey.confirm')
                .onClick(() => {
                  this.deleteKey();
                })
            }
            .width('100%')
            .justifyContent(FlexAlign.End)
            .margin({ top: 12 })
          }
          .width('100%')
          .padding(14)
          .backgroundColor(this.pal().surface)
          .borderRadius(14)
          .margin({ left: 16, right: 16, top: 12 })
        }

        if (this.statusLine.length > 0) {
          Text(this.statusLine)
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSecondary)
            .padding({ left: 24, right: 24, top: 10 })
        }

        SectionLabel({ label: Copy.tr('生效方式'), dark: this.dark })
        Column() {
          Text(this.snapshot.engine === EnginePhase.Ready
            ? Copy.tr('保存后会写入本机配置；服务地址与模型从下一次启动本地引擎起装配。密钥按这个服务的 AssetStore 别名在每次请求时读取：保存新密钥后，同一服务的后续请求会使用新密钥。')
            : Copy.tr('保存后会写入本机配置；服务地址与模型会在下次启动主机时生效。密钥按这个服务的 AssetStore 别名在每次请求时读取。'))
            .fontSize(Metrics.processSize)
            .fontColor(this.pal().textSecondary)
            .lineHeight(20)
            .padding(14)
          if (this.snapshot.engine !== EnginePhase.Ready) {
            Row() {
              PillButton({
                label: Copy.tr('启动本地引擎'), dark: this.dark, kind: 'secondary', compact: true,
                buttonId: 'provider.startEngine',
                onTap: () => {
                  this.requireActions().startEngine().catch((error: BusinessError) => {
                    this.actionError = error.message;
                  });
                }
              })
            }
            .padding({ left: 14, bottom: 14 })
          }
        }
        .backgroundColor(this.pal().surface)
        .borderRadius(Metrics.settingsCardRadius)
        .margin({ left: 16, right: 16 })
        Text(Copy.tr('密钥只存在这台手机上；配置、日志、网页里都没有它。'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .lineHeight(17)
          .padding({ left: 24, right: 24, top: 8 })
        Text('')
          .height(28)
      }
      .width('100%')
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
    .scrollBar(BarState.Off)
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('模型服务'),
        dark: this.dark,
        showBack: true,
        backId: 'provider.back',
        onBack: () => {
          if (this.editorOpen) {
            this.closeEditor();
          } else {
            this.requireActions().back();
          }
        }
      })
      if (this.actionError.length === 0 && (this.snapshot.failure.length > 0 || this.snapshot.notice.length > 0)) {
        NoticeBanner({
          text: this.snapshot.failure.length > 0 ? this.snapshot.failure : this.snapshot.notice,
          dark: this.dark,
          kind: this.snapshot.failure.length > 0 ? 'error' : 'info',
          showClose: true,
          bannerId: 'provider.host-result',
          onClose: () => { this.requireActions().clearNotice(); }
        })
      }
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({
            text: this.actionError, dark: this.dark, kind: 'error', showClose: true,
            bannerId: 'provider.error',
            onClose: () => {
              this.actionError = '';
            }
          })
        }
        .padding({ left: 16, right: 16 })
      }
      if (this.editorOpen) {
        this.EditorBody()
      } else {
        this.ListBody()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
export struct WebSearchView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('WebSearchView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private readonlyReason(): string {
    return Copy.tr('只读：独立搜索服务的密钥要由宿主在出网时注入 /search、/fetch 两条路径；')
      + '本宿主的出网代理只认模型服务的端点，没有搜索凭证路由，启动时也只放行一个精确 origin。'
      + '写这份配置引擎用不上，所以本页不提供保存。';
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('网页搜索'),
        dark: this.dark,
        showBack: true,
        backId: 'websearch.back',
        onBack: () => {
          this.requireActions().back();
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({ text: this.actionError, dark: this.dark, kind: 'error' })
        }
        .padding({ left: 16, right: 16 })
      }
      Scroll() {
        Column() {
          SectionLabel({ label: Copy.tr('网页搜索'), dark: this.dark })
          Column() {
            InfoRow({ label: Copy.tr('网页搜索'), value: Copy.unknown, dark: this.dark, showDivider: false })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          Row() {
            NoticeBanner({
              text: this.readonlyReason(),
              dark: this.dark,
              kind: 'warning',
              bannerId: 'websearch.readonly'
            })
          }
          .padding({ left: 16, right: 16, top: 8 })

          SectionLabel({ label: Copy.tr('用哪一份搜索'), dark: this.dark })
          Column() {
            InfoRow({ label: Copy.tr('搜索后端'), value: Copy.unknown, dark: this.dark, showDivider: true })
            InfoRow({ label: Copy.tr('地址'), value: Copy.unknown, dark: this.dark, showDivider: true })
            InfoRow({ label: Copy.tr('密钥状态'), value: Copy.unknown, dark: this.dark, showDivider: false })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16 })
          Text(Copy.tr('宿主没有报告搜索后端的读取结果，本页不假设任何地址或密钥状态；也不提供开关或保存。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8 })

          SectionLabel({ label: Copy.tr('什么时候生效'), dark: this.dark })
          Column() {
            Text(Copy.tr('存好或改动搜索设置后，需要重启本地引擎才生效；重启不会动文件夹和会话。'))
              .fontSize(Metrics.processSize)
              .fontColor(this.pal().textSecondary)
              .lineHeight(20)
              .padding(14)
            Row() {
              PillButton({
                label: this.snapshot.engine === EnginePhase.Ready ? Copy.tr('停止本地引擎') : Copy.tr('启动本地引擎'),
                dark: this.dark,
                kind: 'secondary',
                buttonId: 'websearch.engine',
                onTap: () => {
                  if (this.snapshot.engine === EnginePhase.Ready) {
                    this.requireActions().stopEngine().catch((error: BusinessError) => {
                      this.actionError = error.message;
                    });
                  } else {
                    this.requireActions().startEngine().catch((error: BusinessError) => {
                      this.actionError = error.message;
                    });
                  }
                }
              })
            }
            .padding({ left: 14, bottom: 14 })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('还要知道'), dark: this.dark })
          Text(Copy.tr('用模型服务自带的搜索时，抓网页这件事也归它管，这里不再单独设。密钥只存在这台手机上，本地引擎拿到的只是一个占位。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, bottom: 32 })
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
export struct McpView {
  @Prop @Watch('onSnapshotUpdated') snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  onSnapshotUpdated(): void {
    if (this.snapshot.notice.length > 0) {
      this.actionError = '';
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('McpView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private run(promise: Promise<void>): void {
    promise.catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private readonlyReason(): string {
    return Copy.tr('只读：MCP 注册表在 Worker 的 /dsh/home/mcp.json，改写要走宿主的 phone/mcpUpsert 工具；')
      + '本宿主没有注册该工具，所以不会在这里保存或删除服务器。';
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('MCP 服务器'),
        dark: this.dark,
        showBack: true,
        backId: 'mcp.back',
        onBack: () => {
          this.requireActions().back();
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({
            text: this.actionError, dark: this.dark, kind: 'error', showClose: true,
            bannerId: 'mcp.error',
            onClose: () => {
              this.actionError = '';
            }
          })
        }
        .padding({ left: 16, right: 16 })
      }
      Row() {
        NoticeBanner({
          text: this.readonlyReason(), dark: this.dark, kind: 'warning', bannerId: 'mcp.readonly'
        })
      }
      .padding({ left: 16, right: 16 })
      Scroll() {
        Column() {
          SectionLabel({ label: Copy.tr('服务器'), dark: this.dark })
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取 MCP 服务器…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('读取不到 MCP 服务器列表'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('读取不到 MCP 服务器列表，未做任何假设'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length === 0) {
            EmptyState({
              title: Copy.tr('还没有 MCP 服务器'),
              hint: Copy.tr('注册表由 Worker 的 /dsh/home/mcp.json 提供；本页只读读取'),
              glyph: 'NETWORK',
              dark: this.dark
            })
          } else {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                Column() {
                Row() {
                  Column() {
                    Row() {
                      Text(item.title.length > 0 ? item.title : item.id)
                        .fontSize(Metrics.bodySize)
                        .fontColor(this.pal().textPrimary)
                        .maxLines(1)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                      if (item.enabled) {
                        Text(Copy.tr('已连接'))
                          .fontSize(Metrics.captionSize)
                          .fontColor(this.pal().sessionSuccess)
                          .margin({ left: 6 })
                      }
                    }
                    .width('100%')
                    if (item.subtitle.length > 0) {
                      Text(item.subtitle)
                        .fontSize(Metrics.secondarySize)
                        .fontColor(this.pal().textSubtle)
                        .maxLines(1)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .margin({ top: 2 })
                    }
                    if (item.detail.length > 0) {
                      Text(item.detail)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .maxLines(3)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .margin({ top: 2 })
                    }
                  }
                  .layoutWeight(1)
                  .alignItems(HorizontalAlign.Start)
                  .id('mcp.item.' + item.id)
                  .onClick(() => {
                    this.requireActions().navigate(Page.McpEditor, item.id);
                  })
                  Text(Copy.tr('只读'))
                    .fontSize(Metrics.captionSize)
                    .fontColor(this.pal().textSubtle)
                }
                .width('100%')
                .constraintSize({ minHeight: Metrics.rowHeight })
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .alignItems(VerticalAlign.Center)
                if (index < this.snapshot.items.length - 1) {
                  Hairline({ dark: this.dark })
                }
                }.width('100%')
              }, (item: NamedItem) => JSON.stringify(item))
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16 })
          }

          Text(Copy.tr('列表来自 Worker 的 phone/mcpList；状态和工具数都是宿主报告的实时值，本页不改写注册表。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8 })

          SectionLabel({ label: Copy.tr('操作'), dark: this.dark })
          Column() {
            NavRow({
              label: Copy.tr('刷新注册表'), glyph: 'REFRESH', dark: this.dark, rowId: 'mcp.refresh',
              showDivider: true,
              onTap: () => {
                this.run(this.requireActions().refresh());
              }
            })
            NavRow({
              label: Copy.tr('MCP 服务器表单（只读）'), glyph: 'PLUS', dark: this.dark, rowId: 'mcp.addServer',
              showDivider: false,
              onTap: () => {
                this.requireActions().navigate(Page.McpEditor);
              }
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          Text(Copy.tr('本宿主没有 phone/mcpUpsert 与 phone/mcpRemove；保存或删除都不会落盘，也不会显示成功。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .padding({ left: 24, right: 24, top: 8 })
          Text('')
            .height(28)
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
export struct McpEditorView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State name: string = '';
  @State facts: string = '';
  @State editorId: string = '';
  @State actionError: string = '';

  aboutToAppear(): void {
    this.editorId = this.snapshot.detail;
    for (let i: number = 0; i < this.snapshot.items.length; i++) {
      const item: NamedItem = this.snapshot.items[i];
      if ((this.editorId.length > 0 && item.id === this.editorId)
        || (this.editorId.length === 0 && this.snapshot.items.length === 1)) {
        this.name = item.title;
        this.facts = item.detail;
        if (this.editorId.length === 0) {
          this.editorId = item.id;
        }
      }
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('McpEditorView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private readonlyReason(): string {
    return Copy.tr('保存不会落盘：本宿主没有 phone/mcpUpsert 这个 host tool。')
      + 'MCP 注册表在 Worker 的 /dsh/home/mcp.json；本页只读展示，不提供保存或删除。';
  }

  build() {
    Column() {
      NavHeader({
        title: this.editorId.length > 0 ? Copy.tr('MCP 服务器（只读）') : Copy.tr('添加 MCP 服务器（只读）'),
        dark: this.dark,
        showBack: true,
        backId: 'mcpEditor.back',
        onBack: () => {
          this.requireActions().back();
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({ text: this.actionError, dark: this.dark, kind: 'error' })
        }
        .padding({ left: 16, right: 16 })
      }
      Row() {
        NoticeBanner({
          text: this.readonlyReason(), dark: this.dark, kind: 'warning', bannerId: 'mcpEditor.readonly'
        })
      }
      .padding({ left: 16, right: 16 })
      Scroll() {
        Column() {
          SectionLabel({ label: Copy.tr('服务器'), dark: this.dark })
          Column() {
            Text(Copy.tr('名称'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 16, top: 12, bottom: 4 })
            TextInput({ text: this.name, placeholder: Copy.tr('无') })
              .fontSize(Metrics.processSize)
              .fontColor(this.pal().textPrimary)
              .placeholderColor(this.pal().inputPlaceholder)
              .height(44)
              .backgroundColor(this.pal().sessionSelector)
              .borderRadius(12)
              .margin({ left: 16, right: 16 })
              .enabled(false)
              .id('mcpEditor.name')
            Text(Copy.tr('宿主报告的注册表、地址与工具'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 16, top: 12, bottom: 4 })
            Text(this.facts.length > 0 ? this.facts : Copy.unknown)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textPrimary)
              .lineHeight(17)
              .padding({ left: 16, right: 16, bottom: 12 })
              .id('mcpEditor.facts')
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16 })

          Text(this.readonlyReason())
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8 })
          Text('')
            .height(32)
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
export struct RuntimeView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State confirmStop: boolean = false;

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('RuntimeView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private run(promise: Promise<void>): void {
    promise.catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('运行状态'),
        dark: this.dark,
        showBack: true,
        backId: 'runtime.back',
        onBack: () => {
          this.requireActions().back();
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({
            text: this.actionError, dark: this.dark, kind: 'error', showClose: true,
            bannerId: 'runtime.error',
            onClose: () => {
              this.actionError = '';
            }
          })
        }
        .padding({ left: 16, right: 16 })
      }
      Scroll() {
        Column() {
          SectionLabel({ label: Copy.tr('引擎'), dark: this.dark })
          Column() {
            InfoRow({
              label: Copy.tr('状态'), value: Copy.engineLabel(this.snapshot.engine),
              dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('地址'),
              value: this.snapshot.runtimeUrl.length > 0 ? this.snapshot.runtimeUrl : Copy.unknown,
              dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('说明'),
              value: this.snapshot.engineText.length > 0 ? this.snapshot.engineText : Copy.unknown,
              dark: this.dark, showDivider: false
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          Row() {
            PillButton({
              label: this.snapshot.engine === EnginePhase.Ready ? Copy.tr('停止本地引擎') : Copy.tr('启动本地引擎'),
              dark: this.dark,
              kind: this.snapshot.engine === EnginePhase.Ready ? 'secondary' : 'primary',
              buttonId: 'runtime.engine',
              onTap: () => {
                if (this.snapshot.engine === EnginePhase.Ready) {
                  this.confirmStop = true;
                } else {
                  this.run(this.requireActions().startEngine());
                }
              }
            })
            Row() {
              PillButton({
                label: Copy.tr('刷新'), dark: this.dark, kind: 'secondary', compact: true,
                buttonId: 'runtime.refresh',
                onTap: () => {
                  this.run(this.requireActions().refresh());
                }
              })
            }
            .margin({ left: 8 })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14 })

          if (this.confirmStop) {
            Column() {
              Text(Copy.tr('停止本地引擎？'))
                .fontSize(16)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.pal().textPrimary)
              Text(Copy.tr('正在跑的任务会停下来；会话和文件都还在。'))
                .fontSize(Metrics.secondarySize)
                .fontColor(this.pal().textSecondary)
                .lineHeight(19)
                .margin({ top: 6 })
              Row() {
                Text(Copy.tr('取消'))
                  .fontSize(14)
                  .fontColor(this.pal().textSecondary)
                  .height(36)
                  .padding({ left: 14, right: 14 })
                  .borderRadius(18)
                  .backgroundColor(this.pal().sessionSelector)
                  .id('runtime.stop.cancel')
                  .onClick(() => {
                    this.confirmStop = false;
                  })
                Text(Copy.tr('停止'))
                  .fontSize(14)
                  .fontColor('#FFFFFF')
                  .height(36)
                  .padding({ left: 16, right: 16 })
                  .borderRadius(18)
                  .backgroundColor(this.pal().pageDanger)
                  .margin({ left: 8 })
                  .id('runtime.stop.confirm')
                  .onClick(() => {
                    this.confirmStop = false;
                    this.run(this.requireActions().stopEngine());
                  })
              }
              .width('100%')
              .justifyContent(FlexAlign.End)
              .margin({ top: 12 })
            }
            .width('100%')
            .padding(14)
            .backgroundColor(this.pal().surface)
            .borderRadius(14)
            .margin({ left: 16, right: 16, top: 12 })
          }

          SectionLabel({ label: Copy.tr('运行时详情'), dark: this.dark })
          if (this.snapshot.runtimeDetail.length > 0) {
            Text(this.snapshot.runtimeDetail)
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSecondary)
              .lineHeight(20)
              .width('100%')
              .padding(14)
              .backgroundColor(this.pal().surface)
              .borderRadius(Metrics.settingsCardRadius)
              .margin({ left: 16, right: 16 })
          } else {
            Column() {
              EmptyState({
                title: Copy.tr('没有可显示的运行时信息'),
                hint: Copy.tr('宿主没有报告这些值；不会用 0 或默认档位填充。'),
                glyph: 'WAVEFORM',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          }

          SectionLabel({ label: Copy.tr('本次运行'), dark: this.dark })
          Column() {
            InfoRow({
              label: Copy.tr('模型调用'), value: Copy.unknown, dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('输入 Token'), value: Copy.unknown, dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('输出 Token'), value: Copy.unknown, dark: this.dark, showDivider: false
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('最近出错'), dark: this.dark })
          Text(Copy.tr('宿主没有报告最近一次出错；本页不推断成功。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, bottom: 32 })
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
export struct EgressView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('EgressView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('网络访问'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'egress.back',
        rightId: 'egress.refresh',
        onBack: () => {
          this.requireActions().back();
        },
        onRight: () => {
          this.requireActions().refresh().catch((error: BusinessError) => {
            this.actionError = error.message;
          });
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({ text: this.actionError, dark: this.dark, kind: 'error' })
        }
        .padding({ left: 16, right: 16 })
      }
      if (this.snapshot.itemsPhase === LoadPhase.Failed && this.snapshot.failure.length > 0) {
        Row() {
          NoticeBanner({ text: this.snapshot.failure, dark: this.dark, kind: 'error' })
        }
        .padding({ left: 16, right: 16 })
      }
      Scroll() {
        Column() {
          SectionLabel({ label: Copy.tr('出网策略（只读）'), dark: this.dark })
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取出网策略…'), dark: this.dark })
          } else {
            if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
              Text(Copy.tr('宿主没有报告活动路由；未知不等于拒绝。下面只显示宿主实际执行的固定规则。'))
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textSecondary)
                .lineHeight(17)
                .padding({ left: 24, right: 24, top: 2, bottom: 8 })
            }
            if (this.snapshot.items.length === 0) {
              EmptyState({
                title: Copy.tr('没有可显示的策略事实'),
                hint: Copy.tr('宿主没有报告出网规则；本页不推断放行或拒绝。'),
                glyph: 'NETWORK',
                dark: this.dark
              })
            } else {
              Column() {
                ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                  Column() {
                    Row() {
                      Text(item.title)
                        .fontSize(Metrics.bodySize)
                        .fontColor(this.pal().textPrimary)
                        .layoutWeight(1)
                      Text(item.subtitle)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .textAlign(TextAlign.End)
                        .maxLines(2)
                        .margin({ left: 12 })
                    }
                    .width('100%')
                    .constraintSize({ minHeight: Metrics.rowHeight })
                    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                    .alignItems(VerticalAlign.Center)
                    if (item.detail.length > 0) {
                      Text(item.detail)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .lineHeight(17)
                        .width('100%')
                        .padding({ left: 16, right: 16, bottom: 10 })
                    }
                    if (index < this.snapshot.items.length - 1) {
                      Hairline({ dark: this.dark })
                    }
                  }
                  .width('100%')
                }, (item: NamedItem) => JSON.stringify(item))
              }
              .backgroundColor(this.pal().surface)
              .borderRadius(Metrics.settingsCardRadius)
              .clip(true)
              .margin({ left: 16, right: 16 })
            }
            Text(Copy.tr('本页只读：显示宿主实际执行的规则；不提供白名单编辑或出网授权。'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .lineHeight(17)
              .padding({ left: 24, right: 24, top: 12, bottom: 32 })
          }
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}
