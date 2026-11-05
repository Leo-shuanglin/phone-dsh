// SPDX-License-Identifier: MIT
// Native session drawer: ~80% width, global entries, folders, sessions, search,
// per-session pin/archive/rename/delete and the settings bar.

import { ShellSnapshot, ShellActions, SessionRecord, Workspace, Page, EnginePhase, LoadPhase } from '../model/Contracts';
import { Palette, Metrics, Copy } from './Palette';
import { NoticeBanner } from './ShellSupport';
import { NativeIcon } from './NativeIcon';
import { BusinessError } from '@kit.BasicServicesKit';

@Component
export struct DrawerView {
  @Prop @Watch('onSnapshotUpdated') snapshot: ShellSnapshot = new ShellSnapshot();
  @Prop dark: boolean = false;
  @Prop fullScreen: boolean = false;
  actions?: ShellActions;
  onClose: () => void = () => {};
  @State searchOpen: boolean = false;
  @State searchText: string = '';
  @State collapsed: string[] = [];
  @State dialogKind: string = '';
  @State dialogTargetId: string = '';
  @State dialogValue: string = '';
  @State actionError: string = '';
  @State toast: string = '';

  onSnapshotUpdated(): void {
    if (this.snapshot.sessions.length === 0 && this.searchText.length > 0) {
      this.searchText = '';
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('DrawerView requires ShellActions');
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

  private isCollapsed(id: string): boolean {
    for (let i: number = 0; i < this.collapsed.length; i++) {
      if (this.collapsed[i] === id) {
        return true;
      }
    }
    return false;
  }

  private toggleFolder(id: string): void {
    const next: string[] = [];
    let found: boolean = false;
    for (let i: number = 0; i < this.collapsed.length; i++) {
      if (this.collapsed[i] === id) {
        found = true;
      } else {
        next.push(this.collapsed[i]);
      }
    }
    if (!found) {
      next.push(id);
    }
    this.collapsed = next;
  }

  private matchesSearch(session: SessionRecord): boolean {
    if (this.searchText.length === 0) {
      return true;
    }
    return session.title.toLowerCase().indexOf(this.searchText.toLowerCase()) >= 0;
  }

  private visibleSessions(): SessionRecord[] {
    const visible: SessionRecord[] = [];
    for (let i: number = 0; i < this.snapshot.sessions.length; i++) {
      const session: SessionRecord = this.snapshot.sessions[i];
      if (!session.archived && this.matchesSearch(session)) {
        visible.push(session);
      }
    }
    const sorted: SessionRecord[] = [];
    for (let i: number = 0; i < visible.length; i++) {
      if (visible[i].pinned) {
        sorted.push(visible[i]);
      }
    }
    for (let i: number = 0; i < visible.length; i++) {
      if (!visible[i].pinned) {
        sorted.push(visible[i]);
      }
    }
    return sorted;
  }

  private sessionsForFolder(folderId: string): SessionRecord[] {
    const all: SessionRecord[] = this.visibleSessions();
    const matched: SessionRecord[] = [];
    for (let i: number = 0; i < all.length; i++) {
      if (all[i].folderId === folderId) {
        matched.push(all[i]);
      }
    }
    return matched;
  }

  private ungroupedSessions(): SessionRecord[] {
    const all: SessionRecord[] = this.visibleSessions();
    const matched: SessionRecord[] = [];
    for (let i: number = 0; i < all.length; i++) {
      let known: boolean = false;
      for (let j: number = 0; j < this.snapshot.folders.length; j++) {
        if (this.snapshot.folders[j].id === all[i].folderId) {
          known = true;
        }
      }
      if (!known) {
        matched.push(all[i]);
      }
    }
    return matched;
  }

  private closeThenOpen(sessionId: string): void {
    this.onClose();
    this.run(this.requireActions().openSession(sessionId));
  }

  private newSession(folderId: string): void {
    if (this.snapshot.running) {
      return;
    }
    this.onClose();
    this.run(this.requireActions().newSession(folderId));
  }

  private openDialog(kind: string, targetId: string, value: string): void {
    this.dialogKind = kind;
    this.dialogTargetId = targetId;
    this.dialogValue = value;
  }

  private closeDialog(): void {
    this.dialogKind = '';
    this.dialogTargetId = '';
    this.dialogValue = '';
  }

  private dialogTitle(): string {
    if (this.dialogKind === 'folder-create') { return Copy.tr('新建文件夹'); }
    if (this.dialogKind === 'folder-rename') { return Copy.tr('重命名文件夹'); }
    if (this.dialogKind === 'folder-delete') { return Copy.tr('删除这个文件夹？'); }
    if (this.dialogKind === 'session-rename') { return Copy.tr('重命名会话'); }
    if (this.dialogKind === 'session-delete') { return Copy.tr('删除这个会话？'); }
    return '';
  }

  private dialogNeedsInput(): boolean {
    return this.dialogKind === 'folder-create' || this.dialogKind === 'folder-rename'
      || this.dialogKind === 'session-rename';
  }

  private dialogDetail(): string {
    if (this.dialogKind === 'folder-delete') {
      return Copy.tr('里面的会话会留着，只是不再放在这个文件夹里。');
    }
    if (this.dialogKind === 'session-delete') {
      return Copy.tr('删除后无法恢复');
    }
    if (this.dialogKind === 'folder-create') {
      return Copy.tr('会在工作区里新建同名文件夹，之后的会话和文件都可以放进去。');
    }
    return '';
  }

  private confirmDialog(): void {
    const actions: ShellActions = this.requireActions();
    if (this.dialogKind === 'folder-create') {
      if (this.dialogValue.length === 0) {
        return;
      }
      this.run(actions.createFolder(this.dialogValue));
    } else if (this.dialogKind === 'folder-rename') {
      if (this.dialogValue.length === 0) {
        return;
      }
      this.run(actions.renameFolder(this.dialogTargetId, this.dialogValue));
    } else if (this.dialogKind === 'folder-delete') {
      this.run(actions.deleteFolder(this.dialogTargetId));
    } else if (this.dialogKind === 'session-rename') {
      if (this.dialogValue.length === 0) {
        return;
      }
      this.run(actions.renameSession(this.dialogTargetId, this.dialogValue));
    } else if (this.dialogKind === 'session-delete') {
      this.run(actions.deleteSession(this.dialogTargetId));
    }
    this.closeDialog();
  }

  private archive(sessionId: string): void {
    this.requireActions().archiveSession(sessionId, true).then(() => {
      this.toast = Copy.tr('已归档，可在 设置 › 已归档会话 里找回');
    }).catch((error: BusinessError) => {
      this.actionError = error.message;
    });
  }

  @Builder
  DrawerFooter() {
    Row() {
      NativeIcon({ name: '设置', symbol: 'GEAR', dark: this.dark, iconSize: 18, muted: true })
        .width(28)
        .height(20)
      Text(Copy.tr('设置'))
        .fontSize(Metrics.bodySize)
        .fontColor(this.pal().textPrimary)
        .layoutWeight(1)
        .margin({ left: 8 })
      Row() {
        Text('●')
          .fontSize(9)
          .fontColor(this.engineColor())
          .accessibilityText(Copy.engineLabel(this.snapshot.engine))
        Text(Copy.engineLabel(this.snapshot.engine))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSecondary)
          .margin({ left: 5 })
        NativeIcon({ name: '进入设置', symbol: 'CHEVRON_RIGHT', dark: this.dark, iconSize: 16, muted: true })
          .width(16)
          .height(16)
          .margin({ left: 4 })
      }
    }
    .width('100%')
    .height(56)
    .padding({ left: 16, right: 16 })
    .border({ width: { top: Metrics.hairline }, color: this.pal().borderList })
    .id('drawer.settings')
    .onClick(() => {
      this.onClose();
      this.requireActions().navigate(Page.Settings);
    })
  }

  private engineColor(): string {
    if (this.snapshot.engine === EnginePhase.Ready) { return this.pal().success; }
    if (this.snapshot.engine === EnginePhase.Failed) { return this.pal().sessionDanger; }
    return this.pal().textSubtle;
  }

  @Builder
  DrawerPanel() {
    Column() {
      if (this.fullScreen) {
        Row() {
          NativeIcon({ name: '返回', symbol: 'CHEVRON_LEFT', dark: this.dark, iconSize: 22 })
            .width(Metrics.navButton)
            .height(Metrics.navButton)
            .accessibilityText(Copy.tr('返回'))
            .id('drawer.full.back')
            .onClick(() => {
              this.onClose();
            })
          Text(Copy.tr('会话'))
            .fontSize(Metrics.navTitleSize)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.pal().textPrimary)
            .layoutWeight(1)
            .margin({ left: 8 })
          NativeIcon({ name: '搜索会话', symbol: 'SEARCH', dark: this.dark, iconSize: 22 })
            .width(Metrics.navButton)
            .height(Metrics.navButton)
            .borderRadius(Metrics.navButton / 2)
            .backgroundColor(this.pal().surface)
            .accessibilityText(Copy.tr('搜索会话'))
            .id('drawer.search')
            .onClick(() => {
              this.searchOpen = !this.searchOpen;
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: Metrics.navTop + 4, bottom: 2 })
      }

      if (this.actionError.length > 0) {
        Row() {
          NoticeBanner({
            text: this.actionError,
            dark: this.dark,
            kind: 'error',
            showClose: true,
            bannerId: 'drawer.error',
            onClose: () => {
              this.actionError = '';
            }
          })
        }
        .margin({ left: 8, right: 8 })
      }
      if (this.toast.length > 0) {
        Row() {
          NoticeBanner({
            text: this.toast,
            dark: this.dark,
            kind: 'info',
            showClose: true,
            bannerId: 'drawer.toast',
            onClose: () => {
              this.toast = '';
            }
          })
        }
        .margin({ left: 8, right: 8 })
      }

      Scroll() {
        Column() {
          Row() {
            NativeIcon({ name: '新会话', symbol: 'NEW_CHAT', dark: this.dark, iconSize: 20 })
              .width(28)
              .height(20)
            Text(Copy.tr('新会话'))
              .fontSize(17)
              .fontColor(this.pal().textPrimary)
              .layoutWeight(1)
              .margin({ left: 12 })
            if (this.snapshot.running) {
              Text(Copy.tr('本轮结束后可用'))
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textSubtle)
            }
          }
          .width('100%')
          .height(Metrics.drawerRowHeight)
          .padding({ left: 8, right: 8 })
          .borderRadius(Metrics.drawerRowRadius)
          .opacity(this.snapshot.running ? 0.5 : 1)
          .id('drawer.newSession')
          .onClick(() => {
            this.newSession(this.snapshot.activeFolderId);
          })

          Row() {
            NativeIcon({ name: '定时任务', symbol: 'CLOCK', dark: this.dark, iconSize: 20 })
              .width(28)
              .height(20)
            Text(Copy.tr('定时任务'))
              .fontSize(17)
              .fontColor(this.pal().textPrimary)
              .layoutWeight(1)
              .margin({ left: 12 })
          }
          .width('100%')
          .height(Metrics.drawerRowHeight)
          .padding({ left: 8, right: 8 })
          .borderRadius(Metrics.drawerRowRadius)
          .id('drawer.automation')
          .onClick(() => {
            this.onClose();
            this.requireActions().navigate(Page.Automation);
          })

          Row() {
            Text(Copy.tr('文件夹'))
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSubtle)
              .layoutWeight(1)
            if (!this.fullScreen) {
              NativeIcon({ name: '搜索会话', symbol: 'SEARCH', dark: this.dark, iconSize: 20 })
                .width(36)
                .height(36)
                .borderRadius(18)
                .accessibilityText(Copy.tr('搜索会话'))
                .id('drawer.search')
                .onClick(() => {
                  this.searchOpen = !this.searchOpen;
                })
            }
            NativeIcon({ name: '添加工作文件夹', symbol: 'PLUS', dark: this.dark, iconSize: 20 })
              .width(36)
              .height(36)
              .borderRadius(18)
              .accessibilityText(Copy.tr('添加工作文件夹'))
              .id('drawer.folder.add')
              .onClick(() => {
                this.openDialog('folder-create', '', '');
              })
          }
          .width('100%')
          .height(Metrics.drawerFolderHeight)
          .padding({ left: 8, right: 4 })

          if (this.searchOpen) {
            TextInput({ text: this.searchText, placeholder: Copy.tr('搜索会话') })
              .fontSize(Metrics.processSize)
              .fontColor(this.pal().textPrimary)
              .placeholderColor(this.pal().inputPlaceholder)
              .height(40)
              .backgroundColor(this.pal().surface)
              .borderRadius(20)
              .padding({ left: 14, right: 14 })
              .margin({ left: 8, right: 8, bottom: 8 })
              .id('drawer.search.input')
              .onChange((value: string) => {
                this.searchText = value;
              })
          }

          if (this.snapshot.folders.length === 0) {
            Column() {
              Text(Copy.tr('还没有文件夹。点右上角的 + 新建一个，会话和文件都能放进去。'))
                .fontSize(Metrics.secondarySize)
                .fontColor(this.pal().textSubtle)
                .lineHeight(19)
                .textAlign(TextAlign.Center)
              Row() {
                Text(Copy.tr('新建工作文件夹'))
                  .fontSize(14)
                  .fontColor(this.pal().textPrimary)
                  .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                  .borderRadius(18)
                  .backgroundColor(this.pal().surface)
                  .id('drawer.folder.emptyAdd')
                  .onClick(() => {
                    this.openDialog('folder-create', '', '');
                  })
              }
              .margin({ top: 12 })
            }
            .width('100%')
            .padding({ top: 16, bottom: 16, left: 20, right: 20 })
            .alignItems(HorizontalAlign.Center)
          }

          ForEach(this.snapshot.folders, (folder: Workspace) => {
            Column() {
            FolderHeader({
              folder: folder,
              sessions: this.sessionsForFolder(folder.id).length,
              active: folder.id === this.snapshot.activeFolderId,
              collapsed: this.isCollapsed(folder.id),
              dark: this.dark,
              onToggle: () => {
                this.toggleFolder(folder.id);
              },
              onSelect: () => {
                this.run(this.requireActions().selectFolder(folder.id));
              },
              onNewSession: () => {
                this.newSession(folder.id);
              },
              onRename: () => {
                this.openDialog('folder-rename', folder.id, folder.name);
              },
              onDelete: () => {
                this.openDialog('folder-delete', folder.id, folder.name);
              },
              canDelete: folder.id !== this.snapshot.activeFolderId
            })
            if (!this.isCollapsed(folder.id)) {
              ForEach(this.sessionsForFolder(folder.id), (session: SessionRecord) => {
                DrawerSessionRow({
                  session: session,
                  active: session.id === this.snapshot.activeSessionId,
                  dark: this.dark,
                  onOpen: () => {
                    this.closeThenOpen(session.id);
                  },
                  onPin: () => {
                    this.run(this.requireActions().pinSession(session.id, !session.pinned));
                  },
                  onArchive: () => {
                    this.archive(session.id);
                  },
                  onRename: () => {
                    this.openDialog('session-rename', session.id, session.title);
                  },
                  onDelete: () => {
                    this.openDialog('session-delete', session.id, session.title);
                  }
                })
              }, (session: SessionRecord) => JSON.stringify([session.id, session.title, session.folderId, session.pinned, session.archived]))
              if (this.sessionsForFolder(folder.id).length === 0) {
                Text(this.searchText.length > 0 ? Copy.tr('没有匹配的会话') : Copy.tr('还没有会话'))
                  .fontSize(Metrics.secondarySize)
                  .fontColor(this.pal().textSubtle)
                  .padding({ left: 32, top: 8, bottom: 8 })
              }
            }
            }.width('100%')
          }, (folder: Workspace) => JSON.stringify(folder))

          if (this.ungroupedSessions().length > 0) {
            Text(Copy.tr('未分组'))
              .fontSize(Metrics.secondarySize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 8, top: 10, bottom: 4 })
            ForEach(this.ungroupedSessions(), (session: SessionRecord) => {
              DrawerSessionRow({
                session: session,
                active: session.id === this.snapshot.activeSessionId,
                dark: this.dark,
                onOpen: () => {
                  this.closeThenOpen(session.id);
                },
                onPin: () => {
                  this.run(this.requireActions().pinSession(session.id, !session.pinned));
                },
                onArchive: () => {
                  this.archive(session.id);
                },
                onRename: () => {
                  this.openDialog('session-rename', session.id, session.title);
                },
                onDelete: () => {
                  this.openDialog('session-delete', session.id, session.title);
                }
              })
            }, (session: SessionRecord) => JSON.stringify([session.id, session.title, session.folderId, session.pinned, session.archived]))
          }

          if (this.snapshot.sessionsPhase === LoadPhase.Loading) {
            Row() {
              LoadingProgress()
                .width(16)
                .height(16)
                .color(this.pal().textSecondary)
              Text(Copy.tr('正在读取会话…'))
                .fontSize(Metrics.captionSize)
                .fontColor(this.pal().textSubtle)
                .margin({ left: 8 })
            }
            .padding({ left: 12, top: 12, bottom: 12 })
          } else if (this.snapshot.sessionsPhase === LoadPhase.Failed) {
            NoticeBanner({ text: Copy.tr('会话列表加载失败'), dark: this.dark, kind: 'error' })
          } else if (this.snapshot.sessionsPhase === LoadPhase.Unknown) {
            Text(Copy.tr('宿主没有报告会话列表，无法判断有没有会话。'))
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .padding({ left: 12, top: 12, bottom: 12 })
          }

          Text('')
            .height(24)
        }
        .width('100%')
        .padding({ left: 8, right: 8, top: 4 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
      .align(Alignment.TopStart)

      this.DrawerFooter()
    }
    .width(this.fullScreen ? '100%' : 'calc(100% - 44vp)')
    .constraintSize({ maxWidth: this.fullScreen ? '100%' : 320 })
    .height('100%')
    .backgroundColor(this.pal().page)
    .borderRadius({ topRight: 22, bottomRight: 22 })
    .border({ width: { right: Metrics.hairline }, color: this.pal().borderList })
    .padding({ top: 8, bottom: 0 })
  }

  @Builder
  Dialog() {
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
              .width('100%')
              .margin({ top: 8 })
          }
          if (this.dialogNeedsInput()) {
            TextInput({ text: this.dialogValue, placeholder: Copy.tr('名称') })
              .fontSize(Metrics.processSize)
              .fontColor(this.pal().textPrimary)
              .placeholderColor(this.pal().inputPlaceholder)
              .height(44)
              .backgroundColor(this.pal().sessionSelector)
              .borderRadius(12)
              .margin({ top: 12 })
              .id('drawer.dialog.input')
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
              .id('drawer.dialog.cancel')
              .onClick(() => {
                this.closeDialog();
              })
            Text(this.dialogKind === 'folder-delete' || this.dialogKind === 'session-delete' ? Copy.tr('删除') : Copy.tr('保存'))
              .fontSize(15)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.pal().primaryButtonInk)
              .height(40)
              .padding({ left: 18, right: 18 })
              .borderRadius(20)
              .backgroundColor(this.dialogKind === 'folder-delete' || this.dialogKind === 'session-delete'
                ? this.pal().pageDanger : this.pal().primaryButton)
              .margin({ left: 8 })
              .id('drawer.dialog.confirm')
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
    Stack({ alignContent: Alignment.TopStart }) {
      this.DrawerPanel()
      this.Dialog()
    }
    .width('100%')
    .height('100%')
  }
}

