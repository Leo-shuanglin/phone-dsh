// SPDX-License-Identifier: MIT
// The ArkWeb page bridge. The page half is the production WorkerTunnel adapter:
// native calls arrive as __PDSH_NATIVE_CALL__ frames and every answer/stream is
// posted back through PdshNative.postMessage. No token or key is ever sent here.
import { webview } from '@kit.ArkWeb';
import {
  BinaryChunkReply,
  BinaryCloseReply,
  BinaryOpenReply,
  BridgeCallFrame,
  CloseBinaryReadArgs,
  CloseStreamArgs,
  CommandArgs,
  EventsArgs,
  FollowArgs,
  ListFilesArgs,
  OpenBinaryReadArgs,
  ReadBinaryChunkArgs,
  ReadFileArgs,
  WriteFileArgs,
  asRecord,
  jsonBool,
  jsonNumber,
  jsonString,
  parseJsonObject
} from './HostUtil';

export interface PageBridgeListener {
  onBridgeReady(): void;
  onBridgeError(message: string): void;
  onBridgeStreamEvent(streamId: string, value: Object): void;
  onBridgeStreamEnd(streamId: string, error: string): void;
}

class PendingCall {
  resolve: (value: Object) => void = () => {
  };
  reject: (error: Error) => void = () => {
  };
  timer: number = -1;
}

class EmptyReplyValue {
}

export class PageBridge {
  private listener: PageBridgeListener;
  private controller: webview.WebviewController | null = null;
  private pending: Map<number, PendingCall> = new Map();
  private nextId: number = 1;
  private closed: boolean = false;

  constructor(listener: PageBridgeListener) {
    this.listener = listener;
  }

  attach(controller: webview.WebviewController): void {
    this.controller = controller;
  }

  reloadRuntime(): void {
    if (this.controller === null || this.closed) throw new Error('本地运行时页面桥尚未附加');
    for (const call of this.pending.values()) {
      clearTimeout(call.timer);
      call.reject(new Error('本地运行时正在重新加载'));
    }
    this.pending.clear();
    this.controller.refresh();
  }

  isAttached(): boolean {
    return this.controller !== null;
  }

  terminateRenderer(): boolean {
    if (this.controller === null || this.closed) {
      throw new Error('本地运行时页面桥尚未附加');
    }
    return this.controller.terminateRenderProcess();
  }

  call(method: string, args: Object, timeoutMillis: number): Promise<Object> {
    if (this.controller === null || this.closed) {
      return Promise.reject(new Error('本地运行时页面桥尚未附加'));
    }
    const id = this.nextId;
    this.nextId = this.nextId + 1;
    const frame: BridgeCallFrame = { id: id, method: method, args: args };
    return new Promise<Object>((resolve: (value: Object) => void, reject: (error: Error) => void) => {
      const pending = new PendingCall();
      pending.resolve = resolve;
      pending.reject = reject;
      pending.timer = setTimeout(() => {
        if (!this.pending.has(id)) {
          return;
        }
        this.pending.delete(id);
        reject(new Error(`${method} 在 ${Math.floor(timeoutMillis / 1000)} 秒内没有返回`));
      }, timeoutMillis);
      this.pending.set(id, pending);
      const controller = this.controller as webview.WebviewController;
      const script = `if (typeof __PDSH_NATIVE_CALL__ === 'function') { __PDSH_NATIVE_CALL__(${JSON.stringify(frame)}); }`;
      controller.runJavaScript(script).catch((error: Error) => {
        const failed = this.pending.get(id);
        if (failed === undefined) {
          return;
        }
        clearTimeout(failed.timer);
        this.pending.delete(id);
        failed.reject(new Error('无法调用本地运行时页面桥'));
      });
    });
  }

  callEvents(): Promise<Object> {
    const args: EventsArgs = { streamId: 'events' };
    return this.call('$events', args as Object, 15000);
  }

  callFollow(sessionId: string, streamId: string): Promise<Object> {
    const args: FollowArgs = { sessionId: sessionId, streamId: streamId };
    return this.call('$follow', args as Object, 15000);
  }

