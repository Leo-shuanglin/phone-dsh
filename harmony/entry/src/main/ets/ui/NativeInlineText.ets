// SPDX-License-Identifier: MIT
// One flowing native Text for a Markdown paragraph, heading, list item or table
// cell, matching Android's single-line-paragraph layout.
//
// The previous Flex renderer put every text slice and every formula in its own
// block, so a paragraph could never wrap as one paragraph. This component keeps
// every slice inside one `Text`: styled `Span` siblings plus
// `ImageSpan(PixelMap)` formulas. The API 20 probe rejects an SVG data URI in
// ImageSpan but renders an ImageKit PixelMap; its formula alignment is
// `ImageSpanAlignment.BASELINE` with `baselineOffset = baseline - height`
// (positive moves the content up).
//
// Only the frozen inline surface is accepted: inlines, fontSize, codeFontSize,
// lineHeight, baseWeight, paragraphAlignment, color, dark and onOpenTarget. Markdown parsing
// and link safety stay in MarkdownBody/MarkdownModel; this component only
// reproduces the existing span styles. Native math uses the existing
// NativeMathCache SVG cache and inlineMathSegments() split. Raw TeX stays in
// the paragraph until its PixelMap is ready and after any failure. No WebView,
// no network, no SVG data URI and no resource handle in ShellSnapshot.
//
// Resource ownership: each decoded formula entry owns its PixelMap. Entries are
// released when their formula key leaves the paragraph, when a newer input
// replaces them, and on component disappearance. Async completions are guarded
// by entry identity plus an epoch, and a stale decode releases its own bitmap.

import { image } from '@kit.ImageKit';
import { LengthMetrics } from '@kit.ArkUI';
import {
  MarkdownInline,
  MarkdownLinkTarget,
  MarkdownSpanStyle
} from '../model/MarkdownModel';
import { Metrics, Palette } from './Palette';
import {
  InlineMathSegment,
  MathRender,
  decodeMathSvg,
  inlineMathSegments,
  nativeMath
} from './MathFormula';

const PHASE_TEXT: string = 'text';
const PHASE_LOADING: string = 'loading';
const PHASE_READY: string = 'ready';
const PHASE_FAILED: string = 'failed';

/** One native formula job and the PixelMap it owns. */
class FormulaEntry {
  key: string;
  latex: string;
  phase: string = PHASE_LOADING;
  pixel: image.PixelMap | undefined = undefined;
  width: number = 0;
  height: number = 0;
  baseline: number = 0;
  serial: number = 0;
  epoch: number = 0;

  constructor(key: string, latex: string) {
    this.key = key;
    this.latex = latex;
  }
}

/** One run handed to the single Text. */
class InlinePiece {
  sequence: number = 0;
  math: boolean = false;
  text: string = '';
  style: MarkdownSpanStyle = new MarkdownSpanStyle();
  formulaKey: string = '';
  phase: string = PHASE_TEXT;
  pixel: image.PixelMap | undefined = undefined;
  width: number = 0;
  height: number = 0;
  baseline: number = 0;
  serial: number = 0;
  renderKey: string = '';
}

@Component
export struct NativeInlineText {
  @Prop @Watch('onInputChanged') inlines: MarkdownInline[] = [];
  @Prop @Watch('onInputChanged') fontSize: number = Metrics.bodySize;
  @Prop @Watch('onInputChanged') codeFontSize: number = 0;
  @Prop @Watch('onInputChanged') lineHeight: number = 0;
  @Prop @Watch('onInputChanged') baseWeight: number | FontWeight = FontWeight.Normal;
  @Prop @Watch('onInputChanged') paragraphAlignment: TextAlign = TextAlign.Start;
  @Prop @Watch('onInputChanged') color: string = '#1A1A1A';
  @Prop @Watch('onInputChanged') dark: boolean = false;
  onOpenTarget: (target: MarkdownLinkTarget) => void = () => {};

  @State private pieces: InlinePiece[] = [];
  @State private lineSpacingVp: number = 0;

