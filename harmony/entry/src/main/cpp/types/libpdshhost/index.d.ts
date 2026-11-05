// SPDX-License-Identifier: MIT
export const start: (filesRoot: string, assetsRoot: string) => Promise<string>;
export const stop: () => void;
export const status: () => string;
export const setEventCallback: (callback: (eventJson: string) => void) => void;
export const respond: (requestId: number, kind: number, status: number, headersJson: string, body: ArrayBuffer) => void;
export const installBundle: (zipFile: string, assetRoot: string) => Promise<void>;
export const workspaceChanges: (fromMseq: string, toMseq: string) => Promise<string>;
export const workspaceWatermark: () => string;

// Resolves to JSON {svg,width,height,baseline} or {error}.
export const renderMath: (latex: string, optionsJson: string) => Promise<string>;
