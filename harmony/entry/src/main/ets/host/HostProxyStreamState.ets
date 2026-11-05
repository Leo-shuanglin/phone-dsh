// SPDX-License-Identifier: MIT
// Pure state for one streaming outbound exchange. RCP delivers status, headers,
// body chunks, and data-end through separate callbacks whose order is not part
// of the public contract. This type records what actually arrived, so the proxy
// can stream only after the real status is known and can finish exactly once
// regardless of whether data-end or the fetch promise arrives first.

export class ProxyStreamState {
  status: number = 0;
  statusKnown: boolean = false;
  headersSeen: boolean = false;
  headSent: boolean = false;
  dataSeen: boolean = false;
  dataEnded: boolean = false;
  rejected: boolean = false;
  finished: boolean = false;
  pendingBytes: number = 0;

  /**
   * Records a final status. 1xx responses are not the final
   * response and are ignored; a second final status is not allowed to rewrite
   * the first one.
   */
  receiveStatus(code: number): boolean {
    if (this.finished || this.statusKnown) {
      return false;
    }
    if (code < 100 || code > 599 || code < 200) {
      return false;
    }
    this.statusKnown = true;
    this.status = code;
    this.rejected = code > 299;
    return true;
  }

  receiveHeaders(): boolean {
    if (this.finished || this.headersSeen) {
      return false;
    }
    this.headersSeen = true;
    return true;
  }

  noteData(): void {
    if (!this.finished) {
      this.dataSeen = true;
    }
  }

  /**
   * Accounts for a chunk that must wait for status. Returns true when the
   * bounded pending buffer overflowed. Rejected exchanges never buffer data.
   */
  queuePending(size: number, limit: number): boolean {
    if (this.finished || this.rejected || this.headSent) {
      return false;
    }
    this.pendingBytes += size;
    return this.pendingBytes > limit;
  }

  /**
   * A head may be sent only after the real final status is known and it is a
   * success. Waiting for headers first preserves them; a first body chunk is
   * also enough to start streaming.
   */
  headReady(): boolean {
    return !this.finished && !this.rejected && this.statusKnown &&
      this.status >= 200 && this.status <= 299 && !this.headSent &&
      (this.headersSeen || this.dataSeen);
  }

  markHeadSent(): void {
    this.headSent = true;
    this.pendingBytes = 0;
  }

  noteDataEnd(): void {
    if (!this.finished) {
      this.dataEnded = true;
    }
  }

  /**
   * data-end alone cannot finish before the status is known: that would force
   * a guessed status. The fetch promise is terminal because it carries the
   * actual status and headers.
   */
  readyToFinish(fetchSettled: boolean): boolean {
    if (this.finished) {
      return false;
    }
    if (fetchSettled) {
      return true;
    }
    return this.dataEnded && this.statusKnown;
  }

  success(): boolean {
    return this.statusKnown && !this.rejected && this.status >= 200 && this.status <= 299;
  }

  finish(): boolean {
    if (this.finished) {
      return false;
    }
    this.finished = true;
    return true;
  }
}

export function proxyRejectMessage(status: number): string {
  if (status >= 300 && status <= 399) {
    return 'upstream_redirect_refused';
  }
  return 'upstream_status';
}
