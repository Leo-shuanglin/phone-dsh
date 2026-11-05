import { ImageLoadControl } from '../model/ImageLoadControl';
// Native ArkUI renderer for the answer body's Markdown blocks.
// SPDX-License-Identifier: MIT
//
// Mirrors the canonical Android MarkdownBody.kt / iOS NativeMarkdownBody.swift:
// paragraphs, headings, lists, quotes, rules, fenced code, GFM tables, and the
// DSH TeX extensions. Raw HTML stays literal text; remote images are never
// fetched. Math is parsed and laid out by the Rust RaTeX 0.1.15 renderer and
// drawn as native ImageKit SVG (outlined glyph paths, no WebView). Parse
// failures keep the source on screen and state the failure; there is no fake
// formula or rendered-looking fallback.

import { pasteboard, BusinessError } from '@kit.BasicServicesKit';
import { common, Want } from '@kit.AbilityKit';
import { Palette, Metrics, Copy } from './Palette';
import { MarkdownImage } from './MarkdownImage';
import { ImageBytes } from '../model/Contracts';
import {
  InlineMathSegment,
  MathFormula,
  hasInlineMath,
  inlineMathSegments
} from './MathFormula';
import {
  MarkdownBlock,
  MarkdownBlockKind,
  MarkdownCodeBlock,
  MarkdownHeadingBlock,
  MarkdownImageBlock,
  MarkdownInline,
  MarkdownLinkTarget,
  MarkdownLinkKind,
  MarkdownListItem,
  MarkdownListBlock,
  MarkdownMathBlock,
  MarkdownParagraphBlock,
  MarkdownQuoteBlock,
  MarkdownRun,
  MarkdownTableAlign,
  MarkdownTableBlock,
  MarkdownVariant,
  flattenInlines,
  parseMarkdownOrPlain
} from '../model/MarkdownModel';

/**
 * Renders one answer body.
 *
 * @param markdown the projected answer text, parsed on change.
 * @param compact compact disclosure typography (expanded thinking).
 * @param onOpenPath invoked for safe relative workspace links.
 */
@Component
export struct MarkdownBody {
  @Prop @Watch('onMarkdownChanged') markdown: string = '';
  @Prop dark: boolean = false;
  @Prop variant: MarkdownVariant = MarkdownVariant.Normal;
  // 0 keeps the reviewed Metrics default (used by pages that have not threaded
  // typography yet). Positive values are the user's body / code roles in pt.
  @Prop bodyFontSize: number = 0;
  @Prop codeFontSize: number = 0;
  onOpenPath: (path: string) => void = () => {};
  // file/sandbox/dsh-resource links are routed through the typed resource
  // action; unknown sessions/paths are refused visibly by the HostService.
  onOpenResource: (source: string) => void = () => {};
  // Component-owned image bytes from the typed page loader. The MarkdownImage
  // component decodes the bytes with ImageKit and owns the PixelMap.
  loadImage?: (source: string, control?: ImageLoadControl) => Promise<ImageBytes>;
  @State private blocks: MarkdownBlock[] = [];
  @State private copiedCode: string = '';
  @State private linkFailure: boolean = false;

  aboutToAppear(): void {
    this.blocks = parseMarkdownOrPlain(this.markdown);
  }