  callClose(streamId: string): Promise<Object> {
    const args: CloseStreamArgs = { streamId: streamId };
    return this.call('$close', args as Object, 5000);
  }

  callWriteFile(path: string, base64: string): Promise<Object> {
    const args: WriteFileArgs = { path: path, base64: base64 };
    return this.call('$writeFile', args as Object, 120000);
  }

  callReadFile(path: string): Promise<Object> {
    const args: ReadFileArgs = { path: path };
    return this.call('$readFile', args as Object, 30000);
  }

  callListFiles(path: string): Promise<Object> {
    const args: ListFilesArgs = { path: path };
    return this.call('$listFiles', args as Object, 30000);
  }

  async callOpenBinaryRead(path: string): Promise<BinaryOpenReply> {
    const args: OpenBinaryReadArgs = { path: path };
    const value: Object = await this.call('$openBinaryRead', args as Object, 30000);
    const row = asRecord(value);
    const reply: BinaryOpenReply = {
      id: jsonString(row, 'id'),
      size: jsonNumber(row, 'size', 0),
      mime: jsonString(row, 'mime', 'application/octet-stream')
    };
    return reply;
  }

  async callReadBinaryChunk(id: string, offset: number): Promise<BinaryChunkReply> {
    const args: ReadBinaryChunkArgs = { id: id, offset: offset };
    const value: Object = await this.call('$readBinaryChunk', args as Object, 30000);
    const row = asRecord(value);
    const reply: BinaryChunkReply = {
      id: jsonString(row, 'id'),
      size: jsonNumber(row, 'size', 0),
      offset: jsonNumber(row, 'offset', 0),
      base64: jsonString(row, 'base64')
    };
    return reply;
  }

  async callCloseBinaryRead(id: string): Promise<BinaryCloseReply> {
    const args: CloseBinaryReadArgs = { id: id };
    const value: Object = await this.call('$closeBinaryRead', args as Object, 5000);
    const row = asRecord(value);
    const reply: BinaryCloseReply = {
      closed: jsonBool(row, 'closed', false)
    };
    return reply;
  }

  callCommand(command: string, cwd: string): Promise<Object> {
    const args: CommandArgs = { command: command, cwd: cwd };
    return this.call('$command', args as Object, 120000);
  }

  postMessage(json: string): void {
    if (this.closed) {
      return;
    }
    const frame = parseJsonObject(json);
    if (frame === null) {
      return;
    }
    const type = jsonString(frame, 'type');
    if (type === 'ready') {
      this.listener.onBridgeReady();
      return;
    }
    if (type === 'error') {
      this.listener.onBridgeError(jsonString(frame, 'message', '本地运行时启动失败'));
      return;
    }
    if (type === 'reply') {
      this.completeReply(frame);
      return;
    }
    if (type === 'event') {
      const streamId = jsonString(frame, 'streamId');
      const value = asRecord(frame['value']);
      if (streamId.length > 0 && value !== null) {
        this.listener.onBridgeStreamEvent(streamId, value as Object);
      }
      return;
    }
    if (type === 'stream-end') {
      this.listener.onBridgeStreamEnd(jsonString(frame, 'streamId'), jsonString(frame, 'error'));
      return;
    }
  }

  dispose(): void {
    this.closed = true;
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(new Error('页面桥已关闭'));
    }
    this.pending.clear();
    this.controller = null;
  }

  private completeReply(frame: Record<string, Object>): void {
    const id = jsonNumber(frame, 'id', -1);
    const pending = this.pending.get(id);
    if (pending === undefined) {
      return;
    }
    clearTimeout(pending.timer);
    this.pending.delete(id);
    if (jsonBool(frame, 'ok', false)) {
      const value = frame['value'];
      pending.resolve(value === undefined || value === null ? new EmptyReplyValue() : value);
    } else {
      pending.reject(new Error(jsonString(frame, 'error', '运行时调用失败')));
    }
  }
}
