import { api } from './api';
import { hashFileWorker } from './md5';
import { guessChunkSize } from './utils';
import {
  hashCacheKey,
  getHashCache,
  setHashCache,
  saveUploadState,
  delUploadState,
} from './idb';
import type { UploadTask, UploadTaskStatus } from '../types';

/* ---------- 动态并发参数 ---------- */
const MIN_CONCURRENCY = 2;
const MAX_CONCURRENCY = 10;
const INITIAL_CONCURRENCY = 3;
/** 连续 N 片成功且单片 < TARGET_MS 则加一路并发 */
const RAMP_UP_STREAK = 3;
const TARGET_CHUNK_MS = 3000;
/** 单片超过此时长则收缩并发 */
const SLOW_CHUNK_MS = 8000;

/* ---------- 重试参数 ---------- */
const MAX_RETRIES = 3;
const RETRY_BASE_MS = 500;   // 指数退避基数

export interface UploadCallbacks {
  onStatus: (status: UploadTaskStatus, patch?: Partial<UploadTask>) => void;
  onHashProgress: (processed: number, total: number) => void;
  onChunkDone: (chunkIndex: number, bytesDelta: number, speed: number) => void;
  onDone: (fileId: string) => void;
  onError: (err: Error) => void;
}

export async function runUpload(task: UploadTask, cb: UploadCallbacks): Promise<void> {
  // 上传主流程：
  // 1. 浏览器本地计算整文件 MD5 和每个分片 MD5；
  // 2. 调后端 /upload/init 创建或恢复上传会话；
  // 3. 优先拿 presigned PUT URL，把分片直接 PUT 到 MinIO；
  // 4. 直传成功后调 /upload/confirm，让后端记录分片元数据和进度；
  // 5. 如果直传失败，降级为 /upload/chunk，由后端代理写入 MinIO；
  // 6. 全部分片完成后调 /upload/merge，把临时分片合成最终对象。
  const { file } = task;
  const abortSignal = task.abortController?.signal;
  const throwIfAborted = () => {
    if (abortSignal?.aborted) throw new DOMException('aborted', 'AbortError');
  };
  const abortReason = () => (abortSignal as (AbortSignal & { reason?: unknown }) | undefined)?.reason;
  const waitWithAbort = (ms: number) =>
    new Promise<void>((resolve, reject) => {
      if (abortSignal?.aborted) {
        reject(new DOMException('aborted', 'AbortError'));
        return;
      }
      let timer = 0;
      let cleanup = () => {};
      const onAbort = () => {
        window.clearTimeout(timer);
        cleanup();
        reject(new DOMException('aborted', 'AbortError'));
      };
      cleanup = () => abortSignal?.removeEventListener('abort', onAbort);
      timer = window.setTimeout(() => {
        cleanup();
        resolve();
      }, ms);
      abortSignal?.addEventListener('abort', onAbort, { once: true });
    });

  let stage: 'HASH' | 'INIT' | 'UPLOAD' | 'MERGE' = 'HASH';
  try {
    // 1. single-pass hash（优先 IndexedDB 缓存命中，跳过整次文件读取）
    cb.onStatus('HASHING', { startedAt: Date.now() });
    // chunkSize 和 chunkTotal 是前后端共同认可的分片协议。
    // 后端 init 会再次校验 chunkTotal 是否和 fileSize/chunkSize 匹配。
    const chunkSize = guessChunkSize(file.size);
    const chunkTotal = Math.max(1, Math.ceil(file.size / chunkSize));
    const cacheKey = hashCacheKey(file, chunkSize);
    let md5: string;
    let chunkHashes: string[];
    const cached = await getHashCache(cacheKey);
    if (cached) {
      md5 = cached.fileMd5;
      chunkHashes = cached.chunkHashes;
      cb.onHashProgress(file.size, file.size);
    } else {
      const result = await hashFileWorker(
        file,
        chunkSize,
        (done: number, total: number) => cb.onHashProgress(done, total),
        abortSignal,
      );
      md5 = result.fileMd5;
      chunkHashes = result.chunkHashes;
      // 持久化 hash 结果到 IndexedDB，下次拖入同文件直接秒出
      setHashCache({ key: cacheKey, fileMd5: md5, chunkHashes, chunkSize, cachedAt: Date.now() }).catch(() => {});
    }
    throwIfAborted();

    // 2. init
    stage = 'INIT';
    cb.onStatus('INITIATING', { md5, chunkSize, chunkTotal });
    // init 不上传文件内容，只提交文件名、大小、整文件 MD5、分片大小和分片数量。
    // 后端会用这些信息判断秒传、续传或创建新 UploadSession。
    const init = await api.initUpload({
      fileName: file.name,
      fileSize: file.size,
      fileMd5: md5,
      chunkSize,
      chunkTotal,
      mimeType: file.type || 'application/octet-stream',
    }, abortSignal);
    throwIfAborted();

    if (init.mode === 'INSTANT') {
      // 秒传命中：后端已经有相同 MD5 的可用文件，前端不用再传任何分片。
      cb.onStatus('DONE', {
        fileId: init.fileId,
        mode: 'INSTANT',
        chunkSize,
        chunkTotal,
        uploadedChunks: chunkTotal,
        bytesTransferred: file.size,
        speed: 0,
        finishedAt: Date.now(),
      });
      cb.onDone(init.fileId);
      return;
    }

    // UPLOAD 表示全新上传，RESUME 表示复用未完成会话继续上传。
    // uploaded 是后端确认已经存在的分片集合，前端只需要上传缺失分片。
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

    // 2.5 chunk-level dedup：发送缺失分片 MD5 给后端，服务端拷贝命中的分片
    stage = 'UPLOAD';
    const missingBefore: number[] = [];
    for (let i = 0; i < actualChunkTotal; i++) if (!uploaded.has(i)) missingBefore.push(i);
    if (missingBefore.length > 0 && missingBefore.length < actualChunkTotal) {
      try {
        const md5Map: Record<number, string> = {};
        for (const idx of missingBefore) md5Map[idx] = chunkHashes[idx];
        const deduped = await api.dedupChunks(init.fileId, md5Map, abortSignal);
        for (const idx of deduped) uploaded.add(idx);
        if (deduped.length > 0) {
          cb.onStatus('UPLOADING', {
            uploadedChunks: uploaded.size,
            bytesTransferred: uploaded.size * actualChunkSize,
          });
        }
      } catch {
        // dedup 失败不影响正常上传
      }
      throwIfAborted();
    }

    // 3. upload remaining chunks — presigned PUT 直传 + 动态并发 + 指数退避重试
    const indices: number[] = [];
    for (let i = 0; i < actualChunkTotal; i++) if (!uploaded.has(i)) indices.push(i);
    let bytesSoFar = uploaded.size * actualChunkSize;
    const startTs = Date.now();

    // 预签名 URL 缓存 + 批量预取
    const PRESIGN_BATCH = 50;
    let presignedUrls: Record<string, string> = {};
    let presignFetched = 0;
    // usePresign=true 代表优先走浏览器 -> MinIO 的直传路径。
    // 一旦发现预签名上传不可用，后续分片统一降级到浏览器 -> 后端 -> MinIO。
    let usePresign = true; // 首次失败后降级为 backend proxy

    async function ensurePresigned(needed: number): Promise<void> {
      if (!usePresign || presignFetched >= indices.length) return;
      const from = presignFetched;
      const to = Math.min(presignFetched + Math.max(PRESIGN_BATCH, needed), indices.length);
      const batch = indices.slice(from, to);
      try {
        const urls = await api.presignChunks(init.fileId, batch, abortSignal);
        Object.assign(presignedUrls, urls);
        presignFetched = to;
      } catch {
        usePresign = false;
        console.warn('[upload] presign failed, falling back to backend proxy');
      }
    }

    // 共享游标 + 动态并发控制
    let cursor = 0;
    let concurrency = Math.min(INITIAL_CONCURRENCY, indices.length || 1);
    let fastStreak = 0;
    let activeWorkers = 0;
    let firstError: Error | null = null;
    const workerPromises: Promise<void>[] = [];

    function spawnWorker(): Promise<void> {
      const p = worker();
      workerPromises.push(p);
      return p;
    }

    /** 单片上传（带重试），优先 presigned PUT 直传 MinIO */
    async function uploadOneChunk(idx: number): Promise<number> {
      const start = idx * actualChunkSize;
      const end = Math.min(start + actualChunkSize, file.size);
      const blob = file.slice(start, end);
      const chunkMd5 = chunkHashes[idx];
      const presignUrl = presignedUrls[String(idx)];

      for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        throwIfAborted();
        try {
          const t0 = Date.now();
          if (presignUrl && usePresign) {
            const putRes = await fetch(presignUrl, {
              method: 'PUT',
              body: blob,
              signal: abortSignal,
            });
            if (!putRes.ok) throw new Error(`MinIO PUT ${putRes.status}`);
            await api.confirmChunk(init.fileId, idx, chunkMd5, abortSignal);
          } else {
            await api.uploadChunk(
              { fileId: init.fileId, chunkIndex: idx, chunkMd5, chunkSize: blob.size, blob },
              abortSignal,
            );
          }
          return Date.now() - t0;
        } catch (e) {
          if ((e as { name?: string }).name === 'AbortError') throw e;
          if (presignUrl && usePresign) {
            usePresign = false;
            console.warn('[upload] presigned PUT failed, degrading to backend proxy', e);
            attempt--; // CORS fallback 不消耗重试次数
            continue;
          }
          if (attempt >= MAX_RETRIES) throw e;
          const delay = RETRY_BASE_MS * Math.pow(2, attempt);
          console.warn(`[upload] chunk ${idx} attempt ${attempt + 1} failed, retry in ${delay}ms`, e);
          await waitWithAbort(delay);
        }
      }
      return 0; // unreachable
    }

    /** 工人：不断取下一个分片上传 */
    async function worker(): Promise<void> {
      activeWorkers++;
      try {
        while (cursor < indices.length && !firstError) {
          // 预签名 URL 预取：当游标接近已取范围时提前批量获取
          if (usePresign && cursor >= presignFetched - concurrency) {
            await ensurePresigned(concurrency);
          }
          const idx = indices[cursor++];
          const elapsed_ms = await uploadOneChunk(idx);

          uploaded.add(idx);
          const bytesDelta = Math.min(actualChunkSize, file.size - idx * actualChunkSize);
          bytesSoFar += bytesDelta;
          const elapsed = (Date.now() - startTs) / 1000;
          const speed = elapsed > 0 ? bytesSoFar / elapsed : 0;
          // UI 进度按“客户端已完成上传的分片”推进；
          // 后端最终可信进度仍以 confirmChunk/uploadChunk 写入的 Redis 进度为准。
          cb.onChunkDone(idx, bytesDelta, speed);

          // 每 10 片持久化一次上传进度到 IndexedDB（断电 / 关标签页后可恢复）
          if (idx % 10 === 0 || cursor >= indices.length) {
            const doneSet = [...uploaded];
            for (let j = 0; j < cursor; j++) doneSet.push(indices[j]);
            saveUploadState({
              fileId: init.fileId,
              fileMd5: md5,
              fileName: file.name,
              fileSize: file.size,
              chunkSize: actualChunkSize,
              chunkTotal: actualChunkTotal,
              uploadedChunks: doneSet,
              savedAt: Date.now(),
            }).catch(() => {});
          }

          // ---- 动态并发调节 ----
          if (elapsed_ms < TARGET_CHUNK_MS) {
            fastStreak++;
            if (fastStreak >= RAMP_UP_STREAK && concurrency < MAX_CONCURRENCY) {
              concurrency++;
              fastStreak = 0;
              // 启动新 worker
              if (activeWorkers < concurrency && cursor < indices.length) {
                spawnWorker();
              }
            }
          } else {
            fastStreak = 0;
            if (elapsed_ms > SLOW_CHUNK_MS && concurrency > MIN_CONCURRENCY) {
              concurrency--;
              // 当前 worker 自行退出以降低并发
              if (activeWorkers > concurrency) return;
            }
          }
        }
      } catch (e) {
        if (!firstError && (e as { name?: string }).name !== 'AbortError') {
          firstError = e instanceof Error ? e : new Error(String(e));
        }
        throw e;
      } finally {
        activeWorkers--;
      }
    }

    // 首批预签名 URL 预取
    await ensurePresigned(PRESIGN_BATCH);

    const initialWorkers = Math.min(concurrency, indices.length || 1);
    for (let i = 0; i < initialWorkers; i++) spawnWorker();
    for (let i = 0; i < workerPromises.length; i++) {
      await workerPromises[i].catch(() => void 0);
    }
    if (firstError) throw firstError;
    throwIfAborted();

    // 4. merge (server may have auto-merged already; call is idempotent)
    stage = 'MERGE';
    cb.onStatus('MERGING');
    // 所有 worker 完成后主动请求合并。
    // 如果后端 autoMerge 已经先合并成功，这里再次调用会走幂等返回。
    await api.mergeUpload(init.fileId);
    // 上传成功，清除 IndexedDB 持久化状态
    delUploadState(init.fileId).catch(() => {});
    cb.onStatus('DONE', {
      uploadedChunks: actualChunkTotal,
      bytesTransferred: file.size,
      speed: 0,
      finishedAt: Date.now(),
    });
    cb.onDone(init.fileId);
  } catch (e) {
    if ((e as { name?: string }).name === 'AbortError') {
      const paused = abortReason() === 'pause';
      cb.onStatus(paused ? 'PAUSED' : 'CANCELLED', {
        speed: 0,
        error: undefined,
        abortController: undefined,
        ...(paused ? {} : { finishedAt: Date.now() }),
      });
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
