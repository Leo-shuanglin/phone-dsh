// SPDX-License-Identifier: MIT
// Native RaTeX/SVG math rendering for the answer body.
//
// The Rust host parses and lays out the LaTeX with RaTeX 0.1.15 and returns a
// self-contained SVG whose glyphs are outlined paths from the embedded KaTeX
// fonts. This file renders that SVG through ArkUI's native ImageKit/Image
// pipeline (never a WebView), keeps a bounded cache, caps the logical result at
// the native 8192x8192 px / 4,194,304 px^2 bounds, coalesces duplicate
// in-flight jobs, releases ImageSource/PixelMap promptly and guards stale async
// results by generation.
import native from 'libpdshhost.so';
import { Copy } from './Palette';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import {
  MarkdownCodeInline,
  MarkdownDeleteInline,
  MarkdownEmphasisInline,
  MarkdownFootnoteInline,
  MarkdownImageInline,
  MarkdownInline,
  MarkdownInlineKind,
  MarkdownLinkInline,
  MarkdownLinkKind,
  MarkdownMathInline,
  MarkdownSpanStyle,
  MarkdownStrongInline,
  MarkdownTextInline,
  markdownLinkTarget
} from '../model/MarkdownModel';

/** JSON options accepted by native.renderMath. */
export class MathOptions {
  fontSize: number;
  color: string;
  displayMode: boolean;

  constructor(fontSize: number, color: string, displayMode: boolean) {
    this.fontSize = fontSize;
    this.color = color;
    this.displayMode = displayMode;
  }
}

/** Decoded native result. `error` is non-empty instead of svg on failure. */
export class MathRender {
  svg: string = '';
  width: number = 0;
  height: number = 0;
  baseline: number = 0;
  error: string = '';

  static ok(svg: string, width: number, height: number, baseline: number): MathRender {
    const result: MathRender = new MathRender();
    result.svg = svg;
    result.width = width;
    result.height = height;
    result.baseline = baseline;
    return result;
  }

  static failed(error: string): MathRender {
    const result: MathRender = new MathRender();
    result.error = error;
    return result;
  }
}

const CACHE_LIMIT: number = 48;
// 25 formulas can be present in one normal answer; 64 keeps that bounded while
// leaving room for a few concurrent reloads.
const PENDING_LIMIT: number = 64;
// Match the native result cap: 8192 px per edge and 4,194,304 px^2 total.
const MAX_PIXEL_EDGE: number = 8192;
const MAX_PIXEL_AREA: number = 4194304;

function retryableFailure(message: string): boolean {
  return message.indexOf('排队') >= 0 || message.indexOf('渲染失败') >= 0 ||
    message.indexOf('JSON') >= 0 || message.indexOf('缺少 SVG') >= 0 ||
    message.indexOf('结果无效') >= 0 || message.indexOf('解码失败') >= 0;
}

/** Bounded LRU plus in-flight coalescing for native SVG jobs. */
export class NativeMathCache {
  private values: Map<string, MathRender> = new Map<string, MathRender>();
  private order: string[] = [];
  private pending: Map<string, Promise<MathRender>> = new Map<string, Promise<MathRender>>();

  render(latex: string, fontSize: number, color: string, displayMode: boolean): Promise<MathRender> {
    const key: string = this.keyFor(latex, fontSize, color, displayMode);
    const cached: MathRender | undefined = this.values.get(key);
    if (cached !== undefined) {
      this.touch(key);
      return Promise.resolve(cached);
    }
    const running: Promise<MathRender> | undefined = this.pending.get(key);
    if (running !== undefined) {
      return running;
    }
    if (this.pending.size >= PENDING_LIMIT) {
      return Promise.resolve(MathRender.failed('原生公式渲染排队过多，请重试'));
    }
    const options: MathOptions = new MathOptions(fontSize, color, displayMode);
    const started: Promise<MathRender> = this.load(key, latex, JSON.stringify(options));
    this.pending.set(key, started);
    return started;
  }

