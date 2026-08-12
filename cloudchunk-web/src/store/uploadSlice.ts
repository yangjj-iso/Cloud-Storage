import { api } from '../lib/api';
import { runUpload } from '../lib/upload';
import { uniqId } from '../lib/utils';
import type { UploadTask } from '../types';
import type { AppSlice, AppState, UploadSlice } from './types';

function patchTask(state: AppState, id: string, patch: Partial<UploadTask>): AppState {
  return { ...state, uploads: state.uploads.map((task) => task.id === id ? { ...task, ...patch } : task) };
}

export const createUploadSlice: AppSlice<UploadSlice> = (set, get) => ({
  uploads: [],
  enqueueFiles: (list) => {
    const tasks: UploadTask[] = Array.from(list).filter((file) => file.size > 0).map((file) => ({
      id: uniqId(), file, status: 'PENDING', chunkSize: 0, chunkTotal: 0,
      uploadedChunks: 0, bytesTransferred: 0, speed: 0,
    }));
    set((state) => ({ uploads: [...tasks, ...state.uploads] }));
    tasks.forEach((task) => get().startUpload(task.id));
  },
  startUpload: (taskId) => {
    const task = get().uploads.find((item) => item.id === taskId);
    if (!task || ['HASHING', 'INITIATING', 'UPLOADING', 'MERGING'].includes(task.status)) return;
    const preserve = task.status === 'PAUSED' || (task.status === 'FAILED' && Boolean(task.fileId));
    const abortController = new AbortController();
    set((state) => patchTask(state, taskId, {
      status: 'HASHING', abortController, error: undefined, speed: 0,
      ...(preserve ? {} : { bytesTransferred: 0, uploadedChunks: 0 }),
    }));
    const fresh = get().uploads.find((item) => item.id === taskId)!;
    runUpload(fresh, {
      onStatus: (status, patch) => set((state) => patchTask(state, taskId, {
        status, ...(patch || {}),
        ...(['PAUSED', 'DONE', 'FAILED', 'CANCELLED'].includes(status) ? { abortController: undefined } : {}),
      })),
      onHashProgress: (processed, total) => set((state) => {
        const current = state.uploads.find((item) => item.id === taskId);
        return current?.fileId || (current?.uploadedChunks ?? 0) > 0 ? state : patchTask(state, taskId, {
          bytesTransferred: Math.floor((processed / total) * fresh.file.size * 0.05),
        });
      }),
      onChunkDone: (_index, bytes, speed) => set((state) => patchTask(state, taskId, {
        uploadedChunks: (state.uploads.find((item) => item.id === taskId)?.uploadedChunks ?? 0) + 1,
        bytesTransferred: Math.min((state.uploads.find((item) => item.id === taskId)?.bytesTransferred ?? 0) + bytes, fresh.file.size),
        speed,
      })),
      onDone: () => {
        get().showToast({ kind: 'success', title: '上传完成', description: fresh.file.name });
        void get().refreshFiles(); void get().refreshQuota(); void get().refreshDrive();
      },
      onError: (error) => get().showToast({ kind: 'error', title: '上传失败', description: error.message }),
    });
  },
  pauseUpload: (taskId) => {
    const task = get().uploads.find((item) => item.id === taskId);
    if (!task || !['HASHING', 'INITIATING', 'UPLOADING'].includes(task.status)) return;
    task.abortController?.abort('pause');
    set((state) => patchTask(state, taskId, { status: 'PAUSED', speed: 0, error: undefined, abortController: undefined }));
  },
  resumeUpload: (taskId) => { if (get().uploads.find((item) => item.id === taskId)?.status === 'PAUSED') get().startUpload(taskId) },
  cancelUpload: (taskId) => {
    const task = get().uploads.find((item) => item.id === taskId); task?.abortController?.abort('cancel');
    if (task?.fileId) void api.cancelUpload(task.fileId).catch(() => undefined);
    if (task) set((state) => patchTask(state, taskId, {
      status: 'CANCELLED', speed: 0, abortController: undefined, finishedAt: Date.now(),
    }));
  },
  retryUpload: (taskId) => get().startUpload(taskId),
  removeUpload: (taskId) => set((state) => ({ uploads: state.uploads.filter((task) => task.id !== taskId) })),
  clearFinished: () => set((state) => ({ uploads: state.uploads.filter((task) => !['DONE', 'CANCELLED'].includes(task.status)) })),
});