@Component
struct FolderHeader {
  @Prop folder: Workspace = new Workspace();
  @Prop sessions: number = 0;
  @Prop active: boolean = false;
  @Prop collapsed: boolean = false;
  @Prop canDelete: boolean = true;
  @Prop dark: boolean = false;
  onToggle: () => void = () => {};
  onSelect: () => void = () => {};
  onNewSession: () => void = () => {};
  onRename: () => void = () => {};
  onDelete: () => void = () => {};
  @State menuOpen: boolean = false;

  private pal(): Palette {
    return new Palette(this.dark);
  }

  @Builder
  MenuRow(label: string, id: string, enabled: boolean, dangerous: boolean) {
    Row() {
      Text(label)
        .fontSize(Metrics.processSize)
        .fontColor(dangerous ? this.pal().pageDanger
          : (enabled ? this.pal().textPrimary : this.pal().textTertiary))
        .layoutWeight(1)
    }
    .width('100%')
    .height(40)
    .padding({ left: 12, right: 12 })
    .opacity(enabled ? 1 : 0.5)
    .id(id)
    .onClick(() => {
      if (!enabled) {
        return;
      }
      this.menuOpen = false;
      if (id.endsWith('.newSession')) {
        this.onNewSession();
      } else if (id.endsWith('.rename')) {
        this.onRename();
      } else if (id.endsWith('.delete')) {
        this.onDelete();
      }
    })
  }

