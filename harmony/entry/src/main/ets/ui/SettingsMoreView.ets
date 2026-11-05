// SPDX-License-Identifier: MIT
// Secondary settings pages: appearance/font, redacted config, shortcuts, archive,
// about/licenses, phone permissions and the two inventory pages.

import {
  ShellSnapshot, ShellActions, Page, Settings, SessionRecord, NamedItem, LoadPhase,
  FontRole, FontSizes,
  fontSizeValue, setFontSize
} from '../model/Contracts';
import { Palette, Metrics, Copy } from './Palette';
import {
  NavHeader, SectionLabel, NavRow, InfoRow, SwitchRow, PillButton, EmptyState,
  LoadingBlock, NoticeBanner, Hairline
} from './ShellSupport';
import { NativeIcon } from './NativeIcon';
import { BusinessError } from '@kit.BasicServicesKit';

@Component
export struct ChatPreferencesView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ChatPreferencesView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private save(next: Settings): void {
    this.requireActions().saveSettings(next).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private applyChoice(kind: string, value: string): void {
    const next: Settings = Copy.cloneSettings(this.snapshot.settings);
    if (kind === 'appearance') {
      next.theme = value;
    } else if (kind === 'language') {
      next.language = value;
    }
    this.save(next);
  }

  private stepFont(delta: number): void {
    const next: Settings = Copy.cloneSettings(this.snapshot.settings);
    let size: number = next.fontSize + delta;
    if (size < 12) { size = 12; }
    if (size > 24) { size = 24; }
    next.fontSize = size;
    this.save(next);
  }

