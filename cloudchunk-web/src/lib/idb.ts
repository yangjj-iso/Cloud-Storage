/**
 * IndexedDB 持久化层 — 用于跨浏览器会话的断点续传。
 *
 * 两个 object store：
 *   hash_cache  — 缓存 (fileName+fileSize+lastModified) → { fileMd5, chunkHashes }
 *   upload_state — 缓存 fileId → { fileId, uploadedChunks[], chunkSize, chunkTotal, fileMd5 }
 */

const DB_NAME = 'cloudchunk';
const DB_VERSION = 1;
const STORE_HASH = 'hash_cache';
const STORE_UPLOAD = 'upload_state';

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_HASH)) {
        db.createObjectStore(STORE_HASH, { keyPath: 'key' });
      }
      if (!db.objectStoreNames.contains(STORE_UPLOAD)) {
        db.createObjectStore(STORE_UPLOAD, { keyPath: 'fileId' });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function tx<T>(
  storeName: string,
  mode: IDBTransactionMode,
  fn: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return open().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const txn = db.transaction(storeName, mode);
        const store = txn.objectStore(storeName);
        const req = fn(store);
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
        txn.oncomplete = () => db.close();
        txn.onerror = () => {
          db.close();
          reject(txn.error);
        };
      }),
  );
}

/* ============================ hash cache ============================ */

export interface HashCacheEntry {
  key: string; // fileName|fileSize|lastModified
  fileMd5: string;
  chunkHashes: string[];
  chunkSize: number;
  cachedAt: number;
}

/** 根据文件元信息生成稳定 key */
export function hashCacheKey(file: File, chunkSize: number): string {
  return `${file.name}|${file.size}|${file.lastModified}|${chunkSize}`;
}

export async function getHashCache(key: string): Promise<HashCacheEntry | undefined> {
  try {
    const val = await tx<HashCacheEntry | undefined>(STORE_HASH, 'readonly', (s) => s.get(key));
    if (!val) return undefined;
    // 7 天过期
    if (Date.now() - val.cachedAt > 7 * 86400_000) {
      delHashCache(key).catch(() => {});
      return undefined;
    }
    return val;
  } catch {
    return undefined;
  }
}

export async function setHashCache(entry: HashCacheEntry): Promise<void> {
  try {
    await tx(STORE_HASH, 'readwrite', (s) => s.put(entry));
  } catch {
    // IndexedDB 不可用时静默降级
  }
}

export async function delHashCache(key: string): Promise<void> {
  try {
    await tx(STORE_HASH, 'readwrite', (s) => s.delete(key));
  } catch {}
}

/* ============================ upload state ============================ */

export interface UploadStateEntry {
  fileId: string;
  fileMd5: string;
  fileName: string;
  fileSize: number;
  chunkSize: number;
  chunkTotal: number;
  uploadedChunks: number[];
  savedAt: number;
}

export async function getUploadState(fileId: string): Promise<UploadStateEntry | undefined> {
  try {
    const val = await tx<UploadStateEntry | undefined>(STORE_UPLOAD, 'readonly', (s) => s.get(fileId));
    if (!val) return undefined;
    // 24 小时过期
    if (Date.now() - val.savedAt > 24 * 3600_000) {
      delUploadState(fileId).catch(() => {});
      return undefined;
    }
    return val;
  } catch {
    return undefined;
  }
}

export async function saveUploadState(entry: UploadStateEntry): Promise<void> {
  try {
    await tx(STORE_UPLOAD, 'readwrite', (s) => s.put(entry));
  } catch {}
}

export async function delUploadState(fileId: string): Promise<void> {
  try {
    await tx(STORE_UPLOAD, 'readwrite', (s) => s.delete(fileId));
  } catch {}
}