  private async load(key: string, latex: string, optionsJson: string): Promise<MathRender> {
    try {
      const text: string = await native.renderMath(latex, optionsJson);
      const result: MathRender = this.decode(text);
      if (result.error.length === 0) {
        this.remember(key, result);
      }
      return result;
    } catch (error) {
      return MathRender.failed('原生公式渲染失败');
    } finally {
      this.pending.delete(key);
    }
  }

  private decode(text: string): MathRender {
    let parsed: Record<string, Object>;
    try {
      parsed = JSON.parse(text) as Record<string, Object>;
    } catch (error) {
      return MathRender.failed('原生公式结果不是有效 JSON');
    }
    const errorValue: Object | undefined = parsed.error;
    if (errorValue !== undefined && errorValue !== null) {
      return MathRender.failed(errorValue.toString());
    }
    const svgValue: Object | undefined = parsed.svg;
    const widthValue: Object | undefined = parsed.width;
    const heightValue: Object | undefined = parsed.height;
    const baselineValue: Object | undefined = parsed.baseline;
    if (svgValue === undefined || widthValue === undefined || heightValue === undefined) {
      return MathRender.failed('原生公式结果缺少 SVG 或尺寸');
    }
    const svg: string = svgValue as string;
    const width: number = widthValue as number;
    const height: number = heightValue as number;
    const baseline: number = baselineValue === undefined ? height : baselineValue as number;
    if (svg.length === 0 || !Number.isFinite(width) || !Number.isFinite(height) ||
      width <= 0 || height <= 0) {
      return MathRender.failed('原生公式结果无效');
    }
    // Boundary check for an older/mismatched native library: never hand an
    // unbounded logical size to ImageSource.createPixelMap.
    if (width > MAX_PIXEL_EDGE || height > MAX_PIXEL_EDGE || width * height > MAX_PIXEL_AREA) {
      return MathRender.failed('原生公式结果超出尺寸上限');
    }
    return MathRender.ok(svg, width, height, baseline);
  }

  private remember(key: string, result: MathRender): void {
    if (this.values.has(key)) {
      this.touch(key);
      return;
    }
    this.values.set(key, result);
    this.order.push(key);
    while (this.order.length > CACHE_LIMIT) {
      const oldest: string | undefined = this.order.shift();
      if (oldest !== undefined) {
        this.values.delete(oldest);
      }
    }
  }

  private touch(key: string): void {
    const index: number = this.order.indexOf(key);
    if (index >= 0) {
      this.order.splice(index, 1);
      this.order.push(key);
    }
  }

  private keyFor(latex: string, fontSize: number, color: string, displayMode: boolean): string {
    return fontSize.toString() + '|' + color + '|' + (displayMode ? 'd' : 'i') + '|' + latex;
  }
}

export const nativeMath: NativeMathCache = new NativeMathCache();

/** One text or math slice of an inline sequence, style preserved for text. */
export class InlineMathSegment {
  math: boolean;
  text: string;
  style: MarkdownSpanStyle;

  constructor(math: boolean, text: string, style: MarkdownSpanStyle) {
    this.math = math;
    this.text = text;
    this.style = style;
  }
}