  @Builder
  ChoiceRow(label: string, value: string, selected: boolean, kind: string) {
    Row() {
      Text(label)
        .fontSize(Metrics.bodySize)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
      if (selected) {
        NativeIcon({ name: '已选', symbol: 'CHECK', dark: this.dark, iconSize: 16 })
          .width(16)
          .height(16)
      }
    }
    .width('100%')
    .constraintSize({ minHeight: Metrics.rowHeight })
    .padding({ left: 16, right: 16 })
    .id('chatPreferences.' + kind + '.' + value)
    .onClick(() => {
      this.applyChoice(kind, value);
    })
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('聊天外观'),
        dark: this.dark,
        showBack: true,
        backId: 'chatPreferences.back',
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
          SectionLabel({ label: Copy.tr('外观'), dark: this.dark })
          Column() {
            this.ChoiceRow(Copy.tr('跟随系统'), 'system', this.snapshot.settings.theme === 'system', 'appearance')
            this.ChoiceRow(Copy.tr('浅色'), 'light', this.snapshot.settings.theme === 'light', 'appearance')
            this.ChoiceRow(Copy.tr('深色'), 'dark', this.snapshot.settings.theme === 'dark', 'appearance')
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })
          Text(Copy.tr('深色仍在适配中；默认浅色与官方 402pt 基线一致。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .padding({ left: 24, right: 24, top: 8 })

          SectionLabel({ label: Copy.tr('语言'), dark: this.dark })
          Column() {
            this.ChoiceRow(Copy.tr('跟随系统'), 'system', this.snapshot.settings.language === 'system', 'language')
            this.ChoiceRow(Copy.tr('中文'), 'zh', this.snapshot.settings.language === 'zh', 'language')
            this.ChoiceRow('English', 'en', this.snapshot.settings.language === 'en', 'language')
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('字号'), dark: this.dark })
          Column() {
            Row() {
              Column() {
                Text(Copy.tr('正文'))
                  .fontSize(Metrics.bodySize)
                  .fontColor(this.pal().textPrimary)
                Text(Copy.fill('当前 {0} pt', [this.snapshot.settings.fontSize.toString()]))
                  .fontSize(Metrics.captionSize)
                  .fontColor(this.pal().textSubtle)
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
              Text('−')
                .fontSize(20)
                .fontColor(this.pal().textPrimary)
                .width(40)
                .height(40)
                .textAlign(TextAlign.Center)
                .borderRadius(20)
                .backgroundColor(this.pal().sessionSelector)
                .accessibilityText(Copy.tr('减小'))
                .id('chatPreferences.font.decrease')
                .onClick(() => {
                  this.stepFont(-1);
                })
              NativeIcon({ name: '增大', symbol: 'PLUS', dark: this.dark, iconSize: 20 })
                .width(40)
                .height(40)
                .borderRadius(20)
                .backgroundColor(this.pal().sessionSelector)
                .margin({ left: 8 })
                .accessibilityText(Copy.tr('增大'))
                .id('chatPreferences.font.increase')
                .onClick(() => {
                  this.stepFont(1);
                })
            }
            .width('100%')
            .height(64)
            .padding({ left: 16, right: 16 })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16 })
          Text(Copy.tr('正文字号作用于消息正文；代码字号由宿主配置决定。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .padding({ left: 24, right: 24, top: 8 })

          SectionLabel({ label: Copy.tr('过程折叠'), dark: this.dark })
          Column() {
            SwitchRow({
              label: Copy.tr('性能与用量'),
              detail: Copy.tr('详细显示轮次、步骤、速度、缓存与上下文；关闭后只显示速度与缓存'),
              checked: this.snapshot.settings.performanceUsage !== 'compact',
              dark: this.dark,
              rowId: 'chatPreferences.performanceUsage',
              showDivider: true,
              onChange: (value: boolean) => {
                const next: Settings = Copy.cloneSettings(this.snapshot.settings);
                next.performanceUsage = value ? 'detailed' : 'compact';
                this.save(next);
              }
            })
            SwitchRow({
              label: Copy.tr('显示工作过程'),
              detail: Copy.tr('思考与工具行在对话里折叠显示'),
              checked: this.snapshot.settings.showProcess,
              dark: this.dark,
              rowId: 'chatPreferences.showProcess',
              showDivider: true,
              onChange: (value: boolean) => {
                const next: Settings = Copy.cloneSettings(this.snapshot.settings);
                next.showProcess = value;
                this.save(next);
              }
            })
            InfoRow({
              label: Copy.tr('折叠时机'), value: Copy.unknown, dark: this.dark, showDivider: false
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })
          Text(Copy.tr('折叠时机由宿主管理；本页不显示伪造的绑定。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .padding({ left: 24, right: 24, top: 8, bottom: 32 })
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
export struct FontSizeView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State textSize: number = FontSizes.textDefault;
  @State codeSize: number = FontSizes.codeDefault;
  @State terminalSize: number = FontSizes.terminalDefault;
  @State actionError: string = '';
  @State saved: boolean = false;

  aboutToAppear(): void {
    this.textSize = fontSizeValue(this.snapshot.settings, FontRole.Text);
    this.codeSize = fontSizeValue(this.snapshot.settings, FontRole.Code);
    this.terminalSize = fontSizeValue(this.snapshot.settings, FontRole.Terminal);
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('FontSizeView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private sizeFor(role: FontRole): number {
    if (role === FontRole.Code) { return this.codeSize; }
    if (role === FontRole.Terminal) { return this.terminalSize; }
    return this.textSize;
  }

  private change(role: FontRole, delta: number): void {
    const next: number = FontSizes.clamp(role, this.sizeFor(role) + delta);
    if (role === FontRole.Code) {
      this.codeSize = next;
    } else if (role === FontRole.Terminal) {
      this.terminalSize = next;
    } else {
      this.textSize = next;
    }
    this.saved = false;
  }

  private reset(): void {
    this.textSize = FontSizes.textDefault;
    this.codeSize = FontSizes.codeDefault;
    this.terminalSize = FontSizes.terminalDefault;
    this.saved = false;
  }

  private save(): void {
    const next: Settings = Copy.cloneSettings(this.snapshot.settings);
    setFontSize(next, FontRole.Text, this.textSize);
    setFontSize(next, FontRole.Code, this.codeSize);
    setFontSize(next, FontRole.Terminal, this.terminalSize);
    this.requireActions().saveSettings(next).then(() => {
      this.saved = true;
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  @Builder
  RoleStepper(role: FontRole, label: string, size: number, minimum: number, maximum: number) {
    Row() {
      Column() {
        Text(label)
          .fontSize(Metrics.bodySize)
          .fontColor(this.pal().textPrimary)
        Text(Copy.fill('{0} pt', [size.toString()]))
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().textSecondary)
          .margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      Text('−')
        .fontSize(22)
        .fontColor(size > minimum ? this.pal().textPrimary : this.pal().textTertiary)
        .width(44)
        .height(44)
        .textAlign(TextAlign.Center)
        .borderRadius(22)
        .backgroundColor(this.pal().sessionSelector)
        .opacity(size > minimum ? 1 : 0.45)
        .accessibilityText(Copy.tr('减小字号'))
        .id('preference.fontSize.' + role.toString() + '.decrease')
        .onClick(() => {
          this.change(role, -1);
        })
      Text(size.toString() + ' pt')
        .fontSize(Metrics.processSize)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.pal().textPrimary)
        .width(58)
        .textAlign(TextAlign.Center)
      NativeIcon({ name: '增大字号', symbol: 'PLUS', dark: this.dark, iconSize: 20 })
        .width(44)
        .height(44)
        .borderRadius(22)
        .backgroundColor(this.pal().sessionSelector)
        .opacity(size < maximum ? 1 : 0.45)
        .accessibilityText(Copy.tr('增大字号'))
        .id('preference.fontSize.' + role.toString() + '.increase')
        .onClick(() => {
          this.change(role, 1);
        })
    }
    .width('100%')
    .constraintSize({ minHeight: 64 })
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .alignItems(VerticalAlign.Center)
    .id('preference.fontSize.' + role.toString())
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('字号'),
        dark: this.dark,
        showBack: true,
        backId: 'fontSize.back',
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
          SectionLabel({ label: Copy.tr('会话内容'), dark: this.dark })
          Column() {
            this.RoleStepper(FontRole.Text, Copy.tr('会话内容'), this.textSize,
              FontSizes.textMin, FontSizes.textMax)
            Hairline({ dark: this.dark })
            this.RoleStepper(FontRole.Code, Copy.tr('代码'), this.codeSize,
              FontSizes.codeMin, FontSizes.codeMax)
            Hairline({ dark: this.dark })
            this.RoleStepper(FontRole.Terminal, Copy.tr('终端'), this.terminalSize,
              FontSizes.terminalMin, FontSizes.terminalMax)
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('预览'), dark: this.dark })
          Column() {
            Text(Copy.tr('Phone DSH 在这台手机上原生显示。'))
              .fontSize(this.textSize)
              .fontColor(this.pal().textPrimary)
              .lineHeight(this.textSize + 8)
              .width('100%')
            Text(Copy.tr('正文字号会作用于消息正文；工具详情使用更小的字号。'))
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSecondary)
              .lineHeight(20)
              .width('100%')
              .margin({ top: 8 })
            Text('const answer = 42;')
              .fontSize(this.codeSize)
              .fontFamily('monospace')
              .fontColor(this.pal().textPrimary)
              .width('100%')
              .padding(10)
              .backgroundColor(this.pal().sessionCode)
              .borderRadius(8)
              .margin({ top: 10 })
            Text(Copy.tr('终端字号会作用于终端与命令输出。'))
              .fontSize(this.terminalSize)
              .fontFamily('monospace')
              .fontColor(this.pal().textSecondary)
              .width('100%')
              .padding(10)
              .backgroundColor(this.pal().sessionInlineCode)
              .borderRadius(8)
              .margin({ top: 8 })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .padding(16)
          .margin({ left: 16, right: 16 })

          Row() {
            PillButton({
              label: Copy.tr('保存'), dark: this.dark, buttonId: 'fontSize.save',
              onTap: () => {
                this.save();
              }
            })
            PillButton({
              label: Copy.tr('重置'), dark: this.dark, kind: 'secondary',
              buttonId: 'fontSize.reset',
              onTap: () => {
                this.reset();
              }
            })
            if (this.saved) {
              Text(Copy.tr('保存请求已提交'))
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textSecondary)
                .margin({ left: 12 })
            }
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 16, bottom: 32 })
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
export struct SettingsDocumentView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('SettingsDocumentView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('配置文件'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'settingsDocument.back',
        rightId: 'settingsDocument.refresh',
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
      Scroll() {
        Column() {
          if (this.snapshot.detail.length > 0) {
            Text(this.snapshot.detail)
              .fontSize(13)
              .fontColor(this.pal().textPrimary)
              .lineHeight(20)
              .width('100%')
              .padding(14)
              .backgroundColor(this.pal().sessionCode)
              .borderRadius(14)
              .margin({ left: 16, right: 16, top: 16 })
          } else {
            Column() {
              EmptyState({
                title: Copy.tr('DSH 没有可显示的自定义设置'),
                hint: Copy.tr('宿主尚未提供设置描述，这里不显示编造的内容。'),
                glyph: 'DOCUMENT',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16, top: 16 })
          }
          Text(Copy.tr('仅显示 DSH 已脱敏的设置描述与 revision；密钥和本机路径不会出现在这里。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8 })
          Text(Copy.tr('只读：内容来自宿主的脱敏快照，不能在这里编辑。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
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
export struct ShortcutsView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ShortcutsView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  @Builder
  FixedRow(label: string, keys: string) {
    Row() {
      Text(label)
        .fontSize(Metrics.bodySize)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
      Text(keys)
        .fontSize(Metrics.secondarySize)
        .fontColor(this.pal().textSecondary)
        .padding({ left: 8, right: 8, top: 3, bottom: 3 })
        .borderRadius(8)
        .backgroundColor(this.pal().sessionSelector)
    }
    .width('100%')
    .height(Metrics.rowHeight)
    .padding({ left: 16, right: 16 })
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('快捷键'),
        dark: this.dark,
        showBack: true,
        backId: 'shortcuts.back',
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
          SectionLabel({ label: Copy.tr('应用命令'), dark: this.dark })
          if (this.snapshot.items.length > 0) {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                Column() {
                Row() {
                  Column() {
                    Text(item.title.length > 0 ? item.title : item.id)
                      .fontSize(Metrics.bodySize)
                      .fontColor(this.pal().textPrimary)
                    if (item.subtitle.length > 0) {
                      Text(item.subtitle)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .margin({ top: 2 })
                    }
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  Text(item.detail.length > 0 ? item.detail : Copy.unknown)
                    .fontSize(Metrics.secondarySize)
                    .fontColor(this.pal().textSubtle)
                }
                .width('100%')
                .constraintSize({ minHeight: 52 })
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
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
          } else {
            Text(Copy.tr('宿主没有报告快捷键存储，这里不显示默认绑定。'))
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSubtle)
              .lineHeight(19)
              .padding({ left: 24, right: 24 })
          }

          SectionLabel({ label: Copy.tr('固定输入操作（只读）'), dark: this.dark })
          Column() {
            this.FixedRow(Copy.tr('换行'), '⇧↩')
            this.FixedRow(Copy.tr('发送'), '↩')
            this.FixedRow(Copy.tr('另一种发送行为'), '⌘↩')
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })
          Text(Copy.tr('快捷键由宿主读写；本页只显示当前绑定，编辑入口尚未接入。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8, bottom: 32 })
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
export struct ArchivedSessionsView {
  @Prop @Watch('onSnapshotUpdated') snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State confirmDeleteId: string = '';

  onSnapshotUpdated(): void {
    if (this.snapshot.notice.length > 0) {
      this.actionError = '';
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ArchivedSessionsView requires ShellActions');
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

  private archived(): SessionRecord[] {
    const rows: SessionRecord[] = [];
    for (let i: number = 0; i < this.snapshot.sessions.length; i++) {
      if (this.snapshot.sessions[i].archived) {
        rows.push(this.snapshot.sessions[i]);
      }
    }
    return rows;
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('已归档会话'),
        dark: this.dark,
        showBack: true,
        backId: 'archived.back',
        onBack: () => {
          this.requireActions().back();
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({
            text: this.actionError, dark: this.dark, kind: 'error', showClose: true,
            bannerId: 'archived.error',
            onClose: () => {
              this.actionError = '';
            }
          })
        }
        .padding({ left: 16, right: 16 })
      }
      Scroll() {
        Column() {
          if (this.snapshot.sessionsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取已归档会话…'), dark: this.dark })
          } else if (this.snapshot.sessionsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('会话列表加载失败'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.sessionsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主没有报告已归档的会话，无法判断有没有'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.archived().length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('没有已归档的会话'),
                hint: Copy.tr('在侧边栏长按会话选「归档」，会话会收到这里，随时可以恢复。'),
                glyph: 'ARCHIVE',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16, top: 16 })
          } else {
            Column() {
              ForEach(this.archived(), (session: SessionRecord, index: number) => {
                Column() {
                  Row() {
                    Column() {
                      Text(session.title.length > 0 && session.title !== '新会话' ?
                        session.title : Copy.tr('新会话'))
                        .fontSize(Metrics.bodySize)
                        .fontColor(this.pal().textPrimary)
                        .maxLines(1)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                      Text(Copy.relativeTime(session.updatedAt).length > 0
                        ? Copy.relativeTime(session.updatedAt) : Copy.unknown)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .margin({ top: 3 })
                    }
                    .alignItems(HorizontalAlign.Start)
                    .layoutWeight(1)
                    Row() {
                      Text(Copy.tr('恢复'))
                        .fontSize(13)
                        .fontColor(this.pal().textPrimary)
                        .height(32)
                        .padding({ left: 12, right: 12 })
                        .borderRadius(16)
                        .backgroundColor(this.pal().sessionSelector)
                        .id('archived.restore.' + session.id)
                        .onClick(() => {
                          this.run(this.requireActions().archiveSession(session.id, false));
                        })
                      Text(Copy.tr('删除'))
                        .fontSize(13)
                        .fontColor(this.pal().pageDanger)
                        .height(32)
                        .padding({ left: 12, right: 12 })
                        .borderRadius(16)
                        .backgroundColor(this.pal().sessionSelector)
                        .margin({ left: 6 })
                        .id('archived.delete.' + session.id)
                        .onClick(() => {
                          this.confirmDeleteId = session.id;
                        })
                    }
                  }
                  .width('100%')
                  .constraintSize({ minHeight: 62 })
                  .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                  if (this.confirmDeleteId === session.id) {
                    Row() {
                      Text(Copy.tr('删除这个会话？删除后无法恢复'))
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().pageDanger)
                        .layoutWeight(1)
                      Text(Copy.tr('取消'))
                        .fontSize(13)
                        .fontColor(this.pal().textSecondary)
                        .height(32)
                        .padding({ left: 10, right: 10 })
                        .id('archived.delete.cancel')
                        .onClick(() => {
                          this.confirmDeleteId = '';
                        })
                      Text(Copy.tr('删除'))
                        .fontSize(13)
                        .fontColor(this.pal().pageDanger)
                        .height(32)
                        .padding({ left: 10, right: 10 })
                        .id('archived.delete.confirm')
                        .onClick(() => {
                          this.confirmDeleteId = '';
                          this.run(this.requireActions().deleteSession(session.id));
                        })
                    }
                    .width('100%')
                    .padding({ left: 16, right: 16, bottom: 8 })
                  }
                  if (index < this.archived().length - 1) {
                    Hairline({ dark: this.dark })
                  }
                }
                .width('100%')
              }, (session: SessionRecord) => JSON.stringify([session.id, session.title, session.folderId, session.pinned, session.archived]))
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16, top: 16 })
          }
          Text(Copy.tr('恢复后，会话回到原来的文件夹。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .padding({ left: 24, right: 24, top: 8, bottom: 32 })
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
export struct AboutView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('AboutView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('关于'),
        dark: this.dark,
        showBack: true,
        backId: 'about.back',
        onBack: () => {
          this.requireActions().back();
        }
      })
      Scroll() {
        Column() {
          Column() {
            Text('Phone DSH')
              .fontSize(Metrics.largeTitleSize)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.pal().textPrimary)
            Text(Copy.tr('运行在手机端的 DeepSeek Harness'))
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSecondary)
              .margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 24, bottom: 20, left: 24, right: 24 })
          .alignItems(HorizontalAlign.Start)

          SectionLabel({ label: Copy.tr('版本'), dark: this.dark })
          Column() {
            InfoRow({ label: Copy.tr('版本号'), value: Copy.unknown, dark: this.dark, showDivider: true })
            InfoRow({ label: Copy.tr('构建号'), value: Copy.unknown, dark: this.dark, showDivider: false })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('系统'), dark: this.dark })
          Column() {
            InfoRow({
              label: Copy.tr('引擎'), value: Copy.engineLabel(this.snapshot.engine),
              dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('运行时地址'),
              value: this.snapshot.runtimeUrl.length > 0 ? this.snapshot.runtimeUrl : Copy.unknown,
              dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('主题'), value: this.snapshot.dark ? Copy.tr('深色') : Copy.tr('浅色'),
              dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('语言'), value: Copy.languageLabel(this.snapshot.settings.language),
              dark: this.dark, showDivider: false
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('更多'), dark: this.dark })
          Column() {
            NavRow({
              label: Copy.tr('开源许可'), glyph: 'DOCUMENT', dark: this.dark, rowId: 'about.licenses',
              showDivider: true,
              onTap: () => {
                this.requireActions().navigate(Page.Licenses);
              }
            })
            NavRow({
              label: 'Probes', glyph: 'WAVEFORM', dark: this.dark, rowId: 'about.probes',
              showDivider: false,
              onTap: () => {
                this.requireActions().navigate(Page.Probes);
              }
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          Text(Copy.tr('版本号与构建号由宿主报告；当前宿主没有提供这两项，本页显示未知而不是编造值。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8 })
          Text(Copy.tr('会话记录和文件都保存在这台手机上。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .padding({ left: 24, right: 24, top: 4, bottom: 32 })
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
export struct LicensesView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State openId: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('LicensesView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private dependencies(): NamedItem[] {
    const rows: NamedItem[] = [];
    for (let index: number = 1; index < this.snapshot.items.length; index++) {
      rows.push(this.snapshot.items[index]);
    }
    return rows;
  }

  @Builder
  LicenseEntry(item: NamedItem, last: boolean) {
    Column() {
      Row() {
        Column() {
          Text(item.title.length > 0 ? item.title : item.id)
            .fontSize(Metrics.bodySize)
            .fontColor(this.pal().textPrimary)
          if (item.subtitle.length > 0) {
            Text(item.subtitle)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .margin({ top: 2 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Text(item.detail.length > 0
          ? (this.openId === item.id ? Copy.tr('收起') : Copy.tr('展开'))
          : Copy.tr('文本读取失败'))
          .fontSize(Metrics.secondarySize)
          .fontColor(item.detail.length > 0 ? this.pal().sessionAccent : this.pal().pageDanger)
      }
      .width('100%')
      .constraintSize({ minHeight: 54 })
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .id('licenses.item.' + item.id)
      .onClick(() => {
        if (item.detail.length === 0) {
          return;
        }
        this.openId = this.openId === item.id ? '' : item.id;
      })
      if (this.openId === item.id && item.detail.length > 0) {
        Column() {
          Text(item.detail)
            .fontSize(12)
            .lineHeight(18)
            .fontFamily('monospace')
            .fontColor(this.pal().textPrimary)
            .width('100%')
            .copyOption(CopyOptions.LocalDevice)
            .id('licenses.body.' + item.id)
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 10, bottom: 14 })
        .backgroundColor(this.pal().sessionCode)
      }
      if (!last) {
        Hairline({ dark: this.dark })
      }
    }
    .width('100%')
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('开源许可'),
        dark: this.dark,
        showBack: true,
        backId: 'licenses.back',
        onBack: () => {
          this.requireActions().back();
        }
      })
      Scroll() {
        Column() {
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取许可文本…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({
                text: this.snapshot.failure.length > 0
                  ? this.snapshot.failure : Copy.tr('许可文本读取失败'),
                dark: this.dark,
                kind: 'error'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主没有报告许可文本，无法判断有没有第三方依赖。'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          }
          if (this.snapshot.itemsPhase !== LoadPhase.Loading &&
            this.snapshot.items.length > 0) {
            SectionLabel({ label: Copy.tr('本应用'), dark: this.dark })
            Column() {
              this.LicenseEntry(this.snapshot.items[0], true)
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16 })
            if (this.dependencies().length > 0) {
              SectionLabel({ label: Copy.tr('第三方依赖'), dark: this.dark })
              Column() {
                ForEach(this.dependencies(), (item: NamedItem, index: number) => {
                  this.LicenseEntry(item, index === this.dependencies().length - 1)
                }, (item: NamedItem) => JSON.stringify(item))
              }
              .backgroundColor(this.pal().surface)
              .borderRadius(Metrics.settingsCardRadius)
              .clip(true)
              .margin({ left: 16, right: 16 })
            }
          } else if (this.snapshot.itemsPhase === LoadPhase.Ready) {
            Column() {
              EmptyState({
                title: Copy.tr('没有可显示的许可文本'),
                hint: Copy.tr('宿主报告了空清单；这里不会编造第三方依赖。'),
                glyph: 'DOCUMENT',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          }
          Text(Copy.tr('完整许可文本随 HAP 以 rawfile 资源分发；点击条目展开，长按复制。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8, bottom: 32 })
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
export struct PhonePermissionsView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('PhonePermissionsView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private isGroup(item: NamedItem): boolean {
    return item.id.startsWith('permission-group:');
  }

  private canRequest(item: NamedItem): boolean {
    // Only a declared permission the OS reports as not granted could ever have
    // a request path. This host sends no permission request, so the control is
    // rendered disabled rather than faking a dialog that can never appear.
    return item.subtitle === Copy.tr('未授予');
  }

  private stateColor(item: NamedItem): string {
    if (item.subtitle === Copy.tr('已允许') || item.subtitle === Copy.tr('已开启') ||
      item.subtitle === Copy.tr('常开')) {
      return this.pal().success;
    }
    if (item.subtitle === Copy.tr('未授予') || item.subtitle === Copy.tr('已关闭')) {
      return this.pal().pageWarning;
    }
    if (item.subtitle === Copy.tr('读取失败')) {
      return this.pal().pageDanger;
    }
    return this.pal().textSubtle;
  }

  @Builder
  PermissionRow(item: NamedItem) {
    Row() {
      NativeIcon({ name: '', symbol: 'LOCK_SHIELD', dark: this.dark, iconSize: 18, muted: true })
        .width(18)
        .height(18)
        .margin({ top: 2 })
      Column() {
        Text(item.title)
          .fontSize(Metrics.bodySize)
          .fontColor(this.pal().textPrimary)
        Text(item.detail)
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .lineHeight(16)
          .margin({ top: 3 })
        if (this.canRequest(item)) {
          Button(Copy.tr('请求'))
            .enabled(false)
            .fontSize(Metrics.captionSize)
            .height(28)
            .margin({ top: 6 })
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      .margin({ left: 10 })
      Text(item.subtitle)
        .fontSize(Metrics.secondarySize)
        .fontColor(this.stateColor(item))
        .margin({ left: 10, top: 1 })
    }
    .width('100%')
    .alignItems(VerticalAlign.Top)
    .padding({ left: 16, right: 16, top: 10, bottom: 10 })
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('手机权限'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'phonePermissions.back',
        rightId: 'phonePermissions.refresh',
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
      Scroll() {
        Column() {
          SectionLabel({ label: Copy.tr('权限状态'), dark: this.dark })
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取设备授权状态…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Column() {
              NoticeBanner({
                text: this.snapshot.failure.length > 0 ? this.snapshot.failure : Copy.tr('读取手机权限状态失败'),
                dark: this.dark,
                kind: 'error'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length > 0) {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                if (this.isGroup(item)) {
                  Text(item.title)
                    .fontSize(Metrics.captionSize)
                    .fontColor(this.pal().textSubtle)
                    .width('100%')
                    .padding({ left: 16, right: 16, top: 12, bottom: 4 })
                } else {
                  this.PermissionRow(item)
                  if (index < this.snapshot.items.length - 1 &&
                    !this.isGroup(this.snapshot.items[index + 1])) {
                    Text('')
                      .height(Metrics.hairline)
                      .width('100%')
                      .backgroundColor(this.pal().borderList)
                  }
                }
              }, (item: NamedItem) => JSON.stringify(item))
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              Text(Copy.tr('宿主没有报告手机权限状态'))
                .fontSize(Metrics.bodySize)
                .fontColor(this.pal().textPrimary)
              Text(Copy.tr('本页没有读取到任何授权状态，因此不把未读显示成“已允许”或“已拒绝”。'))
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textSubtle)
                .margin({ top: 6 })
            }
            .alignItems(HorizontalAlign.Start)
            .padding(16)
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          }
          Text(Copy.tr('状态来自本机 bundleManager / abilityAccessCtrl / notificationManager 的实际读取；未在 module.json5 声明的权限只显示“未知”和原因。本页不请求权限、不显示能力开关，也不把 PhotoPicker 的按 URI 授权当成整库照片授权。撤销或修改系统授权请前往系统设置。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 10 })

          SectionLabel({ label: Copy.tr('最近使用审计'), dark: this.dark })
          Column() {
            EmptyState({
              title: Copy.tr('宿主没有报告使用审计'),
              hint: Copy.tr('本宿主没有能力使用审计，不能判断有没有记录；不把未报告显示成“暂无记录”。'),
              glyph: 'HAND',
              dark: this.dark
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16 })
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
export struct AgentPresetsView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('AgentPresetsView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('Agent 预设'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'agentPresets.back',
        rightId: 'agentPresets.refresh',
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
      Scroll() {
        Column() {
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取预设…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('预设读取失败'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主还没有返回预设清单，暂不判断有没有预设。'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('当前引擎没有预设'),
                hint: Copy.tr('预设由引擎提供；本页不会编造预设或默认项。'),
                glyph: 'PEOPLE',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                PresetRow({
                  item: item,
                  dark: this.dark,
                  index: index,
                  last: index === this.snapshot.items.length - 1
                })
              }, (item: NamedItem) => JSON.stringify(item))
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16 })
          }
          Text(Copy.tr('预设清单由宿主报告；启用状态和配置只展示已收到的字段。使用预设创建会话需要宿主提供入口。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8, bottom: 32 })
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
struct PresetRow {
  @Prop item: NamedItem = new NamedItem();
  @Prop dark: boolean = false;
  @Prop index: number = 0;
  @Prop last: boolean = false;
  @State open: boolean = false;

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      Row() {
        Column() {
          Text(this.item.title.length > 0 ? this.item.title : this.item.id)
            .fontSize(Metrics.bodySize)
            .fontColor(this.pal().textPrimary)
          if (this.item.subtitle.length > 0) {
            Text(this.item.subtitle)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .margin({ top: 2 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        if (this.item.detail.length > 0) {
          Text(this.open ? Copy.tr('收起') : Copy.tr('查看配置'))
            .fontSize(Metrics.secondarySize)
            .fontColor(this.pal().sessionAccent)
        }
      }
      .width('100%')
      .constraintSize({ minHeight: 54 })
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .id('agentPresets.item.' + this.item.id)
      .onClick(() => {
        this.open = !this.open;
      })
      if (this.open && this.item.detail.length > 0) {
        Text(this.item.detail)
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().textSecondary)
          .lineHeight(19)
          .width('100%')
          .padding({ left: 16, right: 16, bottom: 10 })
      }
      if (!this.last) {
        Hairline({ dark: this.dark })
      }
    }
    .width('100%')
  }
}

@Component
export struct PluginInventoryView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State searchText: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('PluginInventoryView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private filtered(): NamedItem[] {
    const list: NamedItem[] = [];
    for (let i: number = 0; i < this.snapshot.items.length; i++) {
      const item: NamedItem = this.snapshot.items[i];
      if (this.searchText.length === 0
        || item.title.toLowerCase().indexOf(this.searchText.toLowerCase()) >= 0
        || item.id.toLowerCase().indexOf(this.searchText.toLowerCase()) >= 0) {
        list.push(item);
      }
    }
    return list;
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('已加载插件'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'pluginInventory.back',
        rightId: 'pluginInventory.refresh',
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
      TextInput({ text: this.searchText, placeholder: Copy.tr('搜索插件') })
        .fontSize(Metrics.processSize)
        .fontColor(this.pal().textPrimary)
        .placeholderColor(this.pal().inputPlaceholder)
        .height(40)
        .backgroundColor(this.pal().surface)
        .borderRadius(20)
        .padding({ left: 14, right: 14 })
        .margin({ left: 16, right: 16, top: 4, bottom: 8 })
        .id('pluginInventory.search')
        .onChange((value: string) => {
          this.searchText = value;
        })
      Scroll() {
        Column() {
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取插件…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('插件列表读取失败'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主还没有返回插件清单，暂不判断有没有插件。'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.filtered().length === 0) {
            Column() {
              EmptyState({
                title: this.searchText.length > 0 ? Copy.tr('没有匹配的插件。') : Copy.tr('没有已加载插件'),
                hint: this.searchText.length > 0 ? Copy.tr('换个词再找。') : Copy.tr('引擎还没给出插件列表。'),
                glyph: 'PUZZLE',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              ForEach(this.filtered(), (item: NamedItem, index: number) => {
                Column() {
                Row() {
                  Column() {
                    Row() {
                      Text(item.title.length > 0 ? item.title : item.id)
                        .fontSize(Metrics.bodySize)
                        .fontColor(this.pal().textPrimary)
                        .maxLines(1)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                      if (item.subtitle.length > 0) {
                        Text(item.subtitle)
                          .fontSize(Metrics.captionSize)
                          .fontColor(this.pal().textSubtle)
                          .margin({ left: 6 })
                      }
                    }
                    .width('100%')
                    if (item.detail.length > 0) {
                      Text(item.detail)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .maxLines(2)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .margin({ top: 2 })
                    }
                  }
                  .layoutWeight(1)
                  .alignItems(HorizontalAlign.Start)
                  Toggle({ type: ToggleType.Switch, isOn: item.enabled })
                    .selectedColor(this.pal().sessionAccent)
                    .id('pluginInventory.toggle.' + item.id)
                    .onChange((value: boolean) => {
                      this.requireActions().setItemEnabled(item.id, value).catch((error: BusinessError) => {
                        this.actionError = error.message;
                      });
                    })
                }
                .width('100%')
                .constraintSize({ minHeight: 58 })
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .alignItems(VerticalAlign.Center)
                if (index < this.filtered().length - 1) {
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
          Text(Copy.tr('这台手机没有 pnpm、execa 或子进程安装环境。插件和 bundle 可以在这里查看；安装、启用和停用请在桌面 DSH 完成。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8, bottom: 32 })
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
