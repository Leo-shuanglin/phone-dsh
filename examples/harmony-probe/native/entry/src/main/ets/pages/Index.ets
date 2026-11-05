// SPDX-License-Identifier: MIT
import { webview } from '@kit.ArkWeb';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x1A20;
const TAG: string = 'PhoneDSHProbe';
const MAX_CONSOLE_CHARS: number = 400;
const MAX_STATUS_CHARS: number = 220;
const STATUS_INFO_COLOR: string = '#9CC2FF';
const STATUS_ERROR_COLOR: string = '#FF7B72';

function clip(value: string, max: number): string {
  if (value.length <= max) {
    return value;
  }
  return value.slice(0, max) + '...';
}

@Entry
@Component
struct Index {
  controller: webview.WebviewController = new webview.WebviewController();
  @State status: string = 'Connecting to Mac probe host through hdc reverse port 18765';
  @State statusColor: string = STATUS_INFO_COLOR;

  private setStatus(text: string, color: string): void {
    this.status = clip(text, MAX_STATUS_CHARS);
    this.statusColor = color;
  }

  build() {
    Column() {
      Text('PhoneDSH ArkWeb Probe')
        .width('100%')
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .fontColor('#E6EDF3')
        .backgroundColor('#0B1220')
        .padding({ left: 16, right: 16, top: 10, bottom: 4 })

      Text(this.status)
        .width('100%')
        .fontSize(12)
        .fontColor(this.statusColor)
        .backgroundColor('#0B1220')
        .padding({ left: 16, right: 16, top: 2, bottom: 8 })
        .maxLines(3)
        .textOverflow({ overflow: TextOverflow.Ellipsis })

      Web({ src: 'http://127.0.0.1:18765/probe/index.html?autorun=1', controller: this.controller })
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .onConsole((event) => {
          if (event) {
            const line: string = `[console] ${event.message.getSourceId()}:${event.message.getLineNumber()} ${event.message.getMessage()}`;
            hilog.info(DOMAIN, TAG, '%{public}s', clip(line, MAX_CONSOLE_CHARS));
          }
          return false;
        })
        .onPageBegin((event) => {
          if (event) {
            hilog.info(DOMAIN, TAG, 'page begin: %{public}s', clip(event.url, MAX_CONSOLE_CHARS));
            this.setStatus(`Loading ${event.url}`, STATUS_INFO_COLOR);
          }
        })
        .onPageEnd((event) => {
          if (event) {
            hilog.info(DOMAIN, TAG, 'page end: %{public}s', clip(event.url, MAX_CONSOLE_CHARS));
            if (this.statusColor !== STATUS_ERROR_COLOR) {
              this.setStatus(`Page load event received (Worker status is NOT asserted natively): ${event.url}`, STATUS_INFO_COLOR);
            }
          }
        })
        .onErrorReceive((event) => {
          if (event) {
            const detail: string = `code=${event.error.getErrorCode()} ${event.error.getErrorInfo()}`;
            hilog.error(DOMAIN, TAG, 'page error: %{public}s', clip(detail, MAX_CONSOLE_CHARS));
            this.setStatus(`Page load error: ${detail}`, STATUS_ERROR_COLOR);
          }
        })
        .onHttpErrorReceive((event) => {
          if (event) {
            const detail: string = `HTTP ${event.response.getResponseCode()} ${event.response.getReasonMessage()}`;
            hilog.error(DOMAIN, TAG, 'http error: %{public}s', clip(detail, MAX_CONSOLE_CHARS));
            this.setStatus(`Page HTTP error: ${detail}`, STATUS_ERROR_COLOR);
          }
        })
        .width('100%')
        .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#0B1220')
  }
}