  onMarkdownChanged(): void {
    this.linkFailure = false;
    this.blocks = parseMarkdownOrPlain(this.markdown);
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private compact(): boolean {
    return this.variant === MarkdownVariant.Compact;
  }

  private bodySize(): number {
    if (this.compact()) {
      return Metrics.secondarySize;
    }
    return this.bodyFontSize > 0 ? this.bodyFontSize : Metrics.bodySize;
  }

  private bodyLine(): number {
    if (this.compact()) { return 18; }
    return this.bodySize() + 8;
  }

  private codeSize(): number {
    return this.codeFontSize > 0 ? this.codeFontSize : Metrics.listItemSize;
  }

  private codeLine(): number {
    return this.codeSize() + 6;
  }

  private bodyColor(): string {
    return this.compact() ? this.pal().textTertiary : this.pal().textPrimary;
  }

  private headingSize(level: number): number {
    if (this.compact()) {
      return Metrics.secondarySize;
    }
    const base: number = this.bodySize();
    if (level <= 1) {
      return base + 8;
    }
    if (level === 2) {
      return base + 6;
    }
    if (level === 3) {
      return base + 4;
    }
    if (level === 4) {
      return base + 1;
    }
    return base;
  }

  private tableColumns(block: MarkdownTableBlock): number {
    let columns: number = block.header.length;
    for (let i: number = 0; i < block.rows.length; i++) {
      if (block.rows[i].length > columns) {
        columns = block.rows[i].length;
      }
    }
    return columns;
  }

  private paddedCells(cells: MarkdownInline[][], columns: number): MarkdownInline[][] {
    const out: MarkdownInline[][] = [];
    for (let i: number = 0; i < columns; i++) {
      out.push(i < cells.length ? cells[i] : []);
    }
    return out;
  }

  private alignAt(alignments: (MarkdownTableAlign | null)[], index: number): TextAlign {
    if (index >= alignments.length) {
      return TextAlign.Start;
    }
    const align: MarkdownTableAlign | null = alignments[index];
    if (align === MarkdownTableAlign.Center) {
      return TextAlign.Center;
    }
    if (align === MarkdownTableAlign.Right) {
      return TextAlign.End;
    }
    return TextAlign.Start;
  }

  private openRun(run: MarkdownRun): void {
    if (run.style.target === null) {
      return;
    }
    this.openTarget(run.style.target);
  }

  private openTarget(target: MarkdownLinkTarget): void {
    if (target.kind === MarkdownLinkKind.WorkspacePath) {
      if (target.value.length > 0) {
        this.onOpenPath(target.value);
      }
      return;
    }
    if (target.kind !== MarkdownLinkKind.External) {
      // file/sandbox/dsh-resource: the typed resource action applies Android's
      // session/safe-root policy. It never guesses a local path and never
      // silently maps an unknown session to the active workspace.
      this.onOpenResource(target.value);
      return;
    }
    const want: Want = {
      action: 'ohos.want.action.viewData',
      entities: ['entity.system.browsable'],
      uri: target.value
    };
    const host: common.Context | undefined = this.getUIContext().getHostContext();
    if (host === undefined) {
      this.linkFailure = true;
      return;
    }
    const context: common.UIAbilityContext = host as common.UIAbilityContext;
    context.startAbility(want).catch((error: BusinessError) => {
      this.linkFailure = true;
    });
  }

  private copyCode(body: string): void {
    const data: pasteboard.PasteData =
      pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, body);
    pasteboard.getSystemPasteboard().setData(data).then(() => {
      this.copiedCode = body;
      setTimeout(() => {
        if (this.copiedCode === body) {
          this.copiedCode = '';
        }
      }, 1500);
    }).catch((error: BusinessError) => {
      this.copiedCode = '';
    });
  }

