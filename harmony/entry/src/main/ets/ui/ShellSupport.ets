// SPDX-License-Identifier: MIT
// Shared native chrome. Every control carries a stable test id and every icon
// carries an accessibility label so DevEco CLI UITest can address it.

import { Palette, Metrics, Copy } from './Palette';
import { NativeIcon } from './NativeIcon';

@Component
export struct NavHeader {
  @Prop title: string = '';
  @Prop subtitle: string = '';
  @Prop dark: boolean = false;
  @Prop showBack: boolean = true;
  @Prop rightText: string = '';
  @Prop rightGlyph: string = '';
  @Prop backId: string = 'nav.back';
  @Prop rightId: string = 'nav.right';
  onBack: () => void = () => {};
  onRight: () => void = () => {};

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Row() {
        if (this.showBack) {
          NativeIcon({ name: '返回', symbol: 'CHEVRON_LEFT', dark: this.dark, iconSize: 22 })
            .width(Metrics.navButton)
            .height(Metrics.navButton)
            .borderRadius(Metrics.navButton / 2)
            .backgroundColor(this.pal().surface)
            .accessibilityText(Copy.tr('返回'))
            .id(this.backId)
            .onClick(() => {
              this.onBack();
            })
        } else {
          Blank().width(Metrics.navButton)
        }
        Blank().layoutWeight(1)
        if (this.rightText.length > 0) {
          Text(this.rightText)
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.pal().textPrimary)
            .height(36)
            .padding({ left: 16, right: 16 })
            .borderRadius(18)
            .backgroundColor(this.pal().surface)
            .id(this.rightId)
            .onClick(() => {
              this.onRight();
            })
        } else if (this.rightGlyph.length > 0) {
          NativeIcon({
            name: this.rightText.length > 0 ? this.rightText : '更多',
            symbol: this.rightGlyph,
            dark: this.dark,
            iconSize: 20
          })
            .width(Metrics.navButton)
            .height(Metrics.navButton)
            .borderRadius(Metrics.navButton / 2)
            .backgroundColor(this.pal().surface)
            .accessibilityText(this.rightText.length > 0 ? this.rightText : Copy.tr('更多'))
            .id(this.rightId)
            .onClick(() => {
              this.onRight();
            })
        } else {
          Blank().width(Metrics.navButton)
        }
      }
      .width('100%')
      .height(56)

      Column() {
        Text(this.title)
          .fontSize(Metrics.navTitleSize)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.pal().textPrimary)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.subtitle.length > 0) {
          Text(this.subtitle)
            .fontSize(Metrics.navSubtitleSize)
            .fontColor(this.pal().textSubtle)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 1 })
        }
      }
      .alignItems(HorizontalAlign.Center)
      .constraintSize({ maxWidth: '60%' })
    }
    .width('100%')
    .padding({ left: Metrics.chromeInset, right: Metrics.chromeInset, top: Metrics.navTop + 4, bottom: 4 })
  }
}

@Component
export struct SectionLabel {
  @Prop label: string = '';
  @Prop dark: boolean = false;

  build() {
    Text(this.label)
      .fontSize(Metrics.captionSize)
      .fontColor(new Palette(this.dark).textSubtle)
      .fontWeight(FontWeight.Medium)
      .width('100%')
      .padding({ left: 8, top: 18, bottom: 8 })
  }
}

@Component
export struct InfoRow {
  @Prop label: string = '';
  @Prop value: string = '';
  @Prop dark: boolean = false;
  @Prop showDivider: boolean = true;
  @Prop valueColor: string = '';

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      Row() {
        Text(this.label)
          .fontSize(Metrics.bodySize)
          .fontColor(this.pal().textPrimary)
          .layoutWeight(1)
        Text(this.value.length > 0 ? this.value : '—')
          .fontSize(15)
          .fontColor(this.valueColor.length > 0 ? this.valueColor : this.pal().textSubtle)
          .maxLines(2)
          .textAlign(TextAlign.End)
          .margin({ left: 12 })
      }
      .width('100%')
      .constraintSize({ minHeight: Metrics.rowHeight })
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .alignItems(VerticalAlign.Center)
      if (this.showDivider) {
        Text('')
          .height(Metrics.hairline)
          .width('100%')
          .backgroundColor(this.pal().borderList)
      }
    }
  }
}

