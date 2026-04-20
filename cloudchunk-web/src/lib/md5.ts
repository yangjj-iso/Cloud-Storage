import { createMD5 } from 'hash-wasm';

const READ_BUF = 2 * 1024 * 1024; // 2 MB

let _workerCount = 0;

export interface HashResult {
  fileMd5: string;
  chunkHashes: string[];
}

/**
 * 单次遍历同时算出 fileMd5 + 所有 chunkMd5（Web Worker, WASM 加速）。
 * 文件只读一遍，消除传统"先算文件 hash → 再逐片算 chunk hash"的双重 I/O。
 * Worker 不可用时自动降级为主线程版本。
 */
export function hashFileWorker(
  blob: Blob,
  chunkSize: number,
  onProgress?: (processed: number, total: number) => void,
): Promise<HashResult> {
  return new Promise((resolve, reject) => {
    let worker: Worker;
    try {
      worker = new Worker(new URL('./md5.worker.ts', import.meta.url), { type: 'module' });
    } catch {
      resolve(hashFile(blob, chunkSize, onProgress));
      return;
    }
    const id = `md5-${++_workerCount}`;
    worker.onmessage = (e: MessageEvent) => {
      const msg = e.data;
      if (msg.id !== id) return;
      if (msg.type === 'progress') {
        onProgress?.(msg.processed as number, msg.total as number);
      } else if (msg.type === 'done') {
        worker.terminate();
        resolve({ fileMd5: msg.fileMd5 as string, chunkHashes: msg.chunkHashes as string[] });
      } else if (msg.type === 'error') {
        worker.terminate();
        reject(new Error(`MD5 Worker 错误: ${msg.message as string}`));
      }
    };
    worker.onerror = (err) => {
      worker.terminate();
      reject(new Error(`MD5 Worker 崩溃: ${err.message}`));
    };
    worker.postMessage({ id, blob, chunkSize });
  });
}

/**
 * 主线程降级版本：单次遍历同时算 fileMd5 + 所有 chunkMd5。
 */
export async function hashFile(
  blob: Blob,
  chunkSize: number,
  onProgress?: (processed: number, total: number) => void,
): Promise<HashResult> {
  const fileHasher = await createMD5();
  let chunkHasher = await createMD5();
  const total = blob.size;
  const chunkHashes: string[] = [];
  let offset = 0;
  let chunkBytes = 0;

  try {
    while (offset < total) {
      const end = Math.min(offset + READ_BUF, total);
      const buf = await blob.slice(offset, end).arrayBuffer();
      const view = new Uint8Array(buf);

      fileHasher.update(view);

      let pos = 0;
      while (pos < view.length) {
        const remain = chunkSize - chunkBytes;
        const feed = Math.min(remain, view.length - pos);
        chunkHasher.update(view.subarray(pos, pos + feed));
        chunkBytes += feed;
        pos += feed;

        if (chunkBytes >= chunkSize) {
          chunkHashes.push(chunkHasher.digest('hex'));
          chunkHasher = await createMD5();
          chunkBytes = 0;
        }
      }

      offset = end;
      onProgress?.(offset, total);
      if (offset < total) await new Promise((r) => setTimeout(r, 0));
    }

    if (chunkBytes > 0) {
      chunkHashes.push(chunkHasher.digest('hex'));
    }

    return { fileMd5: fileHasher.digest('hex'), chunkHashes };
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new Error(`MD5 计算失败（${offset}/${total} bytes）: ${msg}`);
  }
}