  @Builder
  InlineMathText(inlines: MarkdownInline[], fontSize: number, color: string, lineHeight: number, baseBold: boolean, align: TextAlign) {
    Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.Start, alignItems: ItemAlign.Center }) {
      ForEach(inlineMathSegments(inlines), (segment: InlineMathSegment) => {
        if (segment.math) {
          MathFormula({
            latex: segment.text,
            fontSize: fontSize,
            color: color,
            displayMode: false
          })
        } else {
          Text(segment.text)
            .fontSize(segment.style.code ? this.codeSize() :
              (segment.style.footnote ? Math.max(fontSize - 4, 9) : fontSize))
            .fontColor(segment.style.link ? this.pal().sessionAccent :
              (segment.style.footnote ? this.pal().sessionAccent : color))
            .fontWeight(segment.style.bold ? FontWeight.Medium :
              (baseBold ? FontWeight.Bold : FontWeight.Normal))
            .fontStyle(segment.style.italic ? FontStyle.Italic : FontStyle.Normal)
            .decoration({
              type: segment.style.link ? TextDecorationType.Underline :
                (segment.style.strike ? TextDecorationType.LineThrough : TextDecorationType.None),
              color: segment.style.link ? this.pal().sessionAccent : color
            })
            .lineHeight(lineHeight)
            .textAlign(align)
            .onClick(() => {
              if (segment.style.link && segment.style.target !== null) {
                this.openTarget(segment.style.target);
              }
            })
        }
      }, (segment: InlineMathSegment, index: number) => index.toString())
    }
    .width('100%')
  }

  @Builder
  InlineText(inlines: MarkdownInline[], fontSize: number, color: string, lineHeight: number, baseBold: boolean, align: TextAlign) {
    if (inlines.length === 0) {
      Text('')
        .fontSize(fontSize)
        .fontColor(color)
        .width('100%')
    } else if (hasInlineMath(inlines)) {
      this.InlineMathText(inlines, fontSize, color, lineHeight, baseBold, align)
    } else {
      Text() {
        ForEach(flattenInlines(inlines), (run: MarkdownRun) => {
          Span(run.text)
            .fontSize(run.style.code ? this.codeSize() :
              (run.style.footnote ? Math.max(fontSize - 4, 9) : fontSize))
            .fontColor(run.style.link ? this.pal().sessionAccent :
              (run.style.footnote ? this.pal().sessionAccent : color))
            .fontWeight(run.style.bold ? FontWeight.Medium :
              (baseBold ? FontWeight.Bold : FontWeight.Normal))
            .fontStyle(run.style.italic ? FontStyle.Italic : FontStyle.Normal)
            .decoration({
              type: run.style.link ? TextDecorationType.Underline :
                (run.style.strike ? TextDecorationType.LineThrough : TextDecorationType.None),
              color: run.style.link ? this.pal().sessionAccent : color
            })
            .textBackgroundStyle({
              color: run.style.code ? this.pal().sessionInlineCode : '#00000000',
              radius: 3
            })
            .onClick(() => {
              if (run.style.link) {
                this.openRun(run);
              }
            })
        })
      }
      .fontSize(fontSize)
      .fontColor(color)
      .lineHeight(lineHeight)
      .width('100%')
      .textAlign(align)
    }
  }

  @Builder
  ParagraphView(block: MarkdownParagraphBlock) {
    this.InlineText(
      block.inlines,
      this.bodySize(),
      this.bodyColor(),
      this.bodyLine(),
      false,
      TextAlign.Start
    )
  }

  @Builder
  HeadingView(block: MarkdownHeadingBlock) {
    this.InlineText(
      block.inlines,
      this.headingSize(block.level),
      this.compact() ? this.bodyColor() : this.pal().textPrimary,
      this.headingSize(block.level) * 1.35,
      true,
      TextAlign.Start
    )
  }

  @Builder
  CodeView(block: MarkdownCodeBlock) {
    Column() {
      Row() {
        Text(block.language.length > 0 ? block.language : Copy.tr('代码'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().textSubtle)
        Blank()
        Text(this.copiedCode === block.body ? Copy.tr('已复制') : Copy.tr('复制代码'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.copiedCode === block.body ? this.pal().sessionSuccess : this.pal().sessionAccent)
          .onClick(() => {
            this.copyCode(block.body);
          })
          .accessibilityText(block.language.length > 0 ?
            Copy.fill('{0} 代码，复制', [block.language]) : Copy.tr('代码，复制'))
      }
      .width('100%')
      .margin({ bottom: 6 })

      Scroll() {
        Text(block.body)
          .fontSize(this.codeSize())
          .fontColor(this.pal().textPrimary)
          .fontFamily('monospace')
          .lineHeight(this.codeLine())
          .textAlign(TextAlign.Start)
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
    }
    .width('100%')
    .padding(12)
    .backgroundColor(this.pal().sessionCode)
    .borderRadius(10)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  MathView(block: MarkdownMathBlock) {
    Column() {
      MathFormula({
        latex: block.latex,
        fontSize: this.compact() ? Metrics.listItemSize : this.bodySize(),
        color: this.bodyColor(),
        displayMode: true
      })
    }
    .width('100%')
    .padding({ top: 2, bottom: 2 })
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  ImageView(block: MarkdownImageBlock) {
    MarkdownImage({
      source: block.source,
      alt: block.alt,
      dark: this.dark,
      bodyFontSize: this.bodySize(),
      loadImage: this.loadImage
    })
    .width('100%')
  }

  @Builder
  TableRowView(cells: MarkdownInline[][], alignments: (MarkdownTableAlign | null)[], header: boolean) {
    Row() {
      ForEach(cells, (cell: MarkdownInline[], index: number) => {
        Column() {
          this.InlineText(
            cell,
            this.compact() ? Metrics.secondarySize : Metrics.listItemSize,
            this.pal().textPrimary,
            this.compact() ? 18 : Metrics.listItemLine,
            header,
            this.alignAt(alignments, index)
          )
        }
        .layoutWeight(1)
        .padding({ left: 8, right: 8, top: 8, bottom: 8 })
        .border({
          width: { bottom: Metrics.hairline },
          color: this.pal().borderList
        })
      }, (cell: MarkdownInline[], index: number) => index.toString())
    }
    .width('100%')
    .backgroundColor(header ? this.pal().sessionSelector : '#00000000')
    .alignItems(VerticalAlign.Top)
  }

  @Builder
  TableView(block: MarkdownTableBlock) {
    Column() {
      this.TableRowView(this.paddedCells(block.header, this.tableColumns(block)), block.alignments, true)
      ForEach(this.paddedRows(block), (row: MarkdownInline[][]) => {
        this.TableRowView(row, block.alignments, false)
      }, (row: MarkdownInline[][], index: number) => index.toString())
    }
    .width('100%')
    .borderRadius(8)
    .clip(true)
  }

  private paddedRows(block: MarkdownTableBlock): MarkdownInline[][][] {
    const out: MarkdownInline[][][] = [];
    const columns: number = this.tableColumns(block);
    for (let i: number = 0; i < block.rows.length; i++) {
      out.push(this.paddedCells(block.rows[i], columns));
    }
    return out;
  }

  @Builder
  QuoteView(block: MarkdownQuoteBlock) {
    Column({ space: this.compact() ? 4 : 8 }) {
      ForEach(block.blocks, (child: MarkdownBlock, index: number) => {
        this.BlockView(child)
      }, (child: MarkdownBlock, index: number) => index.toString())
    }
    .width('100%')
    .padding({ left: 14, top: 2, bottom: 2 })
    .border({ width: { left: 2 }, color: this.pal().borderList })
  }

  private markerFor(block: MarkdownListBlock, itemIndex: number): string {
    if (block.items[itemIndex].checked === true) {
      return '[x]';
    }
    if (block.items[itemIndex].checked === false) {
      return '[ ]';
    }
    return block.ordered ? (block.start + itemIndex).toString() + '.' : '•';
  }

  @Builder
  ListView(block: MarkdownListBlock) {
    Column({ space: this.compact() ? 2 : 6 }) {
      ForEach(block.items, (item: MarkdownListItem, index: number) => {
        Column({ space: this.compact() ? 2 : 6 }) {
          Row() {
            Text(this.markerFor(block, index))
              .fontSize(this.bodySize())
              .fontColor(this.pal().textSecondary)
              .width(block.ordered ? 26 : 18)
              .textAlign(TextAlign.End)
            Column() {
              if (item.imageBlock !== null) {
                this.ImageView(item.imageBlock)
              } else {
                this.InlineText(
                  item.inlines,
                  this.bodySize(),
                  this.bodyColor(),
                  this.bodyLine(),
                  false,
                  TextAlign.Start
                )
              }
            }
            .layoutWeight(1)
            .alignItems(HorizontalAlign.Start)
          }
          .width('100%')
          .alignItems(VerticalAlign.Top)
          ForEach(item.children, (child: MarkdownBlock, childIndex: number) => {
            this.BlockView(child)
          }, (child: MarkdownBlock, childIndex: number) => childIndex.toString())
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
      }, (item: MarkdownListItem, index: number) => index.toString())
    }
    .width('100%')
    .padding({ left: 16 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  BlockView(block: MarkdownBlock) {
    if (block.kind === MarkdownBlockKind.Paragraph) {
      this.ParagraphView(block as MarkdownParagraphBlock)
    } else if (block.kind === MarkdownBlockKind.Heading) {
      this.HeadingView(block as MarkdownHeadingBlock)
    } else if (block.kind === MarkdownBlockKind.Code) {
      this.CodeView(block as MarkdownCodeBlock)
    } else if (block.kind === MarkdownBlockKind.Table) {
      this.TableView(block as MarkdownTableBlock)
    } else if (block.kind === MarkdownBlockKind.Math) {
      this.MathView(block as MarkdownMathBlock)
    } else if (block.kind === MarkdownBlockKind.Image) {
      this.ImageView(block as MarkdownImageBlock)
    } else if (block.kind === MarkdownBlockKind.List) {
      this.ListView(block as MarkdownListBlock)
    } else if (block.kind === MarkdownBlockKind.Quote) {
      this.QuoteView(block as MarkdownQuoteBlock)
    } else if (block.kind === MarkdownBlockKind.Rule) {
      Divider()
        .strokeWidth(Metrics.hairline)
        .color(this.pal().borderList)
        .margin({ top: 8, bottom: 8 })
    }
  }

  build() {
    Column({ space: this.compact() ? 4 : Metrics.blockGap }) {
      ForEach(this.blocks, (block: MarkdownBlock, index: number) => {
        this.BlockView(block)
      }, (block: MarkdownBlock, index: number) => index.toString())
      if (this.linkFailure) {
        Text(Copy.tr('链接打开失败'))
          .fontSize(Metrics.captionSize)
          .fontColor(this.pal().sessionDanger)
          .margin({ top: 4 })
          .id('markdown.link.failure')
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}
