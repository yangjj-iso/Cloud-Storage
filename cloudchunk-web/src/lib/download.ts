/**
 * 并发 Range 下载工具
 *
 * 策略：
 *  - 文件 < PARALLEL_THRESHOLD → 预签名链接 window.open；后端鉴权链接 fetch Blob
 *  - 支持 showSaveFilePicker (File System Access API) → 并发 Range 分块，流式写盘，不撑爆内存
 *  - 不支持 FSAPI 且文件 < BLOB_MAX → 并发 Range 拼 Blob，触发 <a> 下载
 *  - 其余兜底 → 预签名链接交给浏览器；后端鉴权链接提示用户换支持 FSAPI 的浏览器
 */

import { getAuthHeader } from './api';

const CHUNK_SIZE = 50 * 1024 * 1024;   // 每块 50 MB
const CONCURRENCY = 4;                   // 并发线程数
const PARALLEL_THRESHOLD = 10 * 1024 * 1024; // 10 MB 以上才并行
const BLOB_MAX = 500 * 1024 * 1024;     // Blob 模式上限 500 MB

export interface DownloadProgress {
  loaded: number;
  total: number;
  percent: number;
}

export async function downloadFile(opts: {
  url: string;
  fileName: string;
  fileSize: number;
  onProgress?: (p: DownloadProgress) => void;
}): Promise<void> {
  const { url, fileName, fileSize, onProgress } = opts;
  const backend = isBackendUrl(url);

  if (!url || fileSize <= PARALLEL_THRESHOLD) {
    if (backend) {
      await downloadSingleRequest(url, fileName, onProgress);
    } else {
      window.open(url, '_blank');
    }
    return;
  }

  const hasFSAPI = typeof (window as any).showSaveFilePicker === 'function';

  if (hasFSAPI) {
    await downloadWithFSAPI(url, fileName, fileSize, onProgress);
  } else if (fileSize <= BLOB_MAX) {
    await downloadAsBlob(url, fileName, fileSize, onProgress);
  } else if (backend) {
    throw new Error('当前浏览器不支持大文件流式保存，请使用支持 File System Access API 的浏览器下载。');
  } else {
    window.open(url, '_blank');
  }
}

/* ------------------------------------------------------------------ */
/*  File System Access API 路径（大文件首选，直接流式写盘）              */
/* ------------------------------------------------------------------ */
async function downloadWithFSAPI(
  url: string,
  fileName: string,
  fileSize: number,
  onProgress?: (p: DownloadProgress) => void,
): Promise<void> {
  const handle = await (window as any).showSaveFilePicker({ suggestedName: fileName });
  const writable = await handle.createWritable();

  const chunks = buildChunks(fileSize);
  let loaded = 0;

  try {
    for (let i = 0; i < chunks.length; i += CONCURRENCY) {
      const batch = chunks.slice(i, i + CONCURRENCY);
      const buffers = await Promise.all(
        batch.map(({ start, end }) =>
          fetchRange(url, start, end),
        ),
      );
      for (const buf of buffers) {
        await writable.write(buf);
        loaded += buf.byteLength;
        onProgress?.(makeProgress(loaded, fileSize));
      }
    }
    await writable.close();
  } catch (e) {
    await writable.abort();
    throw e;
  }
}

/* ------------------------------------------------------------------ */
/*  Blob 路径（中小文件兜底）                                           */
/* ------------------------------------------------------------------ */
async function downloadAsBlob(
  url: string,
  fileName: string,
  fileSize: number,
  onProgress?: (p: DownloadProgress) => void,
): Promise<void> {
  const chunks = buildChunks(fileSize);
  const buffers: ArrayBuffer[] = new Array(chunks.length);
  let loaded = 0;

  for (let i = 0; i < chunks.length; i += CONCURRENCY) {
    const batch = chunks.slice(i, i + CONCURRENCY);
    const results = await Promise.all(
      batch.map(async ({ start, end }, offset) => {
        const buf = await fetchRange(url, start, end);
        loaded += buf.byteLength;
        onProgress?.(makeProgress(loaded, fileSize));
        return { idx: i + offset, buf };
      }),
    );
    for (const { idx, buf } of results) {
      buffers[idx] = buf;
    }
  }

  const blob = new Blob(buffers);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(a.href);
}

async function downloadSingleRequest(
  url: string,
  fileName: string,
  onProgress?: (p: DownloadProgress) => void,
): Promise<void> {
  const headers: Record<string, string> = {};
  if (isBackendUrl(url)) {
    Object.assign(headers, getAuthHeader());
  }
  const res = await fetch(url, { headers });
  if (!res.ok) {
    throw new Error(`Download failed: ${res.status}`);
  }
  const blob = await res.blob();
  onProgress?.(makeProgress(blob.size, blob.size || 1));
  triggerBlobDownload(blob, fileName);
}

/* ------------------------------------------------------------------ */
/*  工具函数                                                            */
/* ------------------------------------------------------------------ */
async function fetchRange(url: string, start: number, end: number): Promise<ArrayBuffer> {
  // 后端接口需要 Authorization；预签名 MinIO URL 不带业务鉴权头。
  const headers: Record<string, string> = { Range: `bytes=${start}-${end}` };
  if (isBackendUrl(url)) {
    Object.assign(headers, getAuthHeader());
  }
  const res = await fetch(url, { headers });
  if (res.status !== 206) {
    throw new Error(`Range fetch failed: ${res.status} ${start}-${end}`);
  }
  return res.arrayBuffer();
}

function isBackendUrl(url: string): boolean {
  return url.startsWith('/api/') || url.includes('/api/v1/');
}

function triggerBlobDownload(blob: Blob, fileName: string) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(a.href);
}

function buildChunks(fileSize: number): { start: number; end: number }[] {
  const chunks: { start: number; end: number }[] = [];
  for (let start = 0; start < fileSize; start += CHUNK_SIZE) {
    chunks.push({ start, end: Math.min(start + CHUNK_SIZE - 1, fileSize - 1) });
  }
  return chunks;
}

function makeProgress(loaded: number, total: number): DownloadProgress {
  return { loaded, total, percent: Math.round((loaded / total) * 100) };
}
