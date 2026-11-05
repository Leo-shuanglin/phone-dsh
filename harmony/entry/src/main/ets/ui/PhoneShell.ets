// SPDX-License-Identifier: MIT
import { ImageLoadControl } from '../model/ImageLoadControl';
// PhoneShell is the single native product route surface. It owns no domain
// state: the coordinator supplies a ShellSnapshot and promised ShellActions.

import { ShellSnapshot, ShellActions, Page, FontRole,
  fontSizeValue
} from '../model/Contracts';
import { Palette } from './Palette';
import { ChatView } from './ChatView';
import { DrawerView } from './DrawerView';
import {
  SettingsIndexView, ModelProviderView, WebSearchView, McpView, McpEditorView,
  RuntimeView, EgressView
} from './SettingsView';
import {
  ChatPreferencesView, FontSizeView, SettingsDocumentView, ShortcutsView,
  ArchivedSessionsView, AboutView, LicensesView, PhonePermissionsView,
  AgentPresetsView, PluginInventoryView
} from './SettingsMoreView';
import {
  FilesView, PreviewView, WorkspaceFoldersView, AutomationView, ConfigurationView,
  WorkbenchView, ToolsView, ExportView, DeviceConnectionView, DeviceSessionView,
  SessionArtifactsView, ProbesView
} from './DetailPages';

@Component
export struct PhoneShell {
  @Prop @Watch('onSnapshotChanged') snapshot: ShellSnapshot = new ShellSnapshot();
  actions?: ShellActions;
  registerBackHandler?: (handler: () => boolean) => void;
  private chatBack: (() => boolean) | undefined = undefined;
  @State drawerOpen: boolean = false;

  aboutToAppear(): void {
    this.requireActions();
    if (this.registerBackHandler !== undefined) {
      this.registerBackHandler((): boolean => {
        if (this.drawerOpen) { this.drawerOpen = false; return true; }
        return this.chatBack !== undefined && this.chatBack();
      });
    }
  }

  onSnapshotChanged(): void {
    if (this.snapshot.page !== Page.Chat) {
      this.drawerOpen = false;
    }
  }

  private requireActions(): ShellActions {
    const actions: ShellActions | undefined = this.actions;
    if (actions === undefined) {
      throw new Error('PhoneShell requires ShellActions; the coordinator must pass both snapshot and actions');
    }
    return actions;
  }

  private pal(): Palette {
    return new Palette(this.snapshot.dark);
  }

  @Builder
  PageContent() {
    if (this.snapshot.page === Page.Chat) {
      ChatView({
        snapshot: this.snapshot,
        dark: this.snapshot.dark,
        bodyFontSize: fontSizeValue(this.snapshot.settings, FontRole.Text),
        codeFontSize: fontSizeValue(this.snapshot.settings, FontRole.Code),
        terminalFontSize: fontSizeValue(this.snapshot.settings, FontRole.Terminal),
        loadImage: (source: string, control?: ImageLoadControl) => this.requireActions().loadImage(source, control),
        onOpenResource: (source: string) => {
          this.requireActions().openResource(source).catch((): void => {
          });
        },
        actions: this.actions,
        registerBackHandler: (handler: () => boolean): void => { this.chatBack = handler; },
        onOpenDrawer: () => {
          this.drawerOpen = true;
        }
      })
    } else if (this.snapshot.page === Page.Sessions) {
      DrawerView({
        snapshot: this.snapshot,
        dark: this.snapshot.dark,
        actions: this.actions,
        fullScreen: true,
        onClose: () => {
          this.requireActions().back();
        }
      })
    } else if (this.snapshot.page === Page.WorkspaceFolders) {
      WorkspaceFoldersView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Files) {
      FilesView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Automation) {
      AutomationView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Configuration) {
      ConfigurationView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Settings) {
      SettingsIndexView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.ModelProvider) {
      ModelProviderView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.WebSearch) {
      WebSearchView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Mcp) {
      McpView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.McpEditor) {
      McpEditorView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Runtime) {
      RuntimeView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Egress) {
      EgressView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Workbench) {
      WorkbenchView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Tools) {
      ToolsView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Export) {
      ExportView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.DeviceConnection) {
      DeviceConnectionView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.DeviceSession) {
      DeviceSessionView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.SessionArtifacts) {
      SessionArtifactsView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.AgentPresets) {
      AgentPresetsView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.PluginInventory) {
      PluginInventoryView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.PhonePermissions) {
      PhonePermissionsView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.ChatPreferences) {
      ChatPreferencesView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.SettingsDocument) {
      SettingsDocumentView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Shortcuts) {
      ShortcutsView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.FontSize) {
      FontSizeView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.ArchivedSessions) {
      ArchivedSessionsView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.About) {
      AboutView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Licenses) {
      LicensesView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Preview) {
      PreviewView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    } else if (this.snapshot.page === Page.Probes) {
      ProbesView({ snapshot: this.snapshot, dark: this.snapshot.dark, actions: this.actions })
    }
  }

  @Builder
  DrawerOverlay() {
    if (this.drawerOpen && this.snapshot.page === Page.Chat) {
      Stack({ alignContent: Alignment.TopStart }) {
        Text('')
          .width('100%')
          .height('100%')
          .backgroundColor(this.pal().scrim)
          .id('drawer.scrim')
          .onClick(() => {
            this.drawerOpen = false;
          })
        DrawerView({
          snapshot: this.snapshot,
          dark: this.snapshot.dark,
          actions: this.actions,
          fullScreen: false,
          onClose: () => {
            this.drawerOpen = false;
          }
        })
      }
      .width('100%')
      .height('100%')
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Column() {
        this.PageContent()
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.pal().page)

      this.DrawerOverlay()
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.pal().page)
  }
}
