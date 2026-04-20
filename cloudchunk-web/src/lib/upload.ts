import { api } from './api';
import { md5OfBlob, md5OfChunk } from './md5';
import { guessChunkSize } from './utils';
import type { UploadTask, UploadTaskStatus } from '../types';

const CONCURRENT_CHUNKS = 3;

export interface UploadCallbacks {
  onStatus: (status: UploadTaskStatus, patch?: Partial<UploadTask>) => void;
  onHashProgress: (processed: number, total: number) => void;
  onChunkDone: (chunkIndex: number, bytesDelta: number, speed: number) => void;
  onDone: (fileId: string) => void;
  onError: (err: Error) => void;
}

export async function runUpload(task: UploadTask, cb: UploadCallbacks): Promise<void> {
  const { file } = task;
  const abortSignal = task.abortController?.signal;
  const throwIfAborted = () => {
    if (abortSignal?.aborted) throw new DOMException('aborted', 'AbortError');
  };

  let stage: 'HASH' | 'INIT' | 'UPLOAD' | 'MERGE' = 'HASH';
  try {
    // 1. hash
    cb.onStatus('HASHING', { startedAt: Date.now() });
    const chunkSize = guessChunkSize(file.size);
    const chunkTotal = Math.max(1, Math.ceil(file.size / chunkSize));
    const md5 = await md5OfBlob(file, (done, total) => {
      cb.onHashProgress(done, total);
    });
    throwIfAborted();

    // 2. init
    stage = 'INIT';
    cb.onStatus('INITIATING', { md5, chunkSize, chunkTotal });
    const init = await api.initUpload({
      fileName: file.name,
      fileSize: file.size,
      fileMd5: md5,
      chunkSize,
      chunkTotal,
      mimeType: file.type || 'application/octet-stream',
    });
    throwIfAborted();

    if (init.mode === 'INSTANT') {
      cb.onStatus('DONE', { fileId: init.fileId, mode: 'INSTANT', finishedAt: Date.now() });
      cb.onDone(init.fileId);
      return;
    }

    const actualChunkSize = init.chunkSize ?? chunkSize;
    const actualChunkTotal = init.chunkTotal ?? chunkTotal;
    const uploaded = new Set<number>(init.uploaded ?? []);
    cb.onStatus('UPLOADING', {
      fileId: init.fileId,
      mode: init.mode,
      chunkSize: actualChunkSize,
      chunkTotal: actualChunkTotal,
      uploadedChunks: uploaded.size,
      bytesTransferred: uploaded.size * actualChunkSize,
    });

    // 3. upload missing chunks with concurrency cap
    stage = 'UPLOAD';
    const indices: number[] = [];
    for (let i = 0; i < actualChunkTotal; i++) if (!uploaded.has(i)) indices.push(i);
    let bytesSoFar = uploaded.size * actualChunkSize;
    const startTs = Date.now();
    let cursor = 0;

    async function worker(): Promise<void> {
      while (cursor < indices.length) {
        const idx = indices[cursor++];
        throwIfAborted();
        const start = idx * actualChunkSize;
        const end = Math.min(start + actualChunkSize, file.size);
        const blob = file.slice(start, end);
        const chunkMd5 = await md5OfChunk(blob);
        throwIfAborted();
        await api.uploadChunk(
          {
            fileId: init.fileId,
            chunkIndex: idx,
            chunkMd5,
            chunkSize: blob.size,
            blob,
          },
          abortSignal
        );
        bytesSoFar += blob.size;
        const elapsed = (Date.now() - startTs) / 1000;
        const speed = elapsed > 0 ? bytesSoFar / elapsed : 0;
        cb.onChunkDone(idx, blob.size, speed);
      }
    }

    const workers = Array.from({ length: Math.min(CONCURRENT_CHUNKS, indices.length || 1) }, worker);
    await Promise.all(workers);
    throwIfAborted();

    // 4. merge (server may have auto-merged already; call is idempotent)
    stage = 'MERGE';
    cb.onStatus('MERGING');
    await api.mergeUpload(init.fileId);
    cb.onStatus('DONE', { finishedAt: Date.now() });
    cb.onDone(init.fileId);
  } catch (e) {
    if ((e as { name?: string }).name === 'AbortError') {
      cb.onStatus('CANCELLED', { finishedAt: Date.now() });
      return;
    }
    const raw = e instanceof Error ? e : new Error(String(e));
    const stageLabel =
      stage === 'HASH' ? '计算 MD5' : stage === 'INIT' ? '初始化会话' : stage === 'UPLOAD' ? '上传分片' : '合并';
    const err = new Error(`[${stageLabel}] ${raw.message}`);
    console.error('[upload]', stageLabel, raw);
    cb.onStatus('FAILED', { error: err.message, finishedAt: Date.now() });
    cb.onError(err);
  }
}
