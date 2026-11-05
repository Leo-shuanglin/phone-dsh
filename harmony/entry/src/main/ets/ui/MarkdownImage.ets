import { ImageLoadControl } from '../model/ImageLoadControl';
// SPDX-License-Identifier: MIT
// Native ArkUI image body: real ImageKit decode of the bytes the typed page
// loader returns. The component owns the PixelMap/ImageSource and releases both
// on source replacement, retry and exit. No WebView and no mock decoder.

import { image } from '@kit.ImageKit';
import { ImageBytes } from '../model/Contracts';
import { Palette, Metrics, Copy } from './Palette';
import {
  boundedDecodeSize,
  DecodeSize
} from '../model/ResourceLocator';

const LOADING: string = 'loading';
const READY: string = 'ready';
const FAILED: string = 'failed';

/**
 * Markdown image thumbnail with loading/failure/retry states and a real
 * full-screen viewer. `loadImage` is supplied by the page adapter and returns
 * component-owned bytes; it is never backed by ShellSnapshot state.
 */
@Component
export struct MarkdownImage {
  @Prop @Watch('onSourceChanged') source: string = '';
  @Prop alt: string = '';
  @Prop dark: boolean = false;
  @Prop bodyFontSize: number = 0;
  loadImage?: (source: string, control?: ImageLoadControl) => Promise<ImageBytes>;
  @State private phase: string = LOADING;
  @State private failure: string = '';
  @State private pixelMap: image.PixelMap | undefined = undefined;
  @State private pixelWidth: number = 0;
  @State private pixelHeight: number = 0;
  @State private viewerOpen: boolean = false;
  private generation: number = 0;
  private loadControl: ImageLoadControl = new ImageLoadControl();

  aboutToAppear(): void {
    this.reload();
  }

  onSourceChanged(): void {
    this.reload();
  }

  aboutToDisappear(): void {
    this.loadControl.cancel();
    this.generation += 1;
    this.releaseCurrent();
  }

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private captionSize(): number {
    return this.bodyFontSize > 0 ? this.bodyFontSize : Metrics.bodySize;
  }

  private label(): string {
    if (this.alt.length > 0) {
      return this.alt;
    }
    return Copy.tr('图片');
  }

  private releaseCurrent(): void {
    const current: image.PixelMap | undefined = this.pixelMap;
    this.pixelMap = undefined;
    this.pixelWidth = 0;
    this.pixelHeight = 0;
    if (current !== undefined) {
      current.release().catch((): void => {
      });
    }
  }

  private reload(): void {
    this.loadControl.cancel();
    this.loadControl = new ImageLoadControl();
    this.generation += 1;
    const generation: number = this.generation;
    this.releaseCurrent();
    this.viewerOpen = false;
    this.failure = '';
    this.phase = LOADING;
    this.loadFor(generation).catch((error: Error): void => {
      if (generation === this.generation) {
        this.fail(error.message);
      }
    });
  }

  private fail(message: string): void {
    this.releaseCurrent();
    this.phase = FAILED;
    this.failure = message.length > 0 ? message : Copy.tr('图片加载失败');
  }

  private async loadFor(generation: number): Promise<void> {
    const loader = this.loadImage;
    if (loader === undefined) {
      this.fail(Copy.tr('未配置图片加载器'));
      return;
    }
    if (this.source.trim().length === 0) {
      this.fail(Copy.tr('图片地址为空'));
      return;
    }
    let payload: ImageBytes;
    try {
      payload = await loader(this.source, this.loadControl);
    } catch (error) {
      if (generation === this.generation) {
        this.fail((error as Error).message);
      }
      return;
    }
    if (generation !== this.generation) {
      return;
    }
    if (payload.data.byteLength === 0) {
      this.fail(Copy.tr('图片数据为空'));
      return;
    }
    let imageSource: image.ImageSource | undefined = undefined;
    let decoded: image.PixelMap | undefined = undefined;
    try {
      imageSource = image.createImageSource(payload.data);
      const info: image.ImageInfo = await imageSource.getImageInfo();
      if (generation !== this.generation) {
        return;
      }
      const bounded: DecodeSize = boundedDecodeSize(info.size.width, info.size.height);
      decoded = await imageSource.createPixelMap({
        desiredSize: { width: bounded.width, height: bounded.height },
        editable: false
      });
      if (generation !== this.generation) {
        decoded.release().catch((): void => {
        });
        return;
      }
      this.pixelMap = decoded;
      this.pixelWidth = bounded.width;
      this.pixelHeight = bounded.height;
      this.phase = READY;
    } catch (error) {
      if (decoded !== undefined) {
        decoded.release().catch((): void => {
        });
      }
      if (generation === this.generation) {
        this.fail((error as Error).message);
      }
    } finally {
      if (imageSource !== undefined) {
        imageSource.release().catch((): void => {
        });
      }
    }
  }

  private displayWidth(): number {
    if (this.pixelWidth <= 0) {
      return 0;
    }
    return Math.min(this.pixelWidth, 640);
  }

  private displayHeight(): number {
    if (this.pixelWidth <= 0 || this.pixelHeight <= 0) {
      return 0;
    }
    const ratio: number = this.pixelHeight / this.pixelWidth;
    return Math.max(1, Math.min(this.displayWidth() * ratio, 360));
  }

  @Builder
  Viewer() {
    Stack({ alignContent: Alignment.Center }) {
      if (this.pixelMap !== undefined) {
        Image(this.pixelMap as image.PixelMap)
          .objectFit(ImageFit.Contain)
          .width('100%')
          .height('100%')
      }
      Text(Copy.tr('轻触关闭'))
        .fontSize(Metrics.captionSize)
        .fontColor('#B3FFFFFF')
        .position({ x: 16, y: 48 })
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FF000000')
    .onClick((): void => {
      this.viewerOpen = false;
    })
  }

  build() {
    Column({ space: 4 }) {
      if (this.phase === LOADING) {
        Row({ space: 8 }) {
          LoadingProgress()
            .width(18)
            .height(18)
            .color(this.pal().textSecondary)
          Text(Copy.tr('图片加载中…'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().textTertiary)
        }
        .height(80)
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .id('markdown.image.loading')
      } else if (this.phase === READY && this.pixelMap !== undefined) {
        Image(this.pixelMap as image.PixelMap)
          .objectFit(ImageFit.Contain)
          .width(this.displayWidth())
          .height(this.displayHeight())
          .borderRadius(8)
          .clip(true)
          .onClick((): void => {
            this.viewerOpen = true;
          })
          .id('markdown.image')
      } else {
        Column({ space: 2 }) {
          Text(this.label())
            .fontSize(this.captionSize())
            .fontColor(this.pal().textTertiary)
            .fontStyle(FontStyle.Italic)
            .width('100%')
          Text(Copy.tr('图片加载失败：') + this.failure)
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().sessionDanger)
            .width('100%')
          Text(Copy.tr('重试'))
            .fontSize(Metrics.captionSize)
            .fontColor(this.pal().sessionAccent)
            .margin({ top: 4 })
            .onClick((): void => {
              this.reload();
            })
            .id('markdown.image.retry')
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .id('markdown.image.failed')
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .bindContentCover(this.viewerOpen, this.Viewer(), {
      modalTransition: ModalTransition.DEFAULT
    })
  }
}
