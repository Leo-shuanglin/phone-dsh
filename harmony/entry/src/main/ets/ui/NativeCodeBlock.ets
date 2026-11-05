// SPDX-License-Identifier: MIT
// Native ArkUI code card, ported from the frozen Android CodeBlock.kt.
//
// Authority (same baseline as android/lib/.../surface/CodeBlock.kt):
//   chrome        radius 12, 0.5 border using colors.sessionBorderL2, 2 vp outer inset
//   header strip  34 vp tall, colors.sessionCodeBanner, language + wrap + copy
//   copy          min 44 x 28, 13 vp glyph, 5 vp gap, 11 vp label, 已复制 for 1 s
//   body          colors.sessionCode, mono 13 vp, line height navSubtitle 14.5 + 5
//   long block    > 24 lines or > 2400 chars: max 300 vp, 展开全部 N 行 / 收起 tail
//
// The card receives only language/body/dark. It reuses highlightCode() from
// model/CodeHighlight.ets and copies the untouched body string through the
// system pasteboard. The source text is never truncated or line-clamped.

import { pasteboard, BusinessError } from '@kit.BasicServicesKit';
import { CodeInk, CodeRun, highlightCode } from '../model/CodeHighlight';
import { Copy, Metrics, Palette } from './Palette';
import { NativeIcon } from './NativeIcon';

/** `Components.swift:156-158`: long is > 24 lines or > 2400 characters. */
const CODE_LONG_LINE_COUNT: number = 24;
const CODE_LONG_CHAR_COUNT: number = 2400;

/** `Components.swift:207`: the collapsed code preview's own 300 pt cap. */
const CODE_COLLAPSED_MAX_HEIGHT: number = 300;

/** `Components.swift:181`: 已复制 holds for one second, then reverts. */
const CODE_COPIED_HOLD_MS: number = 1000;

/** Source-owned code metrics that are not shared UI tokens. */
const CODE_SOURCE_FONT_SIZE: number = 13;
const CODE_LINE_HEIGHT: number = 19.5;
const CODE_HEADER_HEIGHT: number = 34;
const CODE_HEADER_INSET_H: number = 12;
const CODE_HEADER_GAP: number = 8;
const CODE_BLOCK_RADIUS: number = 12;
const CODE_BLOCK_OUTER_INSET_V: number = 2;
const CODE_BLOCK_BODY_INSET: number = 12;
const CODE_WRAP_CONTROL_SIZE: number = 28;
const CODE_WRAP_ICON_SIZE: number = 11;
const CODE_COPY_ICON_SIZE: number = 13;
const CODE_COPY_GAP: number = 5;
const CODE_ACTION_LABEL_SIZE: number = 11;
const CODE_COPY_MIN_WIDTH: number = 44;
const CODE_COPY_MIN_HEIGHT: number = 28;
const CODE_EXPAND_HEIGHT: number = 32;
const CODE_EXPAND_LABEL_SIZE: number = 12;

@Component
export struct NativeCodeBlock {
  @Prop language: string = '';
  @Prop @Watch('onBodyChanged') body: string = '';
  @Prop dark: boolean = false;

  @State private wrapped: boolean = true;
  @State private copied: boolean = false;
  @State private expanded: boolean = false;
  @State private lineCount: number = 1;
  @State private runs: CodeRun[] = [];

  private copyTimer: number = -1;
  private disposed: boolean = false;

  aboutToAppear(): void {
    this.disposed = false;
    this.refreshBody();
  }

  aboutToDisappear(): void {
    this.disposed = true;
    this.clearCopyTimer();
  }

  onBodyChanged(): void {
    this.expanded = false;
    this.copied = false;
    this.clearCopyTimer();
    this.refreshBody();
  }

  private refreshBody(): void {
    this.lineCount = this.body.split('\n').length;
    this.runs = highlightCode(this.body);
  }

  private clearCopyTimer(): void {
    if (this.copyTimer >= 0) {
      clearTimeout(this.copyTimer);
      this.copyTimer = -1;
    }
  }

  private isLong(): boolean {
    return this.lineCount > CODE_LONG_LINE_COUNT || this.body.length > CODE_LONG_CHAR_COUNT;
  }

