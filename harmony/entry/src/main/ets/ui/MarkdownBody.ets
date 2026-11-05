import { ImageLoadControl } from '../model/ImageLoadControl';
// Native ArkUI renderer for the answer body's Markdown blocks.
// SPDX-License-Identifier: MIT
//
// Mirrors the canonical Android MarkdownBody.kt / iOS NativeMarkdownBody.swift:
// paragraphs, headings, lists, quotes, rules, fenced code, GFM tables, and the
// DSH TeX extensions. Raw HTML stays literal text. Image sources use the
// bounded native resource loader. Math is parsed and laid out by the Rust RaTeX 0.1.15 renderer and
// drawn as native ImageKit SVG (outlined glyph paths, no WebView). Parse
// failures keep the source on screen and state the failure; there is no fake
// formula or rendered-looking fallback.

import { BusinessError } from '@kit.BasicServicesKit';
import { common, Want } from '@kit.AbilityKit';
import { Palette, Metrics, Copy } from './Palette';
import { NativeCodeBlock } from './NativeCodeBlock';
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

// Android `MarkdownTable` picks cards when the minimum grid width no longer
// fits the measured row. Body cells use 16/10 insets, compact cells 8/4; card
// geometry mirrors `MarkdownTableCards`.
const TABLE_COLUMN_MIN_WIDTH: number = 96;
const TABLE_COLUMN_MAX_WIDTH: number = 320;
const TABLE_CELL_INSET_H: number = 16;
const TABLE_CELL_INSET_V: number = 10;
const TABLE_COMPACT_CELL_INSET_H: number = 8;
const TABLE_COMPACT_CELL_INSET_V: number = 4;
const TABLE_CARD_GAP: number = 8;
const TABLE_CARD_RADIUS: number = 12;
const TABLE_CARD_INSET: number = 12;
const TABLE_FIELD_GAP: number = 10;
const TABLE_LABEL_VALUE_GAP: number = 2;

/**
 * One GFM table. The component measures its own available width so a table
 * nested in a list or quote uses the real row width, not a viewport guess.
 * Above the minimum grid width it draws the aligned grid; below it draws the
 * per-row header/value cards from Android's `MarkdownTableCards`.
 */