function appendSegments(out: InlineMathSegment[], inlines: MarkdownInline[], base: MarkdownSpanStyle): void {
  for (let i: number = 0; i < inlines.length; i++) {
    const part: MarkdownInline = inlines[i];
    // Dispatch on the data-only `kind` rather than `instanceof`: @Prop deep
    // copies a child's array and the copy may not keep the concrete prototype.
    if (part.kind === MarkdownInlineKind.Text) {
      out.push(new InlineMathSegment(false, (part as MarkdownTextInline).text, base.clone()));
    } else if (part.kind === MarkdownInlineKind.Strong) {
      const next: MarkdownSpanStyle = base.clone();
      next.bold = true;
      appendSegments(out, (part as MarkdownStrongInline).children, next);
    } else if (part.kind === MarkdownInlineKind.Emphasis) {
      const next: MarkdownSpanStyle = base.clone();
      next.italic = true;
      appendSegments(out, (part as MarkdownEmphasisInline).children, next);
    } else if (part.kind === MarkdownInlineKind.Delete) {
      const next: MarkdownSpanStyle = base.clone();
      next.strike = true;
      appendSegments(out, (part as MarkdownDeleteInline).children, next);
    } else if (part.kind === MarkdownInlineKind.Code) {
      const next: MarkdownSpanStyle = base.clone();
      next.code = true;
      out.push(new InlineMathSegment(false, (part as MarkdownCodeInline).code, next));
    } else if (part.kind === MarkdownInlineKind.Link) {
      const link: MarkdownLinkInline = part as MarkdownLinkInline;
      const target = markdownLinkTarget(link.destination);
      if (target === null || target.kind === MarkdownLinkKind.ResourceUri) {
        appendSegments(out, link.children, base);
      } else {
        const next: MarkdownSpanStyle = base.clone();
        next.link = true;
        next.target = target;
        appendSegments(out, link.children, next);
      }
    } else if (part.kind === MarkdownInlineKind.Image) {
      const next: MarkdownSpanStyle = base.clone();
      next.italic = true;
      out.push(new InlineMathSegment(false, (part as MarkdownImageInline).alt, next));
    } else if (part.kind === MarkdownInlineKind.Math) {
      out.push(new InlineMathSegment(true, (part as MarkdownMathInline).latex, base.clone()));
    } else if (part.kind === MarkdownInlineKind.Footnote) {
      const next: MarkdownSpanStyle = base.clone();
      next.footnote = true;
      out.push(new InlineMathSegment(false, '[' + (part as MarkdownFootnoteInline).id + ']', next));
    }
  }
}

/** Splits inline Markdown into text runs and TeX runs without losing math identity. */
export function inlineMathSegments(
  inlines: MarkdownInline[],
  base: MarkdownSpanStyle = new MarkdownSpanStyle()
): InlineMathSegment[] {
  const out: InlineMathSegment[] = [];
  appendSegments(out, inlines, base);
  return out;
}

function roundUp(value: number): number {
  return Math.max(1, Math.ceil(value));
}

/** Decoded pixel bounds for one formula image. */
export class MathPixelSize {
  width: number;
  height: number;

  constructor(width: number, height: number) {
    this.width = width;
    this.height = height;
  }
}

/**
 * Converts a RaTeX logical (vp) result into decode pixels at the actual display
 * density. Both the display-math and inline paths use this helper so the
 * 8192 edge / 4,194,304 area caps can never drift apart.
 */
export function boundedMathPixelSize(
  widthVp: number,
  heightVp: number,
  vp2px: (value: number) => number
): MathPixelSize {
  let width: number = roundUp(vp2px(widthVp));
  let height: number = roundUp(vp2px(heightVp));
  if (width > MAX_PIXEL_EDGE || height > MAX_PIXEL_EDGE) {
    const edgeScale: number = Math.min(MAX_PIXEL_EDGE / width, MAX_PIXEL_EDGE / height);
    width = Math.max(1, Math.floor(width * edgeScale));
    height = Math.max(1, Math.floor(height * edgeScale));
  }
  if (width * height > MAX_PIXEL_AREA) {
    const areaScale: number = Math.sqrt(MAX_PIXEL_AREA / (width * height));
    width = Math.max(1, Math.floor(width * areaScale));
    height = Math.max(1, Math.floor(height * areaScale));
  }
  return new MathPixelSize(width, height);
}

/**
 * Decodes one SVG byte buffer at the bounded target size. The caller owns the
 * returned PixelMap; the ImageSource is always released by this function.
 */
export async function decodeMathPixelMap(
  bytes: Uint8Array,
  widthVp: number,
  heightVp: number,
  vp2px: (value: number) => number
): Promise<image.PixelMap> {
  const target: MathPixelSize = boundedMathPixelSize(widthVp, heightVp, vp2px);
  const source: image.ImageSource = image.createImageSource(bytes.buffer as ArrayBuffer);
  if (source === undefined) {
    throw new Error('原生公式图片解码失败');
  }
  try {
    return await source.createPixelMap({
      desiredSize: {
        width: target.width,
        height: target.height
      }
    });
  } finally {
    source.release().then(() => {}).catch(() => {});
  }
}