  private collapsible(): boolean {
    return this.isLong() && !this.expanded;
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private inkColor(ink: CodeInk): string {
    switch (ink) {
      case 'string': return this.pal().sessionSuccess;
      case 'comment': return this.pal().sessionCaption;
      case 'number': return this.pal().sessionWarning;
      case 'keyword': return this.pal().sessionAccent;
      default: return this.pal().sessionSecondary;
    }
  }

  private copyLabel(): string {
    return this.copied ? Copy.tr('已复制') : Copy.tr('复制');
  }

  private wrapActionLabel(): string {
    return this.wrapped ? Copy.tr('关闭自动换行') : Copy.tr('自动换行');
  }

  private expandLabel(): string {
    if (this.expanded) {
      return Copy.tr('收起');
    }
    return Copy.fill('展开全部 {0} 行', [this.lineCount.toString()]);
  }

  private copyBody(): void {
    const body: string = this.body;
    const data: pasteboard.PasteData =
      pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, body);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      if (this.disposed || this.body !== body) {
        return;
      }
      this.copied = true;
      this.clearCopyTimer();
      this.copyTimer = setTimeout((): void => {
        this.copyTimer = -1;
        this.copied = false;
      }, CODE_COPIED_HOLD_MS);
    }).catch((error: BusinessError) => {
      if (this.disposed) {
        return;
      }
      this.copied = false;
      this.clearCopyTimer();
    });
  }

  @Builder
  CodeHeader() {
    Row({ space: CODE_HEADER_GAP }) {
      Text(this.language)
        .fontSize(CODE_ACTION_LABEL_SIZE)
        .fontColor(this.pal().sessionSecondary)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .textAlign(TextAlign.Start)
        .layoutWeight(1)
      Row() {
        NativeIcon({
          symbol: this.wrapped ? 'TEXT_ALIGN_LEFT' : 'ARROW_LEFT_RIGHT',
          dark: this.dark,
          iconSize: CODE_WRAP_ICON_SIZE,
          inkRole: 'secondary'
        })
      }
      .width(CODE_WRAP_CONTROL_SIZE)
      .height(CODE_WRAP_CONTROL_SIZE)
      .justifyContent(FlexAlign.Center)
      .onClick(() => {
        this.wrapped = !this.wrapped;
      })
      .accessibilityText(this.wrapActionLabel())
      .id('markdown.code.wrap')
      Row({ space: CODE_COPY_GAP }) {
        NativeIcon({
          symbol: this.copied ? 'CHECK' : 'COPY',
          dark: this.dark,
          iconSize: CODE_COPY_ICON_SIZE,
          inkRole: 'secondary'
        })
        Text(this.copyLabel())
          .fontSize(CODE_ACTION_LABEL_SIZE)
          .fontColor(this.pal().sessionSecondary)
      }
      .constraintSize({ minWidth: CODE_COPY_MIN_WIDTH, minHeight: CODE_COPY_MIN_HEIGHT })
      .justifyContent(FlexAlign.Center)
      .onClick(() => {
        this.copyBody();
      })
      .accessibilityText(this.copyLabel())
      .id('markdown.code.copy')
    }
    .width('100%')
    .height(CODE_HEADER_HEIGHT)
    .padding({ left: CODE_HEADER_INSET_H, right: CODE_HEADER_INSET_H })
    .backgroundColor(this.pal().sessionCodeBanner)
    .borderRadius({ topLeft: CODE_BLOCK_RADIUS, topRight: CODE_BLOCK_RADIUS })
  }

  @Builder
  CodeText() {
    Text() {
      ForEach(this.runs, (run: CodeRun) => {
        Span(run.text)
          .fontColor(this.inkColor(run.ink))
      }, (run: CodeRun, index: number): string =>
        index.toString() + '|' + run.ink + '|' + run.text)
    }
    .fontSize(CODE_SOURCE_FONT_SIZE)
    .fontFamily('monospace')
    .fontColor(this.pal().sessionSecondary)
    .lineHeight(CODE_LINE_HEIGHT)
    .textAlign(TextAlign.Start)
    .padding(CODE_BLOCK_BODY_INSET)
  }

  @Builder
  CodeBody() {
    if (this.wrapped) {
      this.CodeText()
    } else {
      Scroll() {
        this.CodeText()
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
      .align(Alignment.TopStart)
    }
  }

  @Builder
  CodeViewport() {
    if (this.collapsible()) {
      Scroll() {
        this.CodeBody()
      }
      .scrollable(ScrollDirection.Vertical)
      .scrollBar(BarState.Off)
      .width('100%')
      .constraintSize({ maxHeight: CODE_COLLAPSED_MAX_HEIGHT })
      .align(Alignment.TopStart)
    } else {
      this.CodeBody()
    }
  }

  @Builder
  CodeFooter() {
    Row() {
      Text(this.expandLabel())
        .fontSize(CODE_EXPAND_LABEL_SIZE)
        .fontColor(this.pal().sessionSecondary)
    }
    .width('100%')
    .height(CODE_EXPAND_HEIGHT)
    .justifyContent(FlexAlign.Center)
    .backgroundColor(this.pal().sessionCode)
    .onClick(() => {
      this.expanded = !this.expanded;
    })
    .accessibilityText(this.expandLabel())
    .id('markdown.code.expand')
  }

  build() {
    Column() {
      this.CodeHeader()
      this.CodeViewport()
      if (this.isLong()) {
        this.CodeFooter()
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .backgroundColor(this.pal().sessionCode)
    .borderRadius(CODE_BLOCK_RADIUS)
    .border({ width: Metrics.hairline, color: this.pal().borderL2 })
    .clip(true)
    .margin({ top: CODE_BLOCK_OUTER_INSET_V, bottom: CODE_BLOCK_OUTER_INSET_V })
    .id('markdown.code')
  }
}
