import { ImageLoadControl } from '../model/ImageLoadControl';
// SPDX-License-Identifier: MIT
// Native data pages: files/preview, folders, automation editor/history,
// configuration, workbench command, tools, export, devices and artifacts.

import {
  ShellSnapshot, ShellActions, Page, Workspace, FileEntry, NamedItem, ChatRow,
  LoadPhase, FontRole, EnginePhase,
  fontSizeValue
} from '../model/Contracts';
import { Palette, Metrics, Copy } from './Palette';
import {
  NavHeader, SectionLabel, NavRow, InfoRow, PillButton, EmptyState,
  LoadingBlock, NoticeBanner, Hairline
} from './ShellSupport';
import { NativeIcon } from './NativeIcon';
import { MarkdownImage } from './MarkdownImage';
import { isImageSource } from '../model/ResourceLocator';
import { BusinessError } from '@kit.BasicServicesKit';

@Component
export struct FilesView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State searchText: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('FilesView requires ShellActions');
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

  private segments(): string[] {
    const parts: string[] = [];
    const raw: string[] = this.snapshot.filePath.split('/');
    for (let i: number = 0; i < raw.length; i++) {
      if (raw[i].length > 0) {
        parts.push(raw[i]);
      }
    }
    return parts;
  }

  private prefix(index: number): string {
    const parts: string[] = this.segments();
    let path: string = '';
    for (let i: number = 0; i <= index && i < parts.length; i++) {
      path = path + '/' + parts[i];
    }
    return path.length > 0 ? path : '/';
  }

  private visibleFiles(): FileEntry[] {
    if (this.searchText.length === 0) {
      return this.snapshot.files;
    }
    const list: FileEntry[] = [];
    for (let i: number = 0; i < this.snapshot.files.length; i++) {
      if (this.snapshot.files[i].name.toLowerCase().indexOf(this.searchText.toLowerCase()) >= 0) {
        list.push(this.snapshot.files[i]);
      }
    }
    return list;
  }

  private sizeLabel(entry: FileEntry): string {
    if (entry.directory) { return Copy.tr('文件夹'); }
    if (entry.size <= 0) { return Copy.unknown; }
    if (entry.size >= 1048576) {
      return (entry.size / 1048576).toFixed(1) + ' MiB';
    }
    return Math.ceil(entry.size / 1024).toString() + ' KiB';
  }

  @Builder
  Breadcrumb() {
    Scroll() {
      Row() {
        Text(Copy.tr('工作区'))
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().sessionAccent)
          .padding({ left: 4, right: 4 })
          .id('files.crumb.root')
          .onClick(() => {
            this.run(this.requireActions().openFile('/'));
          })
        ForEach(this.segments(), (segment: string, index: number) => {
          Row() {
          NativeIcon({ name: '路径分隔', symbol: 'CHEVRON_RIGHT', dark: this.dark, iconSize: 14, muted: true })
            .width(14)
            .height(14)
            .margin({ left: 2, right: 2 })
          Text(segment)
            .fontSize(Metrics.secondarySize)
            .fontColor(index === this.segments().length - 1 ? this.pal().textPrimary : this.pal().textSecondary)
            .maxLines(1)
            .padding({ left: 2, right: 2 })
            .id('files.crumb.' + index)
            .onClick(() => {
              this.run(this.requireActions().openFile(this.prefix(index)));
            })
          }.height(36).alignItems(VerticalAlign.Center)
        }, (segment: string, index: number) => segment + index.toString())
      }
      .height(36)
      .alignItems(VerticalAlign.Center)
    }
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .width('100%')
    .padding({ left: 12, right: 12 })
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('工作文件夹'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('导入'),
        backId: 'files.back',
        rightId: 'files.import',
        onBack: () => {
          this.requireActions().back();
        },
        onRight: () => {
          this.run(this.requireActions().pickFiles(false));
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({
            text: this.actionError, dark: this.dark, kind: 'error', showClose: true,
            bannerId: 'files.error',
            onClose: () => {
              this.actionError = '';
            }
          })
        }
        .padding({ left: 16, right: 16 })
      }
      this.Breadcrumb()
      Row() {
        TextInput({ text: this.searchText, placeholder: Copy.tr('搜索文件') })
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().textPrimary)
          .placeholderColor(this.pal().inputPlaceholder)
          .height(38)
          .backgroundColor(this.pal().surface)
          .borderRadius(19)
          .padding({ left: 14, right: 14 })
          .layoutWeight(1)
          .id('files.search')
          .onChange((value: string) => {
            this.searchText = value;
          })
        Text(Copy.tr('图片'))
          .fontSize(13)
          .fontColor(this.pal().textPrimary)
          .height(38)
          .padding({ left: 12, right: 12 })
          .borderRadius(19)
          .backgroundColor(this.pal().surface)
          .margin({ left: 8 })
          .id('files.importImages')
          .onClick(() => {
            this.run(this.requireActions().pickFiles(true));
          })
        if (this.snapshot.filePath !== '/') {
          Text(Copy.tr('上一级'))
            .fontSize(13)
            .fontColor(this.pal().textPrimary)
            .height(38)
            .padding({ left: 12, right: 12 })
            .borderRadius(19)
            .backgroundColor(this.pal().surface)
            .margin({ left: 8 })
            .id('files.up')
            .onClick(() => {
              this.run(this.requireActions().openFile(Copy.parentPath(this.snapshot.filePath)));
            })
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 2, bottom: 8 })

      Scroll() {
        Column() {
          if (this.snapshot.filesPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取文件…'), dark: this.dark })
          } else if (this.snapshot.filesPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('文件读取失败'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.filesPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主没有报告工作区文件，无法判断有没有'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.visibleFiles().length === 0) {
            Column() {
              EmptyState({
                title: this.searchText.length > 0 ? Copy.tr('没有匹配的文件') : Copy.tr('工作区还没有文件'),
                hint: this.searchText.length > 0 ? Copy.tr('换个词再找。') : Copy.tr('连接引擎后可导入或浏览工作区'),
                glyph: 'DOCUMENT',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              ForEach(this.visibleFiles(), (entry: FileEntry, index: number) => {
                Column() {
                Row() {
                  NativeIcon({
                    name: entry.directory ? '文件夹' : '文件',
                    symbol: entry.directory ? 'FOLDER' : 'DOCUMENT',
                    dark: this.dark,
                    iconSize: 18,
                    muted: true
                  })
                    .width(28)
                    .height(18)
                  Column() {
                    Text(entry.name.length > 0 ? entry.name : entry.path)
                      .fontSize(Metrics.bodySize)
                      .fontColor(this.pal().textPrimary)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(this.sizeLabel(entry))
                      .fontSize(Metrics.captionSize)
                      .fontColor(this.pal().textSubtle)
                      .margin({ top: 2 })
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  NativeIcon({ name: '打开', symbol: 'CHEVRON_RIGHT', dark: this.dark, iconSize: 18, muted: true })
                    .width(18)
                    .height(18)
                }
                .width('100%')
                .constraintSize({ minHeight: 54 })
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .id('files.item.' + entry.path)
                .onClick(() => {
                  this.run(this.requireActions().openFile(entry.path));
                })
                if (index < this.visibleFiles().length - 1) {
                  Hairline({ dark: this.dark })
                }
                }.width('100%')
              }, (entry: FileEntry) => JSON.stringify(entry))
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16 })
          }
          if (this.snapshot.previewPath.length > 0) {
            Text(Copy.tr('当前预览：') + this.snapshot.previewPath)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 24, right: 24, top: 8 })
          }
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
export struct PreviewView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('PreviewView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('预览'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'preview.back',
        rightId: 'preview.refresh',
        onBack: () => {
          this.requireActions().back();
        },
        onRight: () => {
          if (this.snapshot.previewPath.length > 0) {
            this.requireActions().openFile(this.snapshot.previewPath).catch((error: BusinessError) => {
              this.actionError = error.message;
            });
          } else {
            this.requireActions().refresh().catch((error: BusinessError) => {
              this.actionError = error.message;
            });
          }
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
          if (this.snapshot.previewPath.length > 0) {
            Text(this.snapshot.previewPath)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .width('100%')
              .padding({ left: 16, right: 16, top: 8, bottom: 8 })
          }
          if (this.snapshot.previewPath.length > 0 && isImageSource(this.snapshot.previewPath)) {
            MarkdownImage({
              source: this.snapshot.previewPath,
              alt: Copy.tr('图片预览'),
              dark: this.dark,
              bodyFontSize: fontSizeValue(this.snapshot.settings, FontRole.Text),
              loadImage: (source: string, control?: ImageLoadControl) => this.requireActions().loadImage(source, control)
            })
            .width('100%')
            .padding({ left: 16, right: 16, top: 4 })
          }
          if (this.snapshot.previewText.length > 0) {
            Text(this.snapshot.previewText)
              .fontSize(fontSizeValue(this.snapshot.settings, FontRole.Code))
              .fontFamily('monospace')
              .fontColor(this.pal().textPrimary)
              .lineHeight(fontSizeValue(this.snapshot.settings, FontRole.Code) + 7)
              .width('100%')
              .padding(14)
              .backgroundColor(this.pal().sessionCode)
              .borderRadius(14)
              .margin({ left: 16, right: 16, top: 4 })
          } else if (!isImageSource(this.snapshot.previewPath)) {
            Column() {
              EmptyState({
                title: Copy.tr('没有可预览的内容'),
                hint: Copy.tr('宿主没有为这个路径报告文本预览。'),
                glyph: 'DOCUMENT',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16, top: 8 })
          }
          Row() {
            PillButton({
              label: Copy.tr('打开文件列表'), dark: this.dark, kind: 'secondary', compact: true,
              buttonId: 'preview.openFiles',
              onTap: () => {
                this.requireActions().navigate(Page.Files);
              }
            })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 32 })
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
export struct WorkspaceFoldersView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State dialogKind: string = '';
  @State dialogTargetId: string = '';
  @State dialogValue: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('WorkspaceFoldersView requires ShellActions');
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

  private sessionCount(folderId: string): number {
    let count: number = 0;
    for (let i: number = 0; i < this.snapshot.sessions.length; i++) {
      if (this.snapshot.sessions[i].folderId === folderId && !this.snapshot.sessions[i].archived) {
        count++;
      }
    }
    return count;
  }

  private openDialog(kind: string, id: string, value: string): void {
    this.dialogKind = kind;
    this.dialogTargetId = id;
    this.dialogValue = value;
  }

  private confirmDialog(): void {
    if (this.dialogKind === 'create') {
      if (this.dialogValue.length > 0) {
        this.run(this.requireActions().createFolder(this.dialogValue));
      }
    } else if (this.dialogKind === 'rename') {
      if (this.dialogValue.length > 0) {
        this.run(this.requireActions().renameFolder(this.dialogTargetId, this.dialogValue));
      }
    } else if (this.dialogKind === 'delete') {
      this.run(this.requireActions().deleteFolder(this.dialogTargetId));
    }
    this.dialogKind = '';
  }

  private dialogTitle(): string {
    if (this.dialogKind === 'create') { return Copy.tr('新建文件夹'); }
    if (this.dialogKind === 'rename') { return Copy.tr('重命名文件夹'); }
    if (this.dialogKind === 'delete') { return Copy.tr('删除这个文件夹？'); }
    return '';
  }

  private dialogDetail(): string {
    if (this.dialogKind === 'delete') {
      return Copy.tr('里面的会话会留着，只是不再放在这个文件夹里');
    }
    if (this.dialogKind === 'create') {
      return Copy.tr('会在工作区里新建同名文件夹，之后的会话和文件都可以放进去。');
    }
    return '';
  }

  @Builder
  FolderDialog() {
    if (this.dialogKind.length > 0) {
      Column() {
        Column() {
          Text(this.dialogTitle())
            .fontSize(18)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.pal().textPrimary)
            .width('100%')
          if (this.dialogDetail().length > 0) {
            Text(this.dialogDetail())
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSecondary)
              .lineHeight(19)
              .margin({ top: 8 })
              .width('100%')
          }
          if (this.dialogKind !== 'delete') {
            TextInput({ text: this.dialogValue, placeholder: Copy.tr('文件夹名称') })
              .fontSize(Metrics.processSize)
              .fontColor(this.pal().textPrimary)
              .placeholderColor(this.pal().inputPlaceholder)
              .height(44)
              .backgroundColor(this.pal().sessionSelector)
              .borderRadius(12)
              .margin({ top: 12 })
              .id('folders.dialog.input')
              .onChange((value: string) => {
                this.dialogValue = value;
              })
          }
          Row() {
            Text(Copy.tr('取消'))
              .fontSize(15)
              .fontColor(this.pal().textSecondary)
              .height(40)
              .padding({ left: 16, right: 16 })
              .borderRadius(20)
              .backgroundColor(this.pal().sessionSelector)
              .id('folders.dialog.cancel')
              .onClick(() => {
                this.dialogKind = '';
              })
            Text(this.dialogKind === 'delete' ? Copy.tr('删除') : Copy.tr('保存'))
              .fontSize(15)
              .fontColor(this.pal().primaryButtonInk)
              .height(40)
              .padding({ left: 18, right: 18 })
              .borderRadius(20)
              .backgroundColor(this.dialogKind === 'delete' ? this.pal().pageDanger : this.pal().primaryButton)
              .margin({ left: 8 })
              .id('folders.dialog.confirm')
              .onClick(() => {
                this.confirmDialog();
              })
          }
          .width('100%')
          .justifyContent(FlexAlign.End)
          .margin({ top: 16 })
        }
        .width('86%')
        .padding(20)
        .backgroundColor(this.pal().surface)
        .borderRadius(20)
        .shadow({ radius: 24, color: '#44000000', offsetY: 8 })
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.pal().scrim)
      .justifyContent(FlexAlign.Center)
      .alignItems(HorizontalAlign.Center)
    }
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Column() {
        NavHeader({
          title: Copy.tr('工作文件夹'),
          dark: this.dark,
          showBack: true,
          rightText: Copy.tr('添加'),
          backId: 'folders.back',
          rightId: 'folders.add',
          onBack: () => {
            this.requireActions().back();
          },
          onRight: () => {
            this.openDialog('create', '', '');
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
            if (this.snapshot.folders.length === 0) {
              Column() {
                EmptyState({
                  title: Copy.tr('还没有工作文件夹'),
                  hint: Copy.tr('会话把生成的文件放在文件夹里。新建一个文件夹给它用。'),
                  glyph: 'FOLDER',
                  dark: this.dark
                })
              }
              .backgroundColor(this.pal().surface)
              .borderRadius(Metrics.settingsCardRadius)
              .margin({ left: 16, right: 16, top: 16 })
            } else {
              Column() {
                ForEach(this.snapshot.folders, (folder: Workspace, index: number) => {
                  Column() {
                    Row() {
                      Column() {
                        Row() {
                          Text(folder.name.length > 0 ? folder.name : Copy.unknown)
                            .fontSize(Metrics.bodySize)
                            .fontColor(this.pal().textPrimary)
                          if (folder.id === this.snapshot.activeFolderId) {
                            Text(Copy.tr('当前'))
                              .fontSize(Metrics.captionSize)
                              .fontColor(this.pal().sessionSuccess)
                              .margin({ left: 6 })
                          }
                        }
                        Text((folder.path.length > 0 ? folder.path : Copy.unknown) + ' · '
                          + Copy.fill('{0} 个会话', [this.sessionCount(folder.id).toString()]))
                          .fontSize(Metrics.captionSize)
                          .fontColor(this.pal().textSubtle)
                          .margin({ top: 3 })
                      }
                      .alignItems(HorizontalAlign.Start)
                      .layoutWeight(1)
                      Row() {
                        if (folder.id !== this.snapshot.activeFolderId) {
                          Text(Copy.tr('切换'))
                            .fontSize(13)
                            .fontColor(this.pal().textPrimary)
                            .height(32)
                            .padding({ left: 10, right: 10 })
                            .borderRadius(16)
                            .backgroundColor(this.pal().sessionSelector)
                            .id('folders.select.' + folder.id)
                            .onClick(() => {
                              this.run(this.requireActions().selectFolder(folder.id));
                            })
                        }
                        Text(Copy.tr('重命名'))
                          .fontSize(13)
                          .fontColor(this.pal().textPrimary)
                          .height(32)
                          .padding({ left: 10, right: 10 })
                          .borderRadius(16)
                          .backgroundColor(this.pal().sessionSelector)
                          .margin({ left: 6 })
                          .id('folders.rename.' + folder.id)
                          .onClick(() => {
                            this.openDialog('rename', folder.id, folder.name);
                          })
                        Text(Copy.tr('删除'))
                          .fontSize(13)
                          .fontColor(folder.id === this.snapshot.activeFolderId ? this.pal().textTertiary : this.pal().pageDanger)
                          .height(32)
                          .padding({ left: 10, right: 10 })
                          .borderRadius(16)
                          .backgroundColor(this.pal().sessionSelector)
                          .margin({ left: 6 })
                          .id('folders.delete.' + folder.id)
                          .onClick(() => {
                            if (folder.id !== this.snapshot.activeFolderId) {
                              this.openDialog('delete', folder.id, folder.name);
                            }
                          })
                      }
                    }
                    .width('100%')
                    .constraintSize({ minHeight: 64 })
                    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                    if (index < this.snapshot.folders.length - 1) {
                      Hairline({ dark: this.dark })
                    }
                  }
                  .width('100%')
                }, (folder: Workspace) => JSON.stringify(folder))
              }
              .backgroundColor(this.pal().surface)
              .borderRadius(Metrics.settingsCardRadius)
              .clip(true)
              .margin({ left: 16, right: 16, top: 16 })
            }
            Text(Copy.tr('删除当前文件夹会被宿主拒绝；先切换到其他文件夹再删除。'))
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
      this.FolderDialog()
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}

@Component
export struct AutomationView {
  @Prop @Watch('onSnapshotUpdated') snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State editorOpen: boolean = false;
  @State editorId: string = '';
  @State editorName: string = '';
  @State editorPrompt: string = '';
  @State editorRule: string = 'after';
  @State editorAfterSeconds: string = '600';
  @State editorAt: string = '';
  @State editorEverySeconds: string = '3600';
  @State editorTime: string = '09:00:00';
  @State editorTimeZone: string = '';
  @State editorExpression: string = '';
  @State editorWeekdays: string = '1,2,3,4,5';
  @State editorChangeTiming: boolean = false;
  @State editorNotice: string = '';
  @State confirmDeleteId: string = '';

  onSnapshotUpdated(): void {
    if (this.snapshot.notice.length > 0) {
      this.actionError = '';
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('AutomationView requires ShellActions');
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

  private localTimeZone(): string {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone;
    } catch (error) {
      return '';
    }
  }

  private draftOf(text: string): Record<string, Object> | null {
    if (text.length === 0 || text.charAt(0) !== '{') {
      return null;
    }
    try {
      const parsed: Object = JSON.parse(text) as Object;
      if (typeof parsed !== 'object' || Array.isArray(parsed)) {
        return null;
      }
      return parsed as Record<string, Object>;
    } catch (error) {
      return null;
    }
  }

  private draftText(row: Record<string, Object> | null, key: string, fallback: string): string {
    if (row === null) {
      return fallback;
    }
    const value: Object | undefined = row[key];
    if (typeof value === 'string') {
      return value as string;
    }
    if (typeof value === 'number' || typeof value === 'boolean') {
      return String(value);
    }
    return fallback;
  }

  private automationPrompt(item: NamedItem): string {
    const row = this.draftOf(item.detail);
    return row === null ? item.detail : this.draftText(row, 'prompt', '');
  }

  private openEditor(item: NamedItem | undefined): void {
    this.editorNotice = '';
    this.actionError = '';
    this.editorChangeTiming = false;
    if (item === undefined) {
      this.editorId = '';
      this.editorName = '';
      this.editorPrompt = '';
      this.editorRule = 'after';
      this.editorAfterSeconds = '600';
      this.editorAt = '';
      this.editorEverySeconds = '3600';
      this.editorTime = '09:00:00';
      this.editorTimeZone = this.localTimeZone();
      this.editorExpression = '';
      this.editorWeekdays = '1,2,3,4,5';
    } else {
      this.editorId = item.id;
      this.editorName = item.title;
      const row = this.draftOf(item.detail);
      this.editorPrompt = this.draftText(row, 'prompt', '');
      this.editorRule = this.draftText(row, 'kind', 'after');
      this.editorAfterSeconds = this.draftText(row, 'afterSeconds', '600');
      this.editorAt = this.draftText(row, 'scheduledAt', '');
      this.editorEverySeconds = this.draftText(row, 'everySeconds', '3600');
      this.editorTime = this.draftText(row, 'time', '09:00:00');
      this.editorTimeZone = this.draftText(row, 'timeZone', this.localTimeZone());
      this.editorExpression = this.draftText(row, 'expression', '');
      this.editorWeekdays = this.draftText(row, 'weekdays', '1,2,3,4,5');
    }
    this.editorOpen = true;
  }

  private timingValid(): boolean {
    if (this.editorRule === 'after') {
      return Number(this.editorAfterSeconds) > 0;
    }
    if (this.editorRule === 'at') {
      return this.editorAt.trim().length > 0;
    }
    if (this.editorRule === 'every') {
      return Number(this.editorEverySeconds) > 0;
    }
    if (this.editorRule === 'cron') {
      return this.editorExpression.trim().length > 0 && this.editorTimeZone.trim().length > 0;
    }
    return this.editorTime.trim().length > 0 && this.editorTimeZone.trim().length > 0 &&
      (this.editorRule !== 'weekly' || this.editorWeekdays.trim().length > 0);
  }

  private saveEditor(): void {
    if (this.editorName.trim().length === 0) {
      this.actionError = Copy.tr('名称不能为空');
      return;
    }
    if (this.editorPrompt.trim().length === 0) {
      this.actionError = Copy.tr('提醒内容不能为空');
      return;
    }
    if (!this.timingValid()) {
      this.actionError = Copy.tr('请填写完整有效的重复规则');
      return;
    }
    const draft: Record<string, Object> = {};
    draft['title'] = this.editorName.trim();
    draft['prompt'] = this.editorPrompt.trim();
    draft['rule'] = this.editorRule;
    draft['afterSeconds'] = this.editorAfterSeconds.trim();
    draft['at'] = this.editorAt.trim();
    draft['everySeconds'] = this.editorEverySeconds.trim();
    draft['time'] = this.editorTime.trim();
    draft['timeZone'] = this.editorTimeZone.trim();
    draft['expression'] = this.editorExpression.trim();
    draft['weekdays'] = this.editorWeekdays.trim();
    if (this.editorId.length > 0) {
      draft['changeTiming'] = this.editorChangeTiming;
    }
    const id: string = this.editorId.length > 0 ? this.editorId : 'automation:new';
    this.actionError = '';
    this.requireActions().saveItem(id, JSON.stringify(draft)).then(() => {
      this.editorOpen = false;
      this.editorNotice = '';
    }).catch((error: BusinessError) => {
      // Keep the draft open and visible after any refusal or conflict.
      this.actionError = error.message;
    });
  }

  private rules(): string[] {
    return ['after', 'at', 'every', 'daily', 'weekly', 'cron'];
  }

  private ruleLabel(rule: string): string {
    if (rule === 'after') {
      return Copy.tr('延时');
    }
    if (rule === 'at') {
      return Copy.tr('定时');
    }
    if (rule === 'every') {
      return Copy.tr('间隔');
    }
    if (rule === 'daily') {
      return Copy.tr('每天');
    }
    if (rule === 'weekly') {
      return Copy.tr('每周');
    }
    return 'Cron';
  }

  private markTimingChanged(): void {
    if (this.editorId.length > 0) {
      this.editorChangeTiming = true;
    }
  }

  @Builder
  TaskEditor() {
    if (this.editorOpen) {
      Column() {
        Text(this.editorId.length > 0 ? Copy.tr('编辑自动化') : Copy.tr('新建自动化'))
          .fontSize(17)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.pal().textPrimary)
          .width('100%')
        Text(Copy.tr('名称'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .width('100%')
          .margin({ top: 12, bottom: 4 })
        TextInput({ text: this.editorName, placeholder: Copy.tr('任务名称（最多 120 字符）') })
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textPrimary)
          .placeholderColor(this.pal().inputPlaceholder)
          .height(44)
          .backgroundColor(this.pal().sessionSelector)
          .borderRadius(12)
          .id('automation.editor.name')
          .onChange((value: string) => {
            this.editorName = value;
          })
        Text(Copy.tr('提醒内容'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .width('100%')
          .margin({ top: 12, bottom: 4 })
        TextArea({ text: this.editorPrompt, placeholder: Copy.tr('到点后要执行的内容') })
          .fontSize(Metrics.processSize)
          .fontColor(this.pal().textPrimary)
          .placeholderColor(this.pal().inputPlaceholder)
          .height(92)
          .backgroundColor(this.pal().sessionSelector)
          .borderRadius(12)
          .id('automation.editor.prompt')
          .onChange((value: string) => {
            this.editorPrompt = value;
          })
        Text(Copy.tr('重复规则'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .width('100%')
          .margin({ top: 12, bottom: 6 })
        Row() {
          ForEach(this.rules(), (rule: string) => {
            Text(this.ruleLabel(rule))
              .fontSize(13)
              .fontColor(this.editorRule === rule ? this.pal().primaryButtonInk : this.pal().textPrimary)
              .height(32)
              .padding({ left: 10, right: 10 })
              .borderRadius(16)
              .backgroundColor(this.editorRule === rule ? this.pal().primaryButton : this.pal().sessionSelector)
              .margin({ right: 6 })
              .id('automation.editor.rule.' + rule)
              .onClick(() => {
                if (this.editorRule !== rule) {
                  this.editorRule = rule;
                  this.markTimingChanged();
                }
              })
          }, (rule: string) => rule)
        }
        .width('100%')
        if (this.editorRule === 'after') {
          Text(Copy.tr('多少秒后提醒'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .width('100%')
            .margin({ top: 12, bottom: 4 })
          TextInput({ text: this.editorAfterSeconds, placeholder: Copy.tr('例如 600') })
            .fontSize(Metrics.processSize)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .id('automation.editor.after')
            .onChange((value: string) => {
              this.editorAfterSeconds = value;
              this.markTimingChanged();
            })
        } else if (this.editorRule === 'at') {
          Text(Copy.tr('触发时间（ISO 8601，含时区）'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .width('100%')
            .margin({ top: 12, bottom: 4 })
          TextInput({ text: this.editorAt, placeholder: '2026-11-02T09:00:00Z' })
            .fontSize(Metrics.processSize)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .id('automation.editor.at')
            .onChange((value: string) => {
              this.editorAt = value;
              this.markTimingChanged();
            })
        } else if (this.editorRule === 'every') {
          Text(Copy.tr('每隔多少秒提醒'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .width('100%')
            .margin({ top: 12, bottom: 4 })
          TextInput({ text: this.editorEverySeconds, placeholder: Copy.tr('例如 3600') })
            .fontSize(Metrics.processSize)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .id('automation.editor.every')
            .onChange((value: string) => {
              this.editorEverySeconds = value;
              this.markTimingChanged();
            })
        } else {
          if (this.editorRule === 'daily' || this.editorRule === 'weekly') {
            Text(Copy.tr('本地时间（HH:mm:ss）'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .width('100%')
              .margin({ top: 12, bottom: 4 })
            TextInput({ text: this.editorTime, placeholder: '09:00:00' })
              .fontSize(Metrics.processSize)
              .height(44)
              .backgroundColor(this.pal().sessionSelector)
              .borderRadius(12)
              .id('automation.editor.time')
              .onChange((value: string) => {
                this.editorTime = value;
                this.markTimingChanged();
              })
          }
          Text(Copy.tr('时区（IANA）'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .width('100%')
            .margin({ top: 12, bottom: 4 })
          TextInput({ text: this.editorTimeZone, placeholder: 'Asia/Shanghai' })
            .fontSize(Metrics.processSize)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .id('automation.editor.timezone')
            .onChange((value: string) => {
              this.editorTimeZone = value;
              this.markTimingChanged();
            })
          if (this.editorRule === 'weekly') {
            Text(Copy.tr('星期（1-7，逗号分隔）'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .width('100%')
              .margin({ top: 12, bottom: 4 })
            TextInput({ text: this.editorWeekdays, placeholder: '1,2,3,4,5' })
              .fontSize(Metrics.processSize)
              .height(44)
              .backgroundColor(this.pal().sessionSelector)
              .borderRadius(12)
              .id('automation.editor.weekdays')
              .onChange((value: string) => {
                this.editorWeekdays = value;
                this.markTimingChanged();
              })
          }
          if (this.editorRule === 'cron') {
            Text(Copy.tr('Cron 表达式（五段）'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .width('100%')
              .margin({ top: 12, bottom: 4 })
            TextInput({ text: this.editorExpression, placeholder: '0 9 * * 1-5' })
              .fontSize(Metrics.processSize)
              .height(44)
              .backgroundColor(this.pal().sessionSelector)
              .borderRadius(12)
              .id('automation.editor.expression')
              .onChange((value: string) => {
                this.editorExpression = value;
                this.markTimingChanged();
              })
          }
        }
        if (this.editorNotice.length > 0) {
          Text(this.editorNotice)
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSecondary)
            .lineHeight(17)
            .width('100%')
            .margin({ top: 4 })
        } else {
          Text(Copy.tr('保存走 Worker 已有的定时任务接口；失败时会保留这份草稿。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .width('100%')
            .margin({ top: 4 })
        }
        Row() {
          Text(Copy.tr('取消'))
            .fontSize(14)
            .fontColor(this.pal().textSecondary)
            .height(38)
            .padding({ left: 14, right: 14 })
            .borderRadius(19)
            .backgroundColor(this.pal().sessionSelector)
            .id('automation.editor.cancel')
            .onClick(() => {
              this.editorOpen = false;
            })
          Text(Copy.tr('保存'))
            .fontSize(14)
            .fontColor(this.pal().primaryButtonInk)
            .height(38)
            .padding({ left: 16, right: 16 })
            .borderRadius(19)
            .backgroundColor(this.pal().primaryButton)
            .margin({ left: 8 })
            .id('automation.editor.save')
            .onClick(() => {
              this.saveEditor();
            })
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
        .margin({ top: 12 })
      }
      .width('100%')
      .padding(16)
      .backgroundColor(this.pal().surface)
      .borderRadius(Metrics.settingsCardRadius)
      .margin({ left: 16, right: 16, top: 8 })
    }
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('定时任务'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('新建'),
        backId: 'automation.back',
        rightId: 'automation.new',
        onBack: () => {
          this.requireActions().back();
        },
        onRight: () => {
          this.openEditor(undefined);
        }
      })
      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({
            text: this.actionError, dark: this.dark, kind: 'error', showClose: true,
            bannerId: 'automation.error',
            onClose: () => {
              this.actionError = '';
            }
          })
        }
        .padding({ left: 16, right: 16 })
      }
      Scroll() {
        Column() {
          this.TaskEditor()
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取定时任务…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('定时任务读取失败'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主没有报告定时任务，无法判断有没有；不会用 0 条代替'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('还没有定时任务'),
                hint: Copy.tr('到点让它自己跑,结果回到原会话。'),
                glyph: 'CLOCK',
                dark: this.dark
              })
              Row() {
                PillButton({
                  label: Copy.tr('新建任务'), dark: this.dark, compact: true, buttonId: 'automation.empty.new',
                  onTap: () => {
                    this.openEditor(undefined);
                  }
                })
              }
              .padding({ bottom: 24 })
            }
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
                        Text(item.enabled ? Copy.tr('活动') : Copy.tr('已停止'))
                          .fontSize(Metrics.captionSize)
                          .fontColor(item.enabled ? this.pal().sessionSuccess : this.pal().textSubtle)
                          .margin({ left: 6 })
                      }
                      if (item.subtitle.length > 0) {
                        Text(item.subtitle)
                          .fontSize(Metrics.captionSize)
                          .fontColor(this.pal().textSubtle)
                          .margin({ top: 2 })
                      }
                      if (this.automationPrompt(item).length > 0) {
                        Text(this.automationPrompt(item))
                          .fontSize(Metrics.secondarySize)
                          .fontColor(this.pal().textSecondary)
                          .maxLines(2)
                          .textOverflow({ overflow: TextOverflow.Ellipsis })
                          .margin({ top: 3 })
                      }
                    }
                    .alignItems(HorizontalAlign.Start)
                    .layoutWeight(1)
                    Row() {
                      Text(Copy.tr('编辑'))
                        .fontSize(13)
                        .fontColor(this.pal().textPrimary)
                        .height(32)
                        .padding({ left: 10, right: 10 })
                        .borderRadius(16)
                        .backgroundColor(this.pal().sessionSelector)
                        .id('automation.edit.' + item.id)
                        .onClick(() => {
                          this.openEditor(item);
                        })
                      Text(Copy.tr('删除'))
                        .fontSize(13)
                        .fontColor(this.pal().pageDanger)
                        .height(32)
                        .padding({ left: 10, right: 10 })
                        .borderRadius(16)
                        .backgroundColor(this.pal().sessionSelector)
                        .margin({ left: 6 })
                        .id('automation.delete.' + item.id)
                        .onClick(() => {
                          this.confirmDeleteId = item.id;
                        })
                    }
                  }
                  .width('100%')
                  .padding({ left: 16, right: 16, top: 10, bottom: 10 })
                  if (this.confirmDeleteId === item.id) {
                    Row() {
                      Text(Copy.tr('删除此自动化任务？'))
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().pageDanger)
                        .layoutWeight(1)
                      Text(Copy.tr('取消'))
                        .fontSize(13)
                        .fontColor(this.pal().textSecondary)
                        .height(30)
                        .padding({ left: 10, right: 10 })
                        .id('automation.delete.cancel')
                        .onClick(() => {
                          this.confirmDeleteId = '';
                        })
                      Text(Copy.tr('删除'))
                        .fontSize(13)
                        .fontColor(this.pal().pageDanger)
                        .height(30)
                        .padding({ left: 10, right: 10 })
                        .id('automation.delete.confirm')
                        .onClick(() => {
                          this.confirmDeleteId = '';
                          this.run(this.requireActions().deleteItem(item.id));
                        })
                    }
                    .width('100%')
                    .padding({ left: 16, right: 16, bottom: 8 })
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
            .margin({ left: 16, right: 16, top: 8 })
          }

          SectionLabel({ label: Copy.tr('最近投递'), dark: this.dark })
          if (this.snapshot.detail.length > 0) {
            Text(this.snapshot.detail)
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSecondary)
              .lineHeight(19)
              .width('100%')
              .padding(14)
              .backgroundColor(this.pal().surface)
              .borderRadius(Metrics.settingsCardRadius)
              .margin({ left: 16, right: 16 })
          } else {
            Text(Copy.tr('宿主没有报告投递记录，无法判断有没有。'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 24, right: 24 })
          }
          Row() {
            PillButton({
              label: Copy.tr('刷新'), dark: this.dark, kind: 'secondary', compact: true,
              buttonId: 'automation.refresh',
              onTap: () => {
                this.run(this.requireActions().refresh());
              }
            })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 32 })
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
export struct ConfigurationView {
  @Prop @Watch('onSnapshotUpdated') snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State configEditorId: string = '';
  @State configEditorKind: string = '';
  @State configEditorValue: string = '';
  @State configEditorSelected: string[] = [];
  @State configEditorError: string = '';

  onSnapshotUpdated(): void {
    if (this.snapshot.notice.length > 0) {
      this.actionError = '';
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ConfigurationView requires ShellActions');
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

  private configItem(id: string): NamedItem | undefined {
    for (let index = 0; index < this.snapshot.items.length; index++) {
      if (this.snapshot.items[index].id === id) {
        return this.snapshot.items[index];
      }
    }
    return undefined;
  }

  private configTitle(): string {
    const item = this.configItem(this.configEditorId);
    return item === undefined ? '配置' : item.title;
  }

  private configOptions(): string[] {
    const item = this.configItem(this.configEditorId);
    return item === undefined ? [] : item.options;
  }

  private openConfigEditor(item: NamedItem): void {
    if (!item.enabled || (item.valueType !== 'number' && item.valueType !== 'modelSelection')) {
      return;
    }
    this.configEditorId = item.id;
    this.configEditorKind = item.valueType;
    this.configEditorValue = item.subtitle;
    this.configEditorSelected = item.selected.slice();
    this.configEditorError = '';
    this.actionError = '';
  }

  private setConfigOption(option: string, selected: boolean): void {
    const next: string[] = [];
    let found = false;
    for (let index = 0; index < this.configEditorSelected.length; index++) {
      const value = this.configEditorSelected[index];
      if (value === option) {
        found = true;
        if (selected) {
          next.push(value);
        }
      } else {
        next.push(value);
      }
    }
    if (selected && !found) {
      next.push(option);
    }
    this.configEditorSelected = next;
  }

  private saveConfigEditor(): void {
    const item = this.configItem(this.configEditorId);
    if (item === undefined) {
      this.configEditorError = Copy.tr('配置项已不存在，请刷新');
      return;
    }
    let value: string;
    if (this.configEditorKind === 'number') {
      const raw = this.configEditorValue.trim();
      if (raw.length === 0 || !Number.isFinite(Number(raw))) {
        this.configEditorError = Copy.tr('请输入有效数字');
        return;
      }
      value = raw;
    } else {
      const routes: Record<string, Object>[] = [];
      for (let index = 0; index < this.configEditorSelected.length; index++) {
        const key = this.configEditorSelected[index];
        const slash = key.indexOf('/');
        if (slash <= 0 || slash >= key.length - 1) {
          this.configEditorError = Copy.tr('模型选择不合法，请重新选择');
          return;
        }
        const route: Record<string, Object> = {};
        route['provider'] = key.substring(0, slash);
        route['model'] = key.substring(slash + 1);
        routes.push(route);
      }
      value = JSON.stringify(routes);
    }
    this.configEditorError = '';
    this.requireActions().saveItem(item.id, value).then(() => {
      this.configEditorId = '';
    }).catch((error: BusinessError) => {
      // A stale revision, invalid value, or refusal stays visible in the editor.
      this.configEditorError = error.message;
    });
  }

  @Builder
  ConfigEditor() {
    if (this.configEditorId.length > 0) {
      Column() {
        Text(this.configTitle())
          .fontSize(16)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.pal().textPrimary)
          .width('100%')
        if (this.configEditorKind === 'number') {
          Text(Copy.tr('新数值'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .width('100%')
            .margin({ top: 12, bottom: 4 })
          TextInput({ text: this.configEditorValue, placeholder: Copy.tr('数字') })
            .fontSize(Metrics.processSize)
            .height(44)
            .backgroundColor(this.pal().sessionSelector)
            .borderRadius(12)
            .id('configuration.editor.number')
            .onChange((value: string) => {
              this.configEditorValue = value;
            })
        } else {
          Text(Copy.tr('允许的模型路由'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .width('100%')
            .margin({ top: 12, bottom: 4 })
          ForEach(this.configOptions(), (option: string) => {
            Row() {
              Text(option)
                .fontSize(Metrics.processSize)
                .fontColor(this.pal().textPrimary)
                .layoutWeight(1)
              Toggle({ type: ToggleType.Switch, isOn: this.configEditorSelected.indexOf(option) >= 0 })
                .selectedColor(this.pal().sessionAccent)
                .id('configuration.editor.model.' + option)
                .onChange((value: boolean) => {
                  this.setConfigOption(option, value);
                })
            }
            .width('100%')
            .constraintSize({ minHeight: 44 })
          }, (option: string) => option)
        }
        if (this.configEditorError.length > 0) {
          Text(this.configEditorError)
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().pageDanger)
            .lineHeight(17)
            .width('100%')
            .margin({ top: 6 })
        }
        Row() {
          Text(Copy.tr('取消'))
            .fontSize(14)
            .fontColor(this.pal().textSecondary)
            .height(38)
            .padding({ left: 14, right: 14 })
            .borderRadius(19)
            .backgroundColor(this.pal().sessionSelector)
            .id('configuration.editor.cancel')
            .onClick(() => {
              this.configEditorId = '';
            })
          Text(Copy.tr('保存'))
            .fontSize(14)
            .fontColor(this.pal().primaryButtonInk)
            .height(38)
            .padding({ left: 16, right: 16 })
            .borderRadius(19)
            .backgroundColor(this.pal().primaryButton)
            .margin({ left: 8 })
            .id('configuration.editor.save')
            .onClick(() => {
              this.saveConfigEditor();
            })
        }
        .width('100%')
        .justifyContent(FlexAlign.End)
        .margin({ top: 12 })
      }
      .width('100%')
      .padding(16)
      .backgroundColor(this.pal().surface)
      .borderRadius(Metrics.settingsCardRadius)
      .margin({ left: 16, right: 16, top: 8 })
    }
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('插件与工具'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'configuration.back',
        rightId: 'configuration.refresh',
        onBack: () => {
          this.requireActions().back();
        },
        onRight: () => {
          this.run(this.requireActions().refresh());
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
          Text(Copy.tr('安装、启用和配置插件'))
            .fontSize(Metrics.secondarySize)
            .fontColor(this.pal().textSubtle)
            .padding({ left: 24, right: 24, top: 8 })

          this.ConfigEditor()

          SectionLabel({ label: Copy.tr('会话'), dark: this.dark })
          Column() {
            InfoRow({ label: Copy.tr('当前会话'), value: this.snapshot.title, dark: this.dark, showDivider: true })
            InfoRow({
              label: Copy.tr('文件夹'), value: Copy.folderNameFor(this.snapshot, this.snapshot.activeFolderId),
              dark: this.dark, showDivider: true
            })
            InfoRow({ label: Copy.tr('引擎'), value: Copy.engineLabel(this.snapshot.engine), dark: this.dark, showDivider: false })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('已加载插件'), dark: this.dark })
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
                text: Copy.tr('宿主没有报告插件列表，无法判断有没有'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('引擎还没给出插件列表'),
                hint: Copy.tr('只有真正加载起来的插件才会显示为已启用。'),
                glyph: 'PUZZLE',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
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
                    if (item.detail.length > 0) {
                      Text(item.detail)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .maxLines(2)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .margin({ top: 2 })
                    }
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  if (item.valueType === 'boolean' || item.id.startsWith('plugin:')) {
                    Toggle({ type: ToggleType.Switch, isOn: item.enabled })
                      .selectedColor(this.pal().sessionAccent)
                      .id('configuration.toggle.' + item.id)
                      .onChange((value: boolean) => {
                        this.run(this.requireActions().setItemEnabled(item.id, value));
                      })
                  } else if (item.valueType === 'number' || item.valueType === 'modelSelection') {
                    Text(item.enabled ? Copy.tr('编辑') : Copy.tr('只读'))
                      .fontSize(13)
                      .fontColor(item.enabled ? this.pal().sessionAccent : this.pal().textSubtle)
                      .height(32)
                      .padding({ left: 10, right: 10 })
                      .borderRadius(16)
                      .backgroundColor(this.pal().sessionSelector)
                      .id('configuration.edit.' + item.id)
                      .onClick(() => {
                        this.openConfigEditor(item);
                      })
                  } else {
                    Text(Copy.tr('只读'))
                      .fontSize(13)
                      .fontColor(this.pal().textSubtle)
                      .height(32)
                      .padding({ left: 10, right: 10 })
                  }
                }
                .width('100%')
                .constraintSize({ minHeight: 58 })
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

          SectionLabel({ label: Copy.tr('本机管理范围'), dark: this.dark })
          Column() {
            NavRow({
              label: Copy.tr('已加载插件清单'), glyph: 'PUZZLE', dark: this.dark, rowId: 'configuration.pluginInventory',
              showDivider: true,
              onTap: () => {
                this.requireActions().navigate(Page.PluginInventory);
              }
            })
            NavRow({
              label: Copy.tr('网页搜索与抓取'), glyph: 'GLOBE', dark: this.dark, rowId: 'configuration.webSearch',
              showDivider: true,
              onTap: () => {
                this.requireActions().navigate(Page.WebSearch);
              }
            })
            NavRow({
              label: Copy.tr('MCP 网络与密钥'), glyph: 'NETWORK', dark: this.dark, rowId: 'configuration.mcp',
              showDivider: true,
              onTap: () => {
                this.requireActions().navigate(Page.Mcp);
              }
            })
            NavRow({
              label: Copy.tr('命令'), glyph: 'TERMINAL', dark: this.dark, rowId: 'configuration.tools',
              showDivider: true,
              onTap: () => {
                this.requireActions().navigate(Page.Tools);
              }
            })
            NavRow({
              label: Copy.tr('Agent 预设'), glyph: 'PEOPLE', dark: this.dark, rowId: 'configuration.presets',
              showDivider: false,
              onTap: () => {
                this.requireActions().navigate(Page.AgentPresets);
              }
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })
          Text(Copy.tr('这一页显示引擎里真正装着的插件。保存后马上生效。只有真正加载起来的插件才会显示为已启用；没装的,这里不会假装它在跑。'))
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
export struct WorkbenchView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State command: string = '';
  @State actionError: string = '';
  @State statusLine: string = '';

  aboutToAppear(): void {
    this.command = this.snapshot.command;
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('WorkbenchView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private runCommand(): void {
    if (this.command.length === 0) {
      this.statusLine = Copy.tr('命令不能为空');
      return;
    }
    this.statusLine = '';
    this.requireActions().runCommand(this.command).then(() => {
      this.statusLine = Copy.tr('运行请求已提交');
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  private itemPath(item: NamedItem): string {
    if (item.detail.length > 0) { return item.detail; }
    if (item.subtitle.indexOf('/') >= 0) { return item.subtitle; }
    return item.id;
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('工作区'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'workbench.back',
        rightId: 'workbench.refresh',
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
          SectionLabel({ label: Copy.tr('文件'), dark: this.dark })
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取文件…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Text(Copy.tr('宿主没有报告本次回合写出的文件，无法判断有没有'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 24, right: 24 })
          } else if (this.snapshot.items.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('本次回合还没有写出文件'),
                hint: Copy.tr('工具写出文件后显示在这里'),
                glyph: 'DOCUMENT',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                Column() {
                Row() {
                  NativeIcon({ name: '文件', symbol: 'DOCUMENT', dark: this.dark, iconSize: 17, muted: true })
                    .width(26)
                    .height(17)
                  Column() {
                    Text(item.title.length > 0 ? item.title : item.id)
                      .fontSize(Metrics.bodySize)
                      .fontColor(this.pal().textPrimary)
                    if (item.subtitle.length > 0) {
                      Text(item.subtitle)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .maxLines(1)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .margin({ top: 2 })
                    }
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  Text(Copy.tr('打开'))
                    .fontSize(Metrics.secondarySize)
                    .fontColor(this.pal().sessionAccent)
                }
                .width('100%')
                .constraintSize({ minHeight: 52 })
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .id('workbench.file.' + item.id)
                .onClick(() => {
                  this.requireActions().openFile(this.itemPath(item)).catch((error: BusinessError) => {
                    this.actionError = error.message;
                  });
                })
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

          SectionLabel({ label: Copy.tr('命令'), dark: this.dark })
          Column() {
            TextInput({ text: this.command, placeholder: Copy.tr('在此运行命令') })
              .fontSize(Metrics.processSize)
              .fontColor(this.pal().textPrimary)
              .placeholderColor(this.pal().inputPlaceholder)
              .height(44)
              .backgroundColor(this.pal().sessionSelector)
              .borderRadius(12)
              .margin({ left: 16, right: 16, top: 12 })
              .id('workbench.command')
              .onChange((value: string) => {
                this.command = value;
              })
            Row() {
              PillButton({
                label: Copy.tr('运行'), dark: this.dark, compact: true, buttonId: 'workbench.run',
                onTap: () => {
                  this.runCommand();
                }
              })
              if (this.statusLine.length > 0) {
                Text(this.statusLine)
                  .fontSize(Metrics.captionSize)
                  .fontColor(this.pal().textSecondary)
                  .margin({ left: 10 })
              }
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 10, bottom: 14 })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16 })

          SectionLabel({ label: Copy.tr('输出'), dark: this.dark })
          if (this.snapshot.commandOutput.length > 0) {
            Text(this.snapshot.commandOutput)
              .fontSize(fontSizeValue(this.snapshot.settings, FontRole.Terminal))
              .fontFamily('monospace')
              .fontColor(this.pal().textPrimary)
              .lineHeight(fontSizeValue(this.snapshot.settings, FontRole.Terminal) + 7)
              .width('100%')
              .padding(14)
              .backgroundColor(this.pal().sessionCode)
              .borderRadius(14)
              .margin({ left: 16, right: 16 })
          } else {
            Text(Copy.tr('运行结果会显示在这里。'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 24, right: 24 })
          }
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
export struct ToolsView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ToolsView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('命令'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'tools.back',
        rightId: 'tools.refresh',
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
            LoadingBlock({ label: Copy.tr('正在读取命令…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('命令读取失败'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主没有报告 shell 能力，无法判断有没有'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('没有可显示的 shell 能力'),
                hint: Copy.tr('宿主明确报告了空的加速器与挂载列表'),
                glyph: 'TERMINAL',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                Column() {
                Row() {
                  Column() {
                    Text(item.title.length > 0 ? item.title : item.id)
                      .fontSize(Metrics.bodySize)
                      .fontColor(this.pal().textPrimary)
                    Row() {
                      Text(item.subtitle.length > 0 ? item.subtitle : Copy.unknown)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                      Text('·')
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .margin({ left: 4, right: 4 })
                      Text(item.enabled ? Copy.tr('可用') : Copy.tr('不可用'))
                        .fontSize(Metrics.captionSize)
                        .fontColor(item.enabled ? this.pal().sessionSuccess : this.pal().textSubtle)
                    }
                    .margin({ top: 2 })
                    if (item.detail.length > 0) {
                      Text(item.detail)
                        .fontSize(Metrics.captionSize)
                        .fontColor(this.pal().textSubtle)
                        .maxLines(3)
                        .textOverflow({ overflow: TextOverflow.Ellipsis })
                        .margin({ top: 2 })
                    }
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  Text(Copy.tr('只读'))
                    .fontSize(Metrics.captionSize)
                    .fontColor(this.pal().textSubtle)
                }
                .width('100%')
                .constraintSize({ minHeight: 54 })
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
          }
          Text(Copy.tr('只读展示宿主 hello 报告的 shell 能力；本页不启动终端，也不执行命令。'))
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
export struct ExportView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';
  @State statusLine: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ExportView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private preview(): string {
    if (this.snapshot.previewText.length > 0) { return this.snapshot.previewText; }
    return this.snapshot.detail;
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('导出诊断'),
        dark: this.dark,
        showBack: true,
        backId: 'export.back',
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
          SectionLabel({ label: Copy.tr('报告'), dark: this.dark })
          Column() {
            InfoRow({
              label: Copy.tr('状态'),
              value: this.snapshot.detail.length > 0 ? '宿主已报告内容' : Copy.unknown,
              dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('文件'),
              value: this.snapshot.previewPath.length > 0 ? this.snapshot.previewPath : '还没写',
              dark: this.dark, showDivider: true
            })
            InfoRow({
              label: Copy.tr('说明'),
              value: this.snapshot.detail.length > 0 ? '宿主已提供报告内容' : Copy.unknown,
              dark: this.dark, showDivider: false
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })
          Row() {
            PillButton({
              label: Copy.tr('导出当前会话'), dark: this.dark, buttonId: 'export.write',
              onTap: () => {
                this.statusLine = '';
                this.requireActions().exportSession().then(() => {
                  this.statusLine = Copy.tr('导出请求已提交');
                }).catch((error: BusinessError) => {
                  this.actionError = error.message;
                });
              }
            })
            if (this.statusLine.length > 0) {
              Text(this.statusLine)
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textSecondary)
                .margin({ left: 10 })
            }
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14 })

          SectionLabel({ label: Copy.tr('预览'), dark: this.dark })
          if (this.preview().length > 0) {
            Text(this.preview())
              .fontSize(13)
              .fontColor(this.pal().textPrimary)
              .lineHeight(20)
              .width('100%')
              .padding(14)
              .backgroundColor(this.pal().sessionCode)
              .borderRadius(14)
              .margin({ left: 16, right: 16 })
          } else {
            Column() {
              EmptyState({
                title: Copy.tr('还没有可导出的诊断报告'),
                hint: Copy.tr('宿主报告了空快照；不会用空报告冒充已测量'),
                glyph: 'DOCUMENT',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          }
          Text(Copy.tr('写好的文件放进仓库的验收目录，文件名用日期和设备名。当前宿主提供的是导出对话记录动作；诊断包写入尚未提供，本页不伪造文件。'))
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
export struct DeviceConnectionView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('DeviceConnectionView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: 'Device',
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'device.back',
        rightId: 'device.refresh',
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
          SectionLabel({ label: Copy.tr('真实连接设备'), dark: this.dark })
          Column() {
            EmptyState({
              title: Copy.tr('未接入设备连接器'),
              hint: Copy.tr('当前版本不会扫描、配对或伪造在线设备。'),
              glyph: 'DEVICE',
              dark: this.dark
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .margin({ left: 16, right: 16, top: 8 })

          SectionLabel({ label: Copy.tr('传输能力'), dark: this.dark })
          Column() {
            InfoRow({ label: Copy.tr('蓝牙连接器'), value: Copy.tr('未提供'), dark: this.dark, showDivider: true })
            InfoRow({ label: Copy.tr('局域网连接器'), value: Copy.tr('未提供'), dark: this.dark, showDivider: true })
            InfoRow({ label: Copy.tr('真实传输'), value: Copy.tr('尚未实现'), dark: this.dark, showDivider: false })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16 })
          Text(Copy.tr('真实传输尚未实现。此页只展示已注入连接器发布的状态，不会主动发起扫描。'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textSubtle)
            .lineHeight(17)
            .padding({ left: 24, right: 24, top: 8 })

          Column() {
            NavRow({
              label: Copy.tr('设备会话 · 只读监视'),
              value: '没有在线设备',
              glyph: 'DEVICE',
              dark: this.dark,
              rowEnabled: false,
              rowId: 'device.session',
              showDivider: false,
              onTap: () => {
                this.requireActions().navigate(Page.DeviceSession);
              }
            })
          }
          .backgroundColor(this.pal().surface)
          .borderRadius(Metrics.settingsCardRadius)
          .clip(true)
          .margin({ left: 16, right: 16, top: 12 })
          Text(Copy.tr('连接器未发布真实在线设备；打开设备会话需要宿主提供真实的 Session。'))
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
export struct DeviceSessionView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('DeviceSessionView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('设备会话 · 只读监视'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'deviceSession.back',
        rightId: 'deviceSession.refresh',
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
      Text(Copy.tr('只读监视，请在设备上处理输入与确认'))
        .fontSize(Metrics.captionSize)
        .fontColor(this.pal().textSubtle)
        .padding({ left: 24, right: 24, top: 4 })
      Scroll() {
        Column() {
          if (this.snapshot.rows.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('还没有交互记录'),
                hint: Copy.tr('设备与这个固定 Session 的真实交互会显示在这里。'),
                glyph: 'DEVICE',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16, top: 8 })
          } else {
            Column() {
              ForEach(this.snapshot.rows, (row: ChatRow, index: number) => {
                Column() {
                  Text(row.kind)
                    .fontSize(Metrics.captionSize)
                    .fontColor(this.pal().textSubtle)
                  Text(row.text.length > 0 ? row.text : row.detail)
                    .fontSize(Metrics.processSize)
                    .fontColor(this.pal().textPrimary)
                    .lineHeight(21)
                    .margin({ top: 2 })
                }
                .width('100%')
                .alignItems(HorizontalAlign.Start)
                .padding({ left: 16, right: 16, top: 10, bottom: 10 })
                if (index < this.snapshot.rows.length - 1) {
                  Hairline({ dark: this.dark })
                }
              }, (row: ChatRow) => JSON.stringify(row))
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16, top: 8 })
          }
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
export struct SessionArtifactsView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('SessionArtifactsView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: Copy.tr('本会话产物'),
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'artifacts.back',
        rightId: 'artifacts.refresh',
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
            LoadingBlock({ label: Copy.tr('正在读取已测量的写入…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('产物测量失败：宿主无法读取本会话的写入记录。'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('未测量：宿主没有报告本会话的写入范围，无法判断有没有产物。'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('已测量：本会话没有写入产物'),
                hint: Copy.tr('测量范围来自宿主已确认的写入记录；空列表表示该范围内没有真实写入，而不是目录扫描为空。'),
                glyph: 'DOCUMENT',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                Column() {
                Row() {
                  NativeIcon({ name: '产物', symbol: 'DOCUMENT', dark: this.dark, iconSize: 17, muted: true })
                    .width(26)
                    .height(17)
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
                  Text(Copy.tr('打开'))
                    .fontSize(Metrics.secondarySize)
                    .fontColor(this.pal().sessionAccent)
                }
                .width('100%')
                .constraintSize({ minHeight: 54 })
                .padding({ left: 16, right: 16, top: 8, bottom: 8 })
                .id('artifacts.item.' + item.id)
                .onClick(() => {
                  const path: string = item.detail.length > 0 ? item.detail : item.id;
                  this.requireActions().openFile(path).catch((error: BusinessError) => {
                    this.actionError = error.message;
                  });
                })
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
          Text(Copy.tr('只列出测量范围内真实写入 /dsh/workspace、当前仍存在的常规文件；已删除、工作区外或未测量的文件不会列出。'))
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
export struct ProbesView {
  @Prop snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  actions?: ShellActions;
  @State actionError: string = '';

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('ProbesView requires ShellActions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      NavHeader({
        title: 'Probes',
        dark: this.dark,
        showBack: true,
        rightText: Copy.tr('刷新'),
        backId: 'probes.back',
        rightId: 'probes.refresh',
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
      if (this.snapshot.debuggable && this.snapshot.engine === EnginePhase.Ready) {
        Row() {
          PillButton({
            label: Copy.tr('模拟运行视图退出'),
            dark: this.dark,
            kind: 'danger',
            buttonId: 'probes.rendererCrash',
            stretch: true,
            onTap: () => {
              this.requireActions().simulateRendererExit().catch((error: BusinessError) => {
                this.actionError = error.message;
              });
            }
          })
        }
        .padding({ left: 16, right: 16, top: 12 })
      }
      Scroll() {
        Column() {
          if (this.snapshot.itemsPhase === LoadPhase.Loading) {
            LoadingBlock({ label: Copy.tr('正在读取探针结果…'), dark: this.dark })
          } else if (this.snapshot.itemsPhase === LoadPhase.Failed) {
            Row() {
              NoticeBanner({ text: Copy.tr('探针结果读取失败'), dark: this.dark, kind: 'error' })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.itemsPhase === LoadPhase.Unknown) {
            Row() {
              NoticeBanner({
                text: Copy.tr('宿主没有报告探针结果，无法判断有没有跑过；不会显示 0 条'),
                dark: this.dark,
                kind: 'warning'
              })
            }
            .padding({ left: 16, right: 16 })
          } else if (this.snapshot.items.length === 0) {
            Column() {
              EmptyState({
                title: Copy.tr('这次启动还没有跑过探针'),
                hint: Copy.tr('探针只在运行后产生记录；设备上的数值在运行前保持未知'),
                glyph: 'WAVEFORM',
                dark: this.dark
              })
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              ForEach(this.snapshot.items, (item: NamedItem, index: number) => {
                Column() {
                  Row() {
                    Text(item.title.length > 0 ? item.title : item.id)
                      .fontSize(Metrics.bodySize)
                      .fontColor(this.pal().textPrimary)
                      .layoutWeight(1)
                  }
                  .width('100%')
                  if (item.subtitle.length > 0) {
                    Text(item.subtitle)
                      .fontSize(Metrics.captionSize)
                      .fontColor(this.pal().textSubtle)
                      .width('100%')
                      .margin({ top: 2 })
                  }
                  if (item.detail.length > 0) {
                    Text(item.detail)
                      .fontSize(Metrics.secondarySize)
                      .fontColor(this.pal().textSecondary)
                      .lineHeight(19)
                      .width('100%')
                      .margin({ top: 2 })
                  }
                }
                .width('100%')
                .alignItems(HorizontalAlign.Start)
                .padding({ left: 16, right: 16, top: 10, bottom: 10 })
                if (index < this.snapshot.items.length - 1) {
                  Hairline({ dark: this.dark })
                }
              }, (item: NamedItem) => JSON.stringify(item))
            }
            .backgroundColor(this.pal().surface)
            .borderRadius(Metrics.settingsCardRadius)
            .clip(true)
            .margin({ left: 16, right: 16 })
          }
          Text(Copy.tr('未测量的值显示为未知，不会当成 0。'))
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
