// SPDX-License-Identifier: MIT
import { common } from '@kit.AbilityKit';
import { webview } from '@kit.ArkWeb';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { inputMethod } from '@kit.IMEKit';
import { KeyboardAvoidMode } from '@kit.ArkUI';
import { ShellSnapshot, Page } from '../model/Contracts';
import { HostService } from '../host/HostService';
import { PhoneShell } from '../ui/PhoneShell';

@Entry
@Component
struct Index {
  @State snapshot: ShellSnapshot = new ShellSnapshot();
  @StorageProp('pdshSystemDark') @Watch('onSystemAppearanceChanged') systemDark: boolean = false;
  @StorageProp('pdshKeyboardHeight') keyboardHeight: number = 0;
  @StorageProp('pdshLocale') locale: string = 'zh';
  private shellBack: (() => boolean) | undefined = undefined;
  private service: HostService = new HostService();
  private controller: webview.WebviewController = new webview.WebviewController();

  aboutToAppear(): void {
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.service.onChange = (state: ShellSnapshot): void => { this.snapshot = state; };
    this.service.setSystemDark(this.systemDark);
    this.service.attach(this.controller);
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.service.initialize(context).catch((error: Error): void => {
      this.service.pageFailed(error.message);
    });
  }

  onSystemAppearanceChanged(): void {
    this.service.setSystemDark(this.systemDark);
  }

  aboutToDisappear(): void {
    this.service.dispose();
  }

  onBackPress(): boolean {
    if (this.keyboardHeight > 0) {
      this.getUIContext().getFocusController().clearFocus();
      inputMethod.getController().hideTextInput().catch((): void => {
        hilog.warn(0x1A21, 'PhoneDSH', 'Keyboard dismissal was refused');
      });
      return true;
    }
    if (this.shellBack !== undefined && this.shellBack()) return true;
    if (this.snapshot.page !== Page.Chat) {
      this.service.back();
      return true;
    }
    return false;
  }

  build() {
    Stack() {
      if (this.snapshot.runtimeUrl.length > 0) {
        Web({ src: this.snapshot.runtimeUrl, controller: this.controller })
          .javaScriptAccess(true)
          .domStorageAccess(true)
          .javaScriptProxy({ object: this.service.bridge, name: 'PdshNative', methodList: ['postMessage'], controller: this.controller })
          .onPageEnd((): void => { this.service.pageLoaded(); })
          .onErrorReceive((event): void => {
            if (event && event.request.isMainFrame()) this.service.pageFailed(event.error.getErrorInfo());
          })
          .onHttpErrorReceive((event): void => {
            if (event && event.request.isMainFrame()) this.service.pageFailed('本地运行时页面 HTTP ' + event.response.getResponseCode());
          })
          .onRenderExited((event): void => {
            this.service.pageFailed('运行时渲染进程已退出（' + event.renderExitReason.toString() + '）；可重新启动引擎恢复。');
          })
          .onConsole((event): boolean => {
            if (event) {
              const message = event.message.getMessage();
              if (message.startsWith('XL_DSH_READY')) hilog.info(0x1A21, 'PhoneDSH', 'Worker ready');
            }
            return true;
          })
          .width(1)
          .height(1)
          .opacity(0.01)
          .hitTestBehavior(HitTestMode.None)
      }
      ForEach([this.locale], (locale: string) => {
        PhoneShell({ snapshot: this.snapshot, actions: this.service,
          registerBackHandler: (handler: () => boolean): void => { this.shellBack = handler; } })
          .width('100%')
          .height('100%')
      }, (locale: string) => locale)
    }
    .width('100%')
    .height('100%')
  }
}