  private formulas: Map<string, FormulaEntry> = new Map<string, FormulaEntry>();
  private disposed: boolean = false;

  aboutToAppear(): void {
    this.disposed = false;
    this.reload();
  }

  aboutToDisappear(): void {
    this.disposed = true;
    this.formulas.forEach((entry: FormulaEntry) => {
      this.releaseEntry(entry);
    });
    this.formulas = new Map<string, FormulaEntry>();
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private spanSize(style: MarkdownSpanStyle): number {
    if (style.code) {
      return this.codeFontSize > 0 ? this.codeFontSize : Metrics.listItemSize;
    }
    if (style.footnote) {
      return Math.max(this.fontSize - 4, 9);
    }
    return this.fontSize;
  }

  private spanColor(style: MarkdownSpanStyle): string {
    if (style.link || style.footnote) {
      return this.pal().sessionAccent;
    }
    return this.color;
  }

  /**
   * Explicit fp -> px -> vp scaling keeps the RaTeX size equal to the displayed
   * text size even when the user changed the system font scale.
   */
  private mathFontSizeVp(): number {
    const context = this.getUIContext();
    return context.px2vp(context.fp2px(this.fontSize));
  }

  private formulaKey(latex: string, renderSizeVp: number): string {
    return renderSizeVp.toString() + '|' + this.color + '|i|' + latex;
  }

  private styleSignature(style: MarkdownSpanStyle): string {
    let key: string = (style.bold ? 'b' : '-') + (style.italic ? 'i' : '-') +
      (style.strike ? 's' : '-') + (style.code ? 'c' : '-') +
      (style.link ? 'l' : '-') + (style.footnote ? 'f' : '-');
    if (style.link && style.target !== null) {
      key += '|' + style.target.kind + '|' + style.target.value;
    }
    return key;
  }

  private refreshPieceKey(piece: InlinePiece): void {
    piece.renderKey = piece.sequence.toString() + '|' + (piece.math ? 'm' : 't') + '|' +
      piece.formulaKey + '|' + piece.phase + '|' + piece.serial.toString() + '|' +
      this.styleSignature(piece.style) + '|' + piece.text;
  }

  /**
   * The extra spacing is the desired fp line height minus the measured natural
   * single-line height, both converted to vp. `onlyBetweenLines` keeps that
   * leading out of the first and last line, so single-row table cards do not
   * regain the full paragraph leading. A non-positive lineHeight means natural
   * compact spacing.
   */
  private updateLineSpacing(): void {
    this.lineSpacingVp = 0;
    if (this.lineHeight <= 0 || this.fontSize <= 0) {
      return;
    }
    try {
      const context = this.getUIContext();
      const natural = context.getMeasureUtils().measureTextSize({
        textContent: 'Ag',
        fontSize: this.fontSize,
        fontWeight: this.baseWeight
      });
      const naturalHeight: Length | undefined = natural.height;
      if (typeof naturalHeight !== 'number' || !Number.isFinite(naturalHeight)) {
        this.lineSpacingVp = 0;
        return;
      }
      const desiredVp: number = context.px2vp(context.fp2px(this.lineHeight));
      const naturalVp: number = context.px2vp(naturalHeight);
      const extra: number = desiredVp - naturalVp;
      if (Number.isFinite(extra) && extra > 0) {
        this.lineSpacingVp = extra;
      }
    } catch (error) {
      // Measurement is an enhancement, not a prerequisite for readable text.
      this.lineSpacingVp = 0;
    }
  }

  private createPiece(
    sequence: number,
    segment: InlineMathSegment,
    formulaKey: string,
    entry: FormulaEntry | undefined
  ): InlinePiece {
    const piece: InlinePiece = new InlinePiece();
    piece.sequence = sequence;
    piece.math = segment.math;
    piece.text = segment.text;
    piece.style = segment.style;
    piece.formulaKey = formulaKey;
    if (entry !== undefined) {
      piece.phase = entry.phase;
      piece.pixel = entry.pixel;
      piece.width = entry.width;
      piece.height = entry.height;
      piece.baseline = entry.baseline;
      piece.serial = entry.serial;
    } else {
      piece.phase = PHASE_TEXT;
    }
    this.refreshPieceKey(piece);
    return piece;
  }

  private updatedPiece(piece: InlinePiece, entry: FormulaEntry): InlinePiece {
    const next: InlinePiece = new InlinePiece();
    next.sequence = piece.sequence;
    next.math = piece.math;
    next.text = piece.text;
    next.style = piece.style;
    next.formulaKey = piece.formulaKey;
    next.phase = entry.phase;
    next.pixel = entry.pixel;
    next.width = entry.width;
    next.height = entry.height;
    next.baseline = entry.baseline;
    next.serial = entry.serial;
    this.refreshPieceKey(next);
    return next;
  }

  private isActive(entry: FormulaEntry, epoch: number): boolean {
    return !this.disposed && entry.epoch === epoch && this.formulas.get(entry.key) === entry;
  }

  private releaseEntry(entry: FormulaEntry): void {
    const pixel: image.PixelMap | undefined = entry.pixel;
    entry.pixel = undefined;
    entry.phase = PHASE_FAILED;
    entry.epoch++;
    if (pixel !== undefined) {
      pixel.release().then(() => {}).catch(() => {});
    }
  }

  private startFormula(entry: FormulaEntry, renderSizeVp: number): void {
    entry.epoch++;
    const epoch: number = entry.epoch;
    nativeMath.render(entry.latex, renderSizeVp, this.color, false)
      .then((result: MathRender) => {
        this.consumeFormula(entry, epoch, result);
      })
      .catch(() => {
        this.failFormula(entry, epoch);
      });
  }

  private normalizedBaseline(baseline: number, height: number): number {
    if (!Number.isFinite(baseline)) {
      return height;
    }
    return Math.min(Math.max(baseline, 0), height);
  }

  private consumeFormula(entry: FormulaEntry, epoch: number, result: MathRender): void {
    if (!this.isActive(entry, epoch)) {
      return;
    }
    if (result.error.length > 0) {
      this.failFormula(entry, epoch);
      return;
    }
    const context = this.getUIContext();
    decodeMathSvg(
      result.svg,
      result.width,
      result.height,
      (value: number): number => context.vp2px(value)
    ).then((pixel: image.PixelMap) => {
      if (!this.isActive(entry, epoch)) {
        pixel.release().then(() => {}).catch(() => {});
        return;
      }
      entry.pixel = pixel;
      entry.width = result.width;
      entry.height = result.height;
      entry.baseline = this.normalizedBaseline(result.baseline, result.height);
      entry.phase = PHASE_READY;
      entry.serial++;
      this.publishFormula(entry);
    }).catch(() => {
      this.failFormula(entry, epoch);
    });
  }

  private failFormula(entry: FormulaEntry, epoch: number): void {
    if (!this.isActive(entry, epoch)) {
      return;
    }
    const pixel: image.PixelMap | undefined = entry.pixel;
    entry.pixel = undefined;
    entry.phase = PHASE_FAILED;
    entry.serial++;
    if (pixel !== undefined) {
      pixel.release().then(() => {}).catch(() => {});
    }
    this.publishFormula(entry);
  }

  private publishFormula(entry: FormulaEntry): void {
    if (this.disposed) {
      return;
    }
    const next: InlinePiece[] = [];
    let changed: boolean = false;
    for (let i: number = 0; i < this.pieces.length; i++) {
      const piece: InlinePiece = this.pieces[i];
      if (piece.formulaKey === entry.key) {
        next.push(this.updatedPiece(piece, entry));
        changed = true;
      } else {
        next.push(piece);
      }
    }
    if (changed) {
      this.pieces = next;
    }
  }

  private reload(): void {
    this.updateLineSpacing();
    const renderSizeVp: number = this.mathFontSizeVp();
    const segments: InlineMathSegment[] = inlineMathSegments(this.inlines);
    const nextFormulas: Map<string, FormulaEntry> = new Map<string, FormulaEntry>();
    const nextPieces: InlinePiece[] = [];
    const toStart: FormulaEntry[] = [];
    for (let i: number = 0; i < segments.length; i++) {
      const segment: InlineMathSegment = segments[i];
      if (!segment.math) {
        nextPieces.push(this.createPiece(i, segment, '', undefined));
        continue;
      }
      const key: string = this.formulaKey(segment.text, renderSizeVp);
      let entry: FormulaEntry | undefined = nextFormulas.get(key);
      if (entry === undefined) {
        entry = this.formulas.get(key);
        if (entry === undefined) {
          entry = new FormulaEntry(key, segment.text);
          toStart.push(entry);
        }
        nextFormulas.set(key, entry);
      }
      nextPieces.push(this.createPiece(i, segment, key, entry));
    }
    // Release decoded maps whose formula is no longer present. Entries that are
    // unchanged keep their PixelMap, so re-rendering a line does not repeat the
    // native render or the decode for the same TeX.
    this.formulas.forEach((entry: FormulaEntry, key: string) => {
      if (nextFormulas.get(key) !== entry) {
        this.releaseEntry(entry);
      }
    });
    this.formulas = nextFormulas;
    this.pieces = nextPieces;
    for (let i: number = 0; i < toStart.length; i++) {
      this.startFormula(toStart[i], renderSizeVp);
    }
  }

  onInputChanged(): void {
    this.reload();
  }

  private openPiece(piece: InlinePiece): void {
    if (piece.style.link && piece.style.target !== null) {
      this.onOpenTarget(piece.style.target);
    }
  }

  @Builder
  TextPiece(piece: InlinePiece) {
    Span(piece.text)
      .fontSize(this.spanSize(piece.style))
      .fontFamily(piece.style.code ? 'monospace' : '')
      .fontColor(this.spanColor(piece.style))
      .fontWeight(piece.style.bold ? 600 : this.baseWeight)
      .fontStyle(piece.style.italic ? FontStyle.Italic : FontStyle.Normal)
      .decoration({
        type: piece.style.link ? TextDecorationType.Underline :
          (piece.style.strike ? TextDecorationType.LineThrough : TextDecorationType.None),
        color: piece.style.link ? this.pal().sessionAccent : this.color
      })
      .textBackgroundStyle({
        color: piece.style.code ? this.pal().sessionInlineCode : '#00000000',
        radius: 3
      })
      .onClick(() => {
        this.openPiece(piece);
      })
  }

  @Builder
  MathPiece(piece: InlinePiece) {
    if (piece.pixel !== undefined && piece.width > 0 && piece.height > 0) {
      ImageSpan(piece.pixel)
        .width(piece.width)
        .height(piece.height)
        .objectFit(ImageFit.Contain)
        .verticalAlign(ImageSpanAlignment.BASELINE)
        .baselineOffset(LengthMetrics.vp(piece.baseline - piece.height))
        .onClick(() => {
          this.openPiece(piece);
        })
    } else {
      // Loading or failed: keep the actual TeX in the paragraph. No hidden
      // replacement text and no invented formula.
      this.TextPiece(piece)
    }
  }

  build() {
    if (this.pieces.length === 0) {
      Text('')
        .fontSize(this.fontSize)
        .fontColor(this.color)
        .width('100%')
    } else {
      Text() {
        ForEach(this.pieces, (piece: InlinePiece) => {
          if (piece.math) {
            this.MathPiece(piece)
          } else {
            this.TextPiece(piece)
          }
        }, (piece: InlinePiece) => piece.renderKey)
      }
      .width('100%')
      .fontSize(this.fontSize)
      .fontColor(this.color)
      .fontWeight(this.baseWeight)
      .textAlign(this.paragraphAlignment)
      .lineSpacing(LengthMetrics.vp(this.lineSpacingVp), { onlyBetweenLines: true })
    }
  }
}
