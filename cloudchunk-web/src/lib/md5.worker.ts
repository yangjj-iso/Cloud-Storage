/**
 * Web Worker —— 单次遍历 (single-pass) 同时计算 fileMd5 + 所有 chunkMd5。
 *
 * 使用 hash-wasm (WASM) 替代 SparkMD5，吞吐提升 3-5 倍。
 * 文件只读一遍，避免"先算文件 hash → 再逐片算 chunk hash"的双重 I/O。
 *
 * 消息协议：
 *   主线程 → Worker: { id: string; blob: Blob; chunkSize: number }
 *   Worker → 主线程:
 *     | { id; type: 'progress'; processed; total }
 *     | { id; type: 'chunkHash'; chunkIndex; md5 }
 *     | { id; type: 'done'; fileMd5; chunkHashes: string[] }
 *     | { id; type: 'error'; message }
 */
import { createMD5 } from 'hash-wasm';

const READ_BUF = 2 * 1024 * 1024; // 2 MB 读缓冲

self.onmessage = async (
  e: MessageEvent<{ id: string; blob: Blob; chunkSize: number }>,
) => {
  const { id, blob, chunkSize } = e.data;
  const total = blob.size;

  try {
    const fileHasher = await createMD5();
    let chunkHasher = await createMD5();

    const chunkHashes: string[] = [];
    let offset = 0;
    let chunkBytes = 0; // 当前 chunk 已消费字节
    let chunkIndex = 0;

    while (offset < total) {
      const end = Math.min(offset + READ_BUF, total);
      const buf = await blob.slice(offset, end).arrayBuffer();
      const view = new Uint8Array(buf);

      // 喂给文件级 hasher
      fileHasher.update(view);

      // 喂给 chunk 级 hasher，遇到 chunk 边界时切换
      let pos = 0;
      while (pos < view.length) {
        const remain = chunkSize - chunkBytes;
        const feed = Math.min(remain, view.length - pos);
        chunkHasher.update(view.subarray(pos, pos + feed));
        chunkBytes += feed;
        pos += feed;

        if (chunkBytes >= chunkSize) {
          const md5 = chunkHasher.digest('hex');
          chunkHashes.push(md5);
          self.postMessage({ id, type: 'chunkHash', chunkIndex, md5 });
          chunkIndex++;
          chunkHasher = await createMD5();
          chunkBytes = 0;
        }
      }

      offset = end;
      self.postMessage({ id, type: 'progress', processed: offset, total });
    }

    // 最后一个不满 chunkSize 的尾 chunk
    if (chunkBytes > 0) {
      const md5 = chunkHasher.digest('hex');
      chunkHashes.push(md5);
      self.postMessage({ id, type: 'chunkHash', chunkIndex, md5 });
    }

    const fileMd5 = fileHasher.digest('hex');
    self.postMessage({ id, type: 'done', fileMd5, chunkHashes });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    self.postMessage({ id, type: 'error', message });
  }
};
