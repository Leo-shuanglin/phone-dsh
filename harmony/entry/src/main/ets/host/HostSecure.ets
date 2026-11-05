// SPDX-License-Identifier: MIT
// The only model-credential store in this host. A key is an AssetStore secret
// addressed by a stable alias; it never enters preferences, the page bridge,
// native WAL bytes, a notice, or a log line.
import { asset } from '@kit.AssetStoreKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { decodeUtf8, encodeUtf8 } from './HostUtil';

export class KeyReadResult {
  status: string = 'absent'; // ok | absent | unreadable
  value: string = '';
  reason: string = '';
}

export class KeyWriteResult {
  ok: boolean = false;
  reason: string = '';
}

function bytesFor(text: string): Uint8Array {
  return encodeUtf8(text);
}

function assetBytes(value: asset.Value | undefined): Uint8Array {
  if (value instanceof Uint8Array) {
    return value;
  }
  return new Uint8Array(0);
}

function errorCode(error: Error): number {
  const business = error as BusinessError;
  if (business !== null && business !== undefined && typeof business.code === 'number') {
    return business.code;
  }
  return 0;
}

export class HostSecure {
  private static readonly ACCESSIBILITY: asset.Accessibility = asset.Accessibility.DEVICE_UNLOCKED;

  private aliasBytes(alias: string): Uint8Array {
    return bytesFor(alias);
  }

  private queryMap(alias: string): asset.AssetMap {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.aliasBytes(alias));
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ATTRIBUTES);
    return query;
  }

  async has(alias: string): Promise<boolean> {
    const result = await this.read(alias);
    return result.status === 'ok';
  }

  async read(alias: string): Promise<KeyReadResult> {
    if (alias.length === 0) {
      return new KeyReadResult();
    }
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.aliasBytes(alias));
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const rows: Array<asset.AssetMap> = await asset.query(query);
      if (rows.length === 0) {
        return new KeyReadResult();
      }
      const secret = rows[0].get(asset.Tag.SECRET);
      const bytes = assetBytes(secret);
      if (bytes.byteLength === 0) {
        const invalid = new KeyReadResult();
        invalid.status = 'unreadable';
        invalid.reason = 'stored key is empty';
        return invalid;
      }
      const result = new KeyReadResult();
      result.status = 'ok';
      result.value = decodeUtf8(bytes);
      return result;
    } catch (error) {
      const code = errorCode(error as Error);
      if (code === asset.ErrorCode.NOT_FOUND) {
        return new KeyReadResult();
      }
      const result = new KeyReadResult();
      result.status = 'unreadable';
      result.reason = 'asset store unavailable';
      return result;
    }
  }

  async save(alias: string, key: string): Promise<KeyWriteResult> {
    const value = key.trim();
    if (alias.length === 0 || value.length === 0) {
      const refused = new KeyWriteResult();
      refused.reason = '密钥为空';
      return refused;
    }
    const attributes: asset.AssetMap = new Map();
    attributes.set(asset.Tag.SECRET, bytesFor(value));
    attributes.set(asset.Tag.ALIAS, this.aliasBytes(alias));
    attributes.set(asset.Tag.ACCESSIBILITY, HostSecure.ACCESSIBILITY);
    try {
      await asset.add(attributes);
      const written = new KeyWriteResult();
      written.ok = true;
      return written;
    } catch (error) {
      const code = errorCode(error as Error);
      if (code !== asset.ErrorCode.DUPLICATED) {
        const failed = new KeyWriteResult();
        failed.reason = '密钥存储写入失败';
        return failed;
      }
    }
    const update: asset.AssetMap = new Map();
    update.set(asset.Tag.SECRET, bytesFor(value));
    update.set(asset.Tag.ACCESSIBILITY, HostSecure.ACCESSIBILITY);
    try {
      await asset.update(this.queryMap(alias), update);
      const written = new KeyWriteResult();
      written.ok = true;
      return written;
    } catch (error) {
      const failed = new KeyWriteResult();
      failed.reason = '密钥存储更新失败';
      return failed;
    }
  }

  async remove(alias: string): Promise<KeyWriteResult> {
    if (alias.length === 0) {
      const absent = new KeyWriteResult();
      absent.ok = true;
      return absent;
    }
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.aliasBytes(alias));
    try {
      await asset.remove(query);
      const removed = new KeyWriteResult();
      removed.ok = true;
      return removed;
    } catch (error) {
      const code = errorCode(error as Error);
      const result = new KeyWriteResult();
      if (code === asset.ErrorCode.NOT_FOUND) {
        result.ok = true;
        return result;
      }
      result.reason = '密钥存储无法删除';
      return result;
    }
  }
}