  build() {
    Column() {
      Row() {
        NativeIcon({
          name: this.collapsed ? '展开工作文件夹的会话' : '收起工作文件夹的会话',
          symbol: this.collapsed ? 'CHEVRON_RIGHT' : 'CHEVRON_DOWN',
          dark: this.dark,
          iconSize: 12,
          muted: true
        })
          .width(20)
          .height(20)
          .accessibilityText(this.collapsed ? Copy.tr('展开工作文件夹的会话') : Copy.tr('收起工作文件夹的会话'))
          .id('drawer.folder.toggle.' + this.folder.id)
          .onClick(() => {
            this.onToggle();
          })
        NativeIcon({ name: '文件夹', symbol: 'FOLDER', dark: this.dark, iconSize: 17 })
          .width(24)
          .height(17)
        Text(this.folder.name.length > 0 ? this.folder.name : Copy.tr('未命名文件夹'))
          .fontSize(Metrics.processSize)
          .fontWeight(this.active ? FontWeight.Medium : FontWeight.Normal)
          .fontColor(this.active ? this.pal().textPrimary : this.pal().textSecondary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
          .margin({ left: 4 })
          .id('drawer.folder.' + this.folder.id)
          .onClick(() => {
            this.onSelect();
          })
        Text(this.sessions.toString())
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
          .margin({ right: 4 })
        NativeIcon({ name: '在这里新建会话', symbol: 'PLUS', dark: this.dark, iconSize: 18 })
          .width(32)
          .height(36)
          .accessibilityText(Copy.tr('在这里新建会话'))
          .id('drawer.folder.newSession.' + this.folder.id)
          .onClick(() => {
            this.onNewSession();
          })
        NativeIcon({ name: '工作文件夹的选项', symbol: 'MORE', dark: this.dark, iconSize: 16, muted: true })
          .width(32)
          .height(36)
          .accessibilityText(Copy.tr('工作文件夹的选项'))
          .id('drawer.folder.menu.' + this.folder.id)
          .onClick(() => {
            this.menuOpen = !this.menuOpen;
          })
      }
      .width('100%')
      .height(Metrics.drawerFolderHeight)
      .padding({ left: 4, right: 2 })
      if (this.menuOpen) {
        Column() {
          this.MenuRow(Copy.tr('在这里新建会话'), 'drawer.folder.menu.' + this.folder.id + '.newSession', true, false)
          this.MenuRow(Copy.tr('重命名文件夹'), 'drawer.folder.menu.' + this.folder.id + '.rename', true, false)
          this.MenuRow(
            this.canDelete ? '删除文件夹' : '当前文件夹不能删除',
            'drawer.folder.menu.' + this.folder.id + '.delete',
            this.canDelete,
            true
          )
        }
        .width('100%')
        .backgroundColor(this.pal().surface)
        .borderRadius(12)
        .padding({ top: 2, bottom: 2 })
        .margin({ bottom: 4 })
      }
    }
    .width('100%')
  }
}

@Component
struct DrawerSessionRow {
  @Prop session: SessionRecord = new SessionRecord();
  @Prop active: boolean = false;
  @Prop dark: boolean = false;
  onOpen: () => void = () => {};
  onPin: () => void = () => {};
  onArchive: () => void = () => {};
  onRename: () => void = () => {};
  onDelete: () => void = () => {};
  @State menuOpen: boolean = false;

