import { create } from 'zustand';
import { api } from './lib/api';
import { runUpload } from './lib/upload';
import { uniqId } from './lib/utils';
import type { FileMeta, UploadTask, UploadTaskStatus, UserQuota } from './types';

export type ToastKind = 'success' | 'error' | 'info';
export interface Toast {
  id: string;
  kind: ToastKind;
  title: string;
  description?: string;
  ttl?: number;
}

export type View = 'upload' | 'files';

interface State {
  view: View;
  uploads: UploadTask[];
  files: FileMeta[];
  filesTotal: number;
  filesPage: number;
  filesSize: number;
  filesKeyword: string;
  filesMime: string;
  filesLoading: boolean;
  quota: UserQuota | null;
  toasts: Toast[];
  activeFileId: string | null;

  setView: (v: View) => void;
  enqueueFiles: (files: FileList | File[]) => void;
  startUpload: (taskId: string) => void;
  cancelUpload: (taskId: string) => void;
  retryUpload: (taskId: string) => void;
  removeUpload: (taskId: string) => void;
  clearFinished: () => void;

  refreshFiles: () => Promise<void>;
  setFilesParams: (patch: Partial<{ page: number; size: number; keyword: string; mime: string }>) => void;
  refreshQuota: () => Promise<void>;
  deleteFile: (fileId: string) => Promise<void>;

  showToast: (t: Omit<Toast, 'id'>) => void;
  dismissToast: (id: string) => void;

  setActiveFile: (fileId: string | null) => void;
}

function patchTask(state: State, id: string, patch: Partial<UploadTask>): State {
  return {
    ...state,
    uploads: state.uploads.map((t) => (t.id === id ? { ...t, ...patch } : t)),
  };
}

export const useAppStore = create<State>((set, get) => ({
  view: 'upload',
  uploads: [],
  files: [],
  filesTotal: 0,
  filesPage: 1,
  filesSize: 24,
  filesKeyword: '',
  filesMime: '',
  filesLoading: false,
  quota: null,
  toasts: [],
  activeFileId: null,

  setView: (v) => set({ view: v }),

  enqueueFiles: (list) => {
    const arr = Array.from(list).filter((f) => f.size > 0);
    const tasks: UploadTask[] = arr.map((file) => ({
      id: uniqId(),
      file,
      status: 'PENDING',
      chunkSize: 0,
      chunkTotal: 0,
      uploadedChunks: 0,
      bytesTransferred: 0,
      speed: 0,
    }));
    set((s) => ({ uploads: [...tasks, ...s.uploads] }));
    // kick off each
    tasks.forEach((t) => get().startUpload(t.id));
  },

  startUpload: (taskId) => {
    const task = get().uploads.find((t) => t.id === taskId);
    if (!task) return;
    const ac = new AbortController();
    set((s) =>
      patchTask(s, taskId, {
        status: 'HASHING',
        abortController: ac,
        error: undefined,
        bytesTransferred: 0,
        uploadedChunks: 0,
        speed: 0,
      })
    );
    const fresh = get().uploads.find((t) => t.id === taskId)!;
    runUpload(fresh, {
      onStatus: (status, patch) =>
        set((s) => patchTask(s, taskId, { status, ...(patch || {}) })),
      onHashProgress: (processed, total) =>
        set((s) =>
          patchTask(s, taskId, {
            bytesTransferred: Math.floor((processed / total) * fresh.file.size * 0.05),
          })
        ),
      onChunkDone: (_idx, _bytes, speed) =>
        set((s) =>
          patchTask(s, taskId, {
            uploadedChunks: (s.uploads.find((t) => t.id === taskId)?.uploadedChunks ?? 0) + 1,
            bytesTransferred: Math.min(
              (s.uploads.find((t) => t.id === taskId)?.bytesTransferred ?? 0) + _bytes,
              fresh.file.size
            ),
            speed,
          })
        ),
      onDone: () => {
        get().showToast({
          kind: 'success',
          title: '上传完成',
          description: fresh.file.name,
        });
        get().refreshFiles();
        get().refreshQuota();
      },
      onError: (err) => {
        get().showToast({
          kind: 'error',
          title: '上传失败',
          description: err.message,
        });
      },
    });
  },

  cancelUpload: (taskId) => {
    const task = get().uploads.find((t) => t.id === taskId);
    task?.abortController?.abort();
    if (task?.fileId) {
      api.cancelUpload(task.fileId).catch(() => void 0);
    }
  },

  retryUpload: (taskId) => get().startUpload(taskId),

  removeUpload: (taskId) =>
    set((s) => ({ uploads: s.uploads.filter((t) => t.id !== taskId) })),

  clearFinished: () =>
    set((s) => ({
      uploads: s.uploads.filter(
        (t) => !(t.status === 'DONE' || t.status === 'CANCELLED')
      ),
    })),

  refreshFiles: async () => {
    set({ filesLoading: true });
    try {
      const page = await api.files({
        page: get().filesPage,
        size: get().filesSize,
        keyword: get().filesKeyword || undefined,
        mimePrefix: get().filesMime || undefined,
      });
      set({
        files: page.records,
        filesTotal: page.total,
        filesLoading: false,
      });
    } catch (e) {
      set({ filesLoading: false });
      get().showToast({
        kind: 'error',
        title: '文件列表加载失败',
        description: (e as Error).message,
      });
    }
  },

  setFilesParams: (patch) => {
    set((s) => ({
      filesPage: patch.page ?? s.filesPage,
      filesSize: patch.size ?? s.filesSize,
      filesKeyword: patch.keyword ?? s.filesKeyword,
      filesMime: patch.mime ?? s.filesMime,
    }));
    get().refreshFiles();
  },

  refreshQuota: async () => {
    try {
      const q = await api.quota();
      set({ quota: q });
    } catch {
      /* ignore */
    }
  },

  deleteFile: async (fileId) => {
    try {
      await api.deleteFile(fileId);
      get().showToast({ kind: 'success', title: '已删除' });
      get().refreshFiles();
      get().refreshQuota();
    } catch (e) {
      get().showToast({
        kind: 'error',
        title: '删除失败',
        description: (e as Error).message,
      });
    }
  },

  showToast: ({ kind, title, description, ttl = 4000 }) => {
    const id = uniqId();
    set((s) => ({ toasts: [...s.toasts, { id, kind, title, description, ttl }] }));
    if (ttl > 0) {
      setTimeout(() => get().dismissToast(id), ttl);
    }
  },

  dismissToast: (id) =>
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),

  setActiveFile: (fileId) => set({ activeFileId: fileId }),
}));
