// SPDX-License-Identifier: MIT
import { Configuration, ConfigurationConstant, UIAbility } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';

export default class EntryAbility extends UIAbility {
  onCreate(): void {
    this.context.getApplicationContext().setColorMode(ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET);
    this.publishSystemTheme(this.context.config);
  }

  onConfigurationUpdate(configuration: Configuration): void {
    this.publishSystemTheme(configuration);
  }

  onWindowStageCreate(stage: window.WindowStage): void {
    const mainWindow: window.Window = stage.getMainWindowSync();
    mainWindow.on('keyboardHeightChange', (height: number): void => {
      AppStorage.setOrCreate('pdshKeyboardHeight', height);
    });
    stage.loadContent('pages/Index', (error): void => {
      if (error.code !== 0) {
        hilog.error(0x1A20, 'PhoneDSH', 'Product page could not load, code=%{public}d', error.code);
      }
    });
  }

  private publishSystemTheme(configuration: Configuration): void {
    if (configuration.colorMode !== undefined) {
      AppStorage.setOrCreate('pdshSystemDark',
        configuration.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK);
    }
  }
}