/** Convenience wrapper for callers that only have the SVG text. */
export async function decodeMathSvg(
  svg: string,
  widthVp: number,
  heightVp: number,
  vp2px: (value: number) => number
): Promise<image.PixelMap> {
  const bytes: Uint8Array = new util.TextEncoder().encodeInto(svg);
  return decodeMathPixelMap(bytes, widthVp, heightVp, vp2px);
}

/**
 * Renders a display formula as a horizontally scrollable native image.
 */
@Component
export struct MathFormula {
  @Prop @Watch('reload') latex: string = '';
  @Prop @Watch('reload') fontSize: number = 20;
  @Prop @Watch('reload') color: string = '#1A1A1A';
  @Prop @Watch('reload') displayMode: boolean = true;
  @State private pixel: image.PixelMap | undefined = undefined;
  @State private dataUri: string = '';
  @State private renderWidth: number = 0;
  @State private renderHeight: number = 0;
  @State private failure: string = '';
  @State private loading: boolean = true;
  @State private retryable: boolean = false;
  private generation: number = 0;
  private lastKey: string = '';

  aboutToAppear(): void {
    this.reload();
  }

  aboutToDisappear(): void {
    // Invalidate every in-flight promise so a late decode cannot publish to a
    // dead component, then release the decoded bitmap immediately.
    this.generation++;
    this.lastKey = '';
    this.releasePixel();
    this.dataUri = '';
    this.loading = false;
  }

  private releasePixel(): void {
    const previous: image.PixelMap | undefined = this.pixel;
    this.pixel = undefined;
    if (previous !== undefined) {
      previous.release().then(() => {}).catch(() => {});
    }
  }

  private reload(): void {
    if (this.latex.trim().length === 0) {
      this.generation++;
      this.lastKey = '';
      this.releasePixel();
      this.dataUri = '';
      this.failure = Copy.tr('数学公式为空');
      this.retryable = false;
      this.loading = false;
      return;
    }
    const key: string = this.fontSize.toString() + '|' + this.color + '|' +
      (this.displayMode ? 'd' : 'i') + '|' + this.latex;
    if (key === this.lastKey) {
      return;
    }
    this.lastKey = key;
    this.generation++;
    const generation: number = this.generation;
    this.releasePixel();
    this.dataUri = '';
    this.failure = '';
    this.retryable = false;
    this.loading = true;
    nativeMath.render(this.latex, this.fontSize, this.color, this.displayMode)
      .then((result: MathRender) => {
        if (generation !== this.generation) {
          return;
        }
        if (result.error.length > 0) {
          this.failure = result.error;
          this.retryable = retryableFailure(result.error);
          this.loading = false;
          return;
        }
        this.renderWidth = result.width;
        this.renderHeight = result.height;
        this.prepareImage(result, generation);
      })
      .catch((error: Error) => {
        if (generation === this.generation) {
          this.failure = Copy.tr('原生公式渲染失败');
          this.retryable = true;
          this.loading = false;
        }
      });
  }

  private retry(): void {
    // reload() is keyed by lastKey; clearing it lets the retry actually issue a
    // new native job after a transient busy/decode failure.
    this.lastKey = '';
    this.reload();
  }

