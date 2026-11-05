// SPDX-License-Identifier: MIT
// Native icon renderer. Every mark is the generated Android vector from
// resources/rawfile/icons; text glyphs are never used as icons.

@Component
export struct NativeIcon {
  @Prop name: string = '';
  @Prop symbol: string = '';
  @Prop dark: boolean = false;
  @Prop iconSize: number = 20;
  @Prop muted: boolean = false;

  private assetBase(): string {
    switch (this.symbol) {
      case 'NEW_CHAT': return 'NEW_CHAT';
      case 'CLOCK': return 'CLOCK';
      case 'GEAR': return 'GEAR';
      case 'FOLDER': return 'FOLDER';
      case 'SESSIONS': return 'SESSIONS';
      case 'ARCHIVE': return 'ARCHIVE';
      case 'FOLDER_PLUS': return 'FOLDER_PLUS';
      case 'CHEVRON_DOWN': return 'CHEVRON_DOWN';
      case 'CHEVRON_UP': return 'CHEVRON_UP';
      case 'CHEVRON_RIGHT': return 'CHEVRON_RIGHT';
      case 'CHEVRON_LEFT': return 'CHEVRON_LEFT';
      case 'CLOSE': return 'CLOSE';
      case 'CHECK': return 'CHECK';
      case 'COPY': return 'COPY';
      case 'TEXT_ALIGN_LEFT': return 'TEXT_ALIGN_LEFT';
      case 'ARROW_LEFT_RIGHT': return 'ARROW_LEFT_RIGHT';
      case 'THINK': return 'THINK';
      case 'READ': return 'READ';
      case 'EDIT': return 'EDIT';
      case 'CODE': return 'CODE';
      case 'SEARCH': return 'SEARCH';
      case 'TERMINAL': return 'TERMINAL';
      case 'SPARKLE': return 'SPARKLE';
      case 'BRANCH': return 'BRANCH';
      case 'DATABASE': return 'DATABASE';
      case 'GAUGE': return 'GAUGE';
      case 'PLUS': return 'PLUS';
      case 'MORE': return 'MORE';
      case 'REFRESH': return 'REFRESH';
      case 'CPU': return 'CPU';
      case 'PEOPLE': return 'PEOPLE';
      case 'PUZZLE': return 'PUZZLE';
      case 'LOCK_SHIELD': return 'LOCK_SHIELD';
      case 'HAND': return 'HAND';
      case 'NETWORK': return 'NETWORK';
      case 'GLOBE': return 'GLOBE';
      case 'TEXT_SIZE': return 'TEXT_SIZE';
      case 'LIST': return 'LIST';
      case 'COMMAND': return 'COMMAND';
      case 'BOLT': return 'BOLT';
      case 'WAVEFORM': return 'WAVEFORM';
      case 'INFO': return 'INFO';
      case 'DOCUMENT': return 'DOCUMENT';
      case 'NUMBER': return 'NUMBER';
      case 'CHAT_MORE': return 'CHAT_MORE';
      case 'MENU': return 'MENU';
      case 'CHAT_DRAWER': return 'CHAT_DRAWER';
      case 'DEVICE': return 'DEVICE';
      case 'DEVICE_RADIO': return 'DEVICE_RADIO';
      case 'CHAT_BUBBLE': return 'CHAT_BUBBLE';
      case 'SEND': return 'SEND';
      case 'WARNING': return 'WARNING';
      case 'TRASH': return 'TRASH';
      case 'PENCIL': return 'PENCIL';
      case 'MICROPHONE_PERMISSION': return 'MICROPHONE_PERMISSION';
      // The Android registry has no stop vector: the source stop control is a
      // small filled square, drawn here with the actual button ink.
      case 'STOP': return 'STOP';
      // The Android font steppers draw their minus mark as a line, not a glyph.
      case 'MINUS': return 'MINUS';
      default: return '';
    }
  }

  private assetPath(): string {
    const base: string = this.assetBase();
    if (base.length === 0) {
      if (this.symbol.length === 0) {
        return '';
      }
      throw new Error('NativeIcon: unknown symbol "' + this.symbol + '"');
    }
    if (base === 'STOP' || base === 'MINUS') {
      return '';
    }
    return 'icons/' + base + (this.dark ? '-dark.svg' : '-light.svg');
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      if (this.symbol === 'STOP') {
        Row()
          .width(this.iconSize * 0.5)
          .height(this.iconSize * 0.5)
          .borderRadius(1.5)
          .backgroundColor(this.dark ? '#111111' : '#FFFFFF')
          .opacity(this.muted ? 0.55 : 1)
      } else if (this.symbol === 'MINUS') {
        Row()
          .width(this.iconSize)
          .height(2)
          .borderRadius(1)
          .backgroundColor(this.dark ? '#F2F2F2' : '#1A1A1A')
          .opacity(this.muted ? 0.55 : 1)
      } else if (this.symbol.length > 0) {
        Image($rawfile(this.assetPath()))
          .width(this.iconSize)
          .height(this.iconSize)
          .objectFit(ImageFit.Contain)
          .opacity(this.muted ? 0.55 : 1)
      }
    }
    .width(this.iconSize)
    .height(this.iconSize)
    .accessibilityText(this.name)
  }
}
