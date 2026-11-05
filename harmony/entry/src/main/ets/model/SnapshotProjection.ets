// SPDX-License-Identifier: MIT
import { Settings, ShellSnapshot } from './Contracts';

export function detachedSettings(source: Settings): Settings {
  return JSON.parse(JSON.stringify(source)) as Settings;
}

export function detachedSnapshot(source: ShellSnapshot): ShellSnapshot {
  return JSON.parse(JSON.stringify(source)) as ShellSnapshot;
}

export function publishSnapshot(source: ShellSnapshot, listener: (view: ShellSnapshot) => void): void {
  listener(detachedSnapshot(source));
}