  private prepareImage(result: MathRender, generation: number): void {
    let bytes: Uint8Array;
    try {
      bytes = new util.TextEncoder().encodeInto(result.svg);
      this.dataUri = 'data:image/svg+xml;base64,' +
        new util.Base64Helper().encodeToStringSync(bytes);
    } catch (error) {
      this.failure = Copy.tr('SVG 编码失败');
      this.retryable = false;
      this.loading = false;
      return;
    }
    // decodeMathPixelMap applies the same 8192x8192 / 4Mi px cap as the inline
    // path and always releases its ImageSource in a finally block.
    const context = this.getUIContext();
    decodeMathPixelMap(
      bytes,
      result.width,
      result.height,
      (value: number): number => context.vp2px(value)
    ).then((pixelMap: image.PixelMap) => {
      if (generation !== this.generation) {
        // A newer reload/disappear won the race: release the stale bitmap
        // promptly and do not publish it to state.
        pixelMap.release().then(() => {}).catch(() => {});
        return;
      }
      const previous: image.PixelMap | undefined = this.pixel;
      this.pixel = pixelMap;
      this.loading = false;
      if (previous !== undefined) {
        previous.release().then(() => {}).catch(() => {});
      }
    }).catch(() => {
      if (generation === this.generation) {
        // Image(pixelMap) is preferred; the data URI keeps a native Image path
        // if this device's ImageSource cannot decode SVG directly. Keep the
        // fallback visible and expose an explicit retry.
        this.retryable = true;
        this.loading = false;
      }
    });
  }

  private showFailure(): boolean {
    return this.failure.length > 0;
  }

  /** Maps only the renderer's fixed failure strings; dynamic values stay verbatim. */
  private failureLabel(): string {
    const known: string[] = [
      '原生公式渲染排队过多，请重试',
      '原生公式渲染失败',
      '原生公式结果不是有效 JSON',
      '原生公式结果缺少 SVG 或尺寸',
      '原生公式结果无效',
      '原生公式结果超出尺寸上限'
    ];
    let detail: string = this.failure;
    for (let index: number = 0; index < known.length; index++) {
      if (this.failure === known[index]) {
        detail = Copy.tr(known[index]);
        break;
      }
    }
    return Copy.fill('公式渲染失败：{0}', [detail]);
  }

  @Builder
  RetryButton() {
    Button(Copy.tr('重试渲染'))
      .fontSize(11)
      .height(24)
      .padding({ left: 10, right: 10 })
      .backgroundColor('#22000000')
      .fontColor(this.color)
      .onClick(() => {
        this.retry();
      })
  }

  @Builder
  FailureView() {
    Column({ space: 2 }) {
      Text(this.latex)
        .fontSize(Math.max(11, Math.min(this.fontSize, 18)))
        .fontColor(this.color)
        .fontFamily('monospace')
        .width('100%')
      Text(this.failure.length > 0 ? this.failureLabel() :
        Copy.tr('公式渲染失败，已保留 TeX 源'))
        .fontSize(10)
        .fontColor('#C0392B')
        .width('100%')
      if (this.retryable) {
        this.RetryButton()
      }
    }
    .width('100%')
    .padding(6)
    .backgroundColor('#14000000')
    .borderRadius(4)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  RenderedView() {
    if (this.displayMode) {
      Scroll() {
        if (this.pixel !== undefined) {
          Image(this.pixel)
            .width(this.renderWidth)
            .height(this.renderHeight)
            .objectFit(ImageFit.Contain)
        } else {
          Image(this.dataUri)
            .width(this.renderWidth)
            .height(this.renderHeight)
            .objectFit(ImageFit.Contain)
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Off)
      .width('100%')
    } else {
      if (this.pixel !== undefined) {
        Image(this.pixel)
          .width(this.renderWidth)
          .height(this.renderHeight)
          .objectFit(ImageFit.Contain)
      } else {
        Image(this.dataUri)
          .width(this.renderWidth)
          .height(this.renderHeight)
          .objectFit(ImageFit.Contain)
      }
    }
  }

  build() {
    if (this.showFailure()) {
      this.FailureView()
    } else if (this.renderWidth > 0 && this.renderHeight > 0 && (this.pixel !== undefined || this.dataUri.length > 0)) {
      if (this.retryable) {
        // The SVG data URI is still a usable native Image fallback; keep it
        // visible and offer an explicit retry for the failed PixelMap decode.
        Column({ space: 2 }) {
          this.RenderedView()
          this.RetryButton()
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
      } else {
        this.RenderedView()
      }
    } else {
      LoadingProgress()
        .width(Math.max(12, Math.min(this.fontSize, 24)))
        .height(Math.max(12, Math.min(this.fontSize, 24)))
        .color(this.color)
    }
  }
}
