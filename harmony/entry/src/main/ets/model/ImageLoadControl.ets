// SPDX-License-Identifier: MIT
export class ImageLoadControl {
  private stopped: boolean = false;
  private handlers: (() => void)[] = [];

  cancel(): void {
    if (this.stopped) return;
    this.stopped = true;
    const pending = this.handlers;
    this.handlers = [];
    for (let index: number = 0; index < pending.length; index++) pending[index]();
  }

  assertActive(): void {
    if (this.stopped) throw new Error('Image load cancelled');
  }

  subscribe(handler: () => void): () => void {
    if (this.stopped) { handler(); return (): void => {}; }
    this.handlers.push(handler);
    return (): void => {
      const remaining: (() => void)[] = [];
      for (let index: number = 0; index < this.handlers.length; index++) {
        if (this.handlers[index] !== handler) remaining.push(this.handlers[index]);
      }
      this.handlers = remaining;
    };
  }
}