@Component
struct MarkdownTableBody {
  @Prop block: MarkdownTableBlock = new MarkdownTableBlock([], [], []);
  @Prop dark: boolean = false;
  @Prop variant: MarkdownVariant = MarkdownVariant.Normal;
  @Prop bodyFontSize: number = 0;
  @Prop codeFontSize: number = 0;
  onOpenTarget: (target: MarkdownLinkTarget) => void = () => {};
  @State private availableWidth: number = 0;

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
    if (this.compact()) {
      return 18;
    }
    return this.bodySize() + 8;
  }

  private codeSize(): number {
    return this.codeFontSize > 0 ? this.codeFontSize : Metrics.listItemSize;
  }

  private columns(): number {
    let columns: number = this.block.header.length;
    for (let i: number = 0; i < this.block.rows.length; i++) {
      if (this.block.rows[i].length > columns) {
        columns = this.block.rows[i].length;
      }
    }
    return columns;
  }

  private indexes(columns: number): number[] {
    const out: number[] = [];
    for (let i: number = 0; i < columns; i++) {
      out.push(i);
    }
    return out;
  }

  private cellAt(cells: MarkdownInline[][], index: number): MarkdownInline[] {
    if (index < cells.length) {
      return cells[index];
    }
    return [];
  }

  private paddedCells(cells: MarkdownInline[][], columns: number): MarkdownInline[][] {
    const out: MarkdownInline[][] = [];
    for (let i: number = 0; i < columns; i++) {
      out.push(this.cellAt(cells, i));
    }
    return out;
  }

  private paddedRows(): MarkdownInline[][][] {
    const out: MarkdownInline[][][] = [];
    const columns: number = this.columns();
    for (let i: number = 0; i < this.block.rows.length; i++) {
      out.push(this.paddedCells(this.block.rows[i], columns));
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

  private cellInsetH(): number {
    return this.compact() ? TABLE_COMPACT_CELL_INSET_H : TABLE_CELL_INSET_H;
  }

  private cellInsetV(): number {
    return this.compact() ? TABLE_COMPACT_CELL_INSET_V : TABLE_CELL_INSET_V;
  }

  private showGrid(): boolean {
    return this.availableWidth > 0 &&
      this.columns() * TABLE_COLUMN_MIN_WIDTH <= this.availableWidth;
  }

  private openRun(run: MarkdownRun): void {
    if (run.style.target !== null) {
      this.onOpenTarget(run.style.target);
    }
  }

  @Builder
  InlineMathText(inlines: MarkdownInline[], fontSize: number, color: string, lineHeight: number, baseWeight: number | FontWeight, align: TextAlign) {
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
            .fontWeight(segment.style.bold ? 600 : baseWeight)
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
                this.onOpenTarget(segment.style.target);
              }
            })
        }
      }, (segment: InlineMathSegment, index: number) => index.toString() + JSON.stringify(segment))
    }
    .width('100%')
  }

  @Builder
  InlineText(inlines: MarkdownInline[], fontSize: number, color: string, lineHeight: number, baseWeight: number | FontWeight, align: TextAlign) {
    if (inlines.length === 0) {
      Text('')
        .fontSize(fontSize)
        .fontColor(color)
        .width('100%')
    } else if (hasInlineMath(inlines)) {
      this.InlineMathText(inlines, fontSize, color, lineHeight, baseWeight, align)
    } else {
      Text() {
        ForEach(flattenInlines(inlines), (run: MarkdownRun) => {
          Span(run.text)
            .fontSize(run.style.code ? this.codeSize() :
              (run.style.footnote ? Math.max(fontSize - 4, 9) : fontSize))
            .fontColor(run.style.link ? this.pal().sessionAccent :
              (run.style.footnote ? this.pal().sessionAccent : color))
            .fontWeight(run.style.bold ? 600 : baseWeight)
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
  GridRowView(cells: MarkdownInline[][], alignments: (MarkdownTableAlign | null)[], header: boolean, columns: number) {
    Row() {
      ForEach(this.paddedCells(cells, columns), (cell: MarkdownInline[], index: number) => {
        Column() {
          this.InlineText(
            cell,
            this.bodySize(),
            this.pal().textPrimary,
            this.bodyLine(),
            header ? 600 : FontWeight.Normal,
            this.alignAt(alignments, index)
          )
        }
        .layoutWeight(1)
        .constraintSize({ minWidth: TABLE_COLUMN_MIN_WIDTH, maxWidth: TABLE_COLUMN_MAX_WIDTH })
        .padding({
          left: this.cellInsetH(),
          right: this.cellInsetH(),
          top: this.cellInsetV(),
          bottom: this.cellInsetV()
        })
        .border({
          width: { bottom: Metrics.hairline },
          color: this.pal().borderList
        })
      }, (cell: MarkdownInline[], index: number) => index.toString() + JSON.stringify(cell))
    }
    .width('100%')
    .backgroundColor(header ? this.pal().sessionSelector : '#00000000')
    .alignItems(VerticalAlign.Top)
  }

  @Builder
  GridView() {
    Column() {
      this.GridRowView(this.block.header, this.block.alignments, true, this.columns())
      ForEach(this.paddedRows(), (row: MarkdownInline[][], index: number) => {
        this.GridRowView(row, this.block.alignments, false, this.columns())
      }, (row: MarkdownInline[][], index: number) => index.toString() + JSON.stringify(row))
    }
    .width('100%')
  }

  @Builder
  CardsView() {
    Column({ space: TABLE_CARD_GAP }) {
      ForEach(this.block.rows, (cells: MarkdownInline[][], rowIndex: number) => {
        Column({ space: TABLE_FIELD_GAP }) {
          ForEach(this.indexes(this.columns()), (column: number) => {
            Column({ space: TABLE_LABEL_VALUE_GAP }) {
              this.InlineText(
                this.cellAt(this.block.header, column),
                Metrics.captionSize,
                this.pal().sessionSecondary,
                Metrics.captionSize + 6,
                FontWeight.Medium,
                TextAlign.Start
              )
              this.InlineText(
                this.cellAt(cells, column),
                this.bodySize(),
                this.pal().textPrimary,
                this.bodyLine(),
                FontWeight.Normal,
                TextAlign.Start
              )
            }
            .width('100%')
            .alignItems(HorizontalAlign.Start)
          }, (column: number, index: number) => index.toString())
        }
        .width('100%')
        .padding(TABLE_CARD_INSET)
        .backgroundColor(this.pal().sessionSelector)
        .borderRadius(TABLE_CARD_RADIUS)
        .alignItems(HorizontalAlign.Start)
      }, (cells: MarkdownInline[][], index: number) => index.toString() + JSON.stringify(cells))
    }
    .width('100%')
  }

  build() {
    Column() {
      if (this.showGrid()) {
        this.GridView()
      } else {
        this.CardsView()
      }
    }
    .width('100%')
    .onAreaChange((oldValue: Area, newValue: Area) => {
      const width: number = newValue.width as number;
      if (width !== this.availableWidth) {
        this.availableWidth = width;
      }
    })
  }
}

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
            .fontWeight(segment.style.bold ? 600 :
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
      }, (segment: InlineMathSegment, index: number) => index.toString() + JSON.stringify(segment))
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
            .fontWeight(run.style.bold ? 600 :
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
    NativeCodeBlock({ language: block.language, body: block.body, dark: this.dark })
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
  TableView(block: MarkdownTableBlock) {
    MarkdownTableBody({
      block: block,
      dark: this.dark,
      variant: this.variant,
      bodyFontSize: this.bodyFontSize,
      codeFontSize: this.codeFontSize,
      onOpenTarget: (target: MarkdownLinkTarget): void => {
        this.openTarget(target);
      }
    })
    .width('100%')
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
          }, (child: MarkdownBlock, childIndex: number) => childIndex.toString() + JSON.stringify(child))
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
      }, (item: MarkdownListItem, index: number) => index.toString() + JSON.stringify(item))
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
      }, (block: MarkdownBlock, index: number) => index.toString() + JSON.stringify(block))
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