@Component
export struct NavRow {
  @Prop label: string = '';
  @Prop value: string = '';
  @Prop glyph: string = '';
  @Prop dark: boolean = false;
  @Prop rowEnabled: boolean = true;
  @Prop showDivider: boolean = true;
  @Prop showChevron: boolean = true;
  @Prop rowId: string = '';
  onTap: () => void = () => {};

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      Row() {
        if (this.glyph.length > 0) {
          NativeIcon({
            name: this.label,
            symbol: this.glyph,
            dark: this.dark,
            iconSize: 17,
            muted: !this.rowEnabled
          })
            .width(28)
            .height(24)
        }
        Text(this.label)
          .fontSize(Metrics.bodySize)
          .fontColor(this.rowEnabled ? this.pal().textPrimary : this.pal().textTertiary)
          .layoutWeight(1)
          .margin({ left: this.glyph.length > 0 ? 8 : 0 })
        if (this.value.length > 0) {
          Text(this.value)
            .fontSize(15)
            .fontColor(this.pal().textSubtle)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ left: 8 })
        }
        if (this.showChevron) {
          NativeIcon({ name: '进入', symbol: 'CHEVRON_RIGHT', dark: this.dark, iconSize: 18, muted: true })
            .width(18)
            .height(18)
            .margin({ left: 4 })
        }
      }
      .width('100%')
      .constraintSize({ minHeight: Metrics.rowHeight })
      .padding({ left: 16, right: 14, top: 8, bottom: 8 })
      .alignItems(VerticalAlign.Center)
      .opacity(this.rowEnabled ? 1 : 0.55)
      .id(this.rowId)
      .onClick(() => {
        if (this.rowEnabled) {
          this.onTap();
        }
      })
      if (this.showDivider) {
        Text('')
          .height(Metrics.hairline)
          .width('100%')
          .backgroundColor(this.pal().borderList)
      }
    }
  }
}

@Component
export struct SwitchRow {
  @Prop label: string = '';
  @Prop detail: string = '';
  @Prop checked: boolean = false;
  @Prop dark: boolean = false;
  @Prop rowEnabled: boolean = true;
  @Prop showDivider: boolean = true;
  @Prop rowId: string = '';
  onChange: (value: boolean) => void = () => {};

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      Row() {
        Column() {
          Text(this.label)
            .fontSize(Metrics.bodySize)
            .fontColor(this.rowEnabled ? this.pal().textPrimary : this.pal().textTertiary)
          if (this.detail.length > 0) {
            Text(this.detail)
              .fontSize(Metrics.captionSize)
              .fontColor(this.pal().textSubtle)
              .margin({ top: 2 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.checked })
          .selectedColor(this.pal().sessionAccent)
          .enabled(this.rowEnabled)
          .id(this.rowId)
          .onChange((value: boolean) => {
            this.onChange(value);
          })
          .accessibilityText(this.label)
      }
      .width('100%')
      .constraintSize({ minHeight: Metrics.rowHeight })
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .alignItems(VerticalAlign.Center)
      if (this.showDivider) {
        Text('')
          .height(Metrics.hairline)
          .width('100%')
          .backgroundColor(this.pal().borderList)
      }
    }
  }
}

@Component
export struct PillButton {
  @Prop label: string = '';
  @Prop dark: boolean = false;
  @Prop kind: string = 'primary';
  @Prop disabled: boolean = false;
  @Prop buttonId: string = '';
  @Prop stretch: boolean = false;
  @Prop compact: boolean = false;
  onTap: () => void = () => {};

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private fill(): string {
    if (this.kind === 'secondary') { return this.pal().surface; }
    if (this.kind === 'danger') { return this.pal().pageDanger; }
    if (this.kind === 'ghost') { return '#00000000'; }
    return this.pal().primaryButton;
  }

  private ink(): string {
    if (this.kind === 'secondary') { return this.pal().textPrimary; }
    if (this.kind === 'danger') { return '#FFFFFF'; }
    if (this.kind === 'ghost') { return this.pal().sessionAccent; }
    return this.pal().primaryButtonInk;
  }

  build() {
    if (this.stretch) {
      Row() {
        Text(this.label)
          .fontSize(this.compact ? 14 : Metrics.buttonLabelSize)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.ink())
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .height(this.compact ? 36 : Metrics.primaryButtonHeight)
      .padding({ left: this.compact ? 14 : 20, right: this.compact ? 14 : 20 })
      .borderRadius(this.compact ? 18 : Metrics.primaryButtonHeight / 2)
      .backgroundColor(this.fill())
      .justifyContent(FlexAlign.Center)
      .width('100%')
      .opacity(this.disabled ? 0.4 : 1)
      .id(this.buttonId)
      .onClick(() => {
        if (!this.disabled) {
          this.onTap();
        }
      })
    } else {
      Row() {
        Text(this.label)
          .fontSize(this.compact ? 14 : Metrics.buttonLabelSize)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.ink())
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .height(this.compact ? 36 : Metrics.primaryButtonHeight)
      .padding({ left: this.compact ? 14 : 20, right: this.compact ? 14 : 20 })
      .borderRadius(this.compact ? 18 : Metrics.primaryButtonHeight / 2)
      .backgroundColor(this.fill())
      .justifyContent(FlexAlign.Center)
      .opacity(this.disabled ? 0.4 : 1)
      .id(this.buttonId)
      .onClick(() => {
        if (!this.disabled) {
          this.onTap();
        }
      })
    }
  }
}