  private pal(): Palette {
    return new Palette(this.dark);
  }

  @Builder
  ActionRow(label: string, id: string, dangerous: boolean) {
    Row() {
      Text(label)
        .fontSize(Metrics.processSize)
        .fontColor(dangerous ? this.pal().pageDanger : this.pal().textPrimary)
        .layoutWeight(1)
    }
    .width('100%')
    .height(38)
    .padding({ left: 12, right: 12 })
    .id(id)
    .onClick(() => {
      this.menuOpen = false;
      if (id.endsWith('.pin')) {
        this.onPin();
      } else if (id.endsWith('.archive')) {
        this.onArchive();
      } else if (id.endsWith('.rename')) {
        this.onRename();
      } else if (id.endsWith('.delete')) {
        this.onDelete();
      }
    })
  }

  build() {
    Column() {
      Row() {
        Row() {
          Text(this.session.title.length > 0 && this.session.title !== '新会话' ?
            this.session.title : Copy.tr('新会话'))
            .fontSize(Metrics.bodySize)
            .fontColor(this.active ? this.pal().textPrimary : this.pal().textSecondary)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
          if (this.session.pinned) {
            Text('⌖')
              .fontSize(13)
              .fontColor(this.pal().sessionAccent)
              .margin({ left: 4 })
              .accessibilityText(Copy.tr('已置顶'))
          }
          if (this.active) {
            NativeIcon({ name: '当前会话', symbol: 'CHECK', dark: this.dark, iconSize: 15 })
              .width(15)
              .height(15)
              .margin({ left: 4 })
          }
        }
        .layoutWeight(1)
        .height(44)
        .padding({ left: 20, right: 4 })
        .id('drawer.session.' + this.session.id)
        .onClick(() => {
          this.onOpen();
        })
        NativeIcon({ name: '会话选项', symbol: 'MORE', dark: this.dark, iconSize: 15, muted: true })
          .width(34)
          .height(44)
          .accessibilityText(Copy.tr('会话选项'))
          .id('drawer.session.menu.' + this.session.id)
          .onClick(() => {
            this.menuOpen = !this.menuOpen;
          })
      }
      .width('100%')
      .height(44)
      .backgroundColor(this.active ? this.pal().surface : '#00000000')
      .borderRadius(Metrics.drawerRowRadius)
      .margin({ bottom: 2 })
      if (this.menuOpen) {
        Column() {
          this.ActionRow(this.session.pinned ? '取消置顶' : '置顶', 'drawer.session.menu.' + this.session.id + '.pin', false)
          this.ActionRow(Copy.tr('归档'), 'drawer.session.menu.' + this.session.id + '.archive', false)
          this.ActionRow(Copy.tr('重命名'), 'drawer.session.menu.' + this.session.id + '.rename', false)
          this.ActionRow(Copy.tr('删除'), 'drawer.session.menu.' + this.session.id + '.delete', true)
        }
        .width('100%')
        .backgroundColor(this.pal().surface)
        .borderRadius(12)
        .padding({ top: 2, bottom: 2 })
        .margin({ bottom: 4 })
      }
    }
    .width('100%')
  }
}