@Component
export struct EmptyState {
  @Prop title: string = '';
  @Prop hint: string = '';
  @Prop glyph: string = '';
  @Prop dark: boolean = false;

  private pal(): Palette {
    return new Palette(this.dark);
  }

  build() {
    Column() {
      if (this.glyph.length > 0) {
        NativeIcon({
          name: this.title,
          symbol: this.glyph,
          dark: this.dark,
          iconSize: 28,
          muted: true
        })
          .width(28)
          .height(28)
          .margin({ bottom: 8 })
      }
      Text(this.title)
        .fontSize(Metrics.bodySize)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.pal().textPrimary)
        .textAlign(TextAlign.Center)
      if (this.hint.length > 0) {
        Text(this.hint)
          .fontSize(Metrics.secondarySize)
          .fontColor(this.pal().textSubtle)
          .textAlign(TextAlign.Center)
          .lineHeight(20)
          .margin({ top: 6 })
      }
    }
    .width('100%')
    .padding({ left: 32, right: 32, top: 48, bottom: 48 })
    .alignItems(HorizontalAlign.Center)
  }
}

@Component
export struct LoadingBlock {
  @Prop label: string = Copy.tr('正在读取…');
  @Prop dark: boolean = false;

  build() {
    Row() {
      LoadingProgress()
        .width(22)
        .height(22)
        .color(new Palette(this.dark).textSecondary)
      Text(this.label)
        .fontSize(Metrics.secondarySize)
        .fontColor(new Palette(this.dark).textSecondary)
        .margin({ left: 10 })
    }
    .width('100%')
    .justifyContent(FlexAlign.Center)
    .padding({ top: 40, bottom: 40 })
  }
}

@Component
export struct NoticeBanner {
  @Prop text: string = '';
  @Prop dark: boolean = false;
  @Prop kind: string = 'error';
  @Prop showClose: boolean = false;
  @Prop bannerId: string = '';
  onClose: () => void = () => {};

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private color(): string {
    if (this.kind === 'warning') { return this.pal().pageWarning; }
    if (this.kind === 'info') { return this.pal().textSecondary; }
    return this.pal().pageDanger;
  }

  build() {
    Row() {
      Text(this.text)
        .fontSize(Metrics.secondarySize)
        .fontColor(this.color())
        .lineHeight(19)
        .maxLines(4)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .copyOption(CopyOptions.LocalDevice)
        .layoutWeight(1)
      if (this.showClose) {
        NativeIcon({ name: '关闭提示', symbol: 'CLOSE', dark: this.dark, iconSize: 18 })
          .width(36)
          .height(36)
          .id(this.bannerId + '.close')
          .onClick(() => {
            this.onClose();
          })
      }
    }
    .width('100%')
    .padding({ left: 4, right: 4, top: 6, bottom: 6 })
  }
}

@Component
export struct Tag {
  @Prop text: string = '';
  @Prop dark: boolean = false;
  @Prop tone: string = 'neutral';

  private pal(): Palette {
    return new Palette(this.dark);
  }

  private ink(): string {
    if (this.tone === 'success') { return this.pal().sessionSuccess; }
    if (this.tone === 'danger') { return this.pal().sessionDanger; }
    if (this.tone === 'warning') { return this.pal().sessionWarning; }
    if (this.tone === 'accent') { return this.pal().sessionAccent; }
    return this.pal().textSecondary;
  }

  build() {
    Text(this.text)
      .fontSize(Metrics.captionSize)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.ink())
      .padding({ left: 8, right: 8, top: 3, bottom: 3 })
      .borderRadius(9)
      .backgroundColor(this.pal().sessionSelector)
  }
}

@Component
export struct Hairline {
  @Prop dark: boolean = false;

  build() {
    Text('')
      .height(Metrics.hairline)
      .width('100%')
      .backgroundColor(new Palette(this.dark).borderList)
  }
}
