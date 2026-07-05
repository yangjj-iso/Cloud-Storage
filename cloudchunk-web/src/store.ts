import { create } from 'zustand';
import { api, clearStoredAuth, getStoredAuth, saveStoredAuth } from './lib/api';
import { runUpload } from './lib/upload';
import { uniqId } from './lib/utils';
import type {
  AdminFileItem,
  AdminUserItem,
  AuthUser,
  FileMeta,
  LoginRequest,
  RegisterRequest,
  ShareItem,
  SystemSetting,
  UploadTask,
  UploadTaskStatus,
  UserFileItem,
  UserQuota,
} from './types';

export type ToastKind = 'success' | 'error' | 'info';
export interface Toast {
  id: string;
  kind: ToastKind;
  title: string;
  description?: string;
  ttl?: number;
}

export type View = 'upload' | 'files' | 'drive' | 'recycle' | 'shares' | 'admin';

interface State {
  authChecked: boolean;
  authLoading: boolean;
  authUser: AuthUser | null;
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

  // Drive
  driveFiles: UserFileItem[];
  driveParentId: number;
  driveLoading: boolean;

  // Recycle bin
  recycleFiles: UserFileItem[];
  recycleLoading: boolean;

  // Shares
  shares: ShareItem[];
  sharesTotal: number;
  sharesLoading: boolean;

  // Admin
  adminUsers: AdminUserItem[];
  adminUsersTotal: number;
  adminFiles: AdminFileItem[];
  adminFilesTotal: number;
  adminSettings: SystemSetting[];
  adminLoading: boolean;

  initAuth: () => Promise<void>;
  login: (req: LoginRequest) => Promise<void>;
  register: (req: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;

  setView: (v: View) => void;
  enqueueFiles: (files: FileList | File[]) => void;
  startUpload: (taskId: string) => void;
  pauseUpload: (taskId: string) => void;
  resumeUpload: (taskId: string) => void;
  cancelUpload: (taskId: string) => void;
  retryUpload: (taskId: string) => void;
  removeUpload: (taskId: string) => void;
  clearFinished: () => void;

  refreshFiles: () => Promise<void>;
  setFilesParams: (patch: Partial<{ page: number; size: number; keyword: string; mime: string }>) => void;
  refreshQuota: () => Promise<void>;
  deleteFile: (fileId: string) => Promise<void>;

  // Drive
  refreshDrive: (parentId?: number) => Promise<void>;
  driveMkdir: (parentId: number, name: string) => Promise<void>;
  driveRename: (id: number, newName: string) => Promise<void>;
  driveMove: (id: number, newParentId: number) => Promise<void>;
  driveDelete: (id: number) => Promise<void>;

  // Recycle
  refreshRecycle: () => Promise<void>;
  recycleRestore: (id: number) => Promise<void>;
  recycleHardDelete: (id: number) => Promise<void>;

  // Shares
  refreshShares: () => Promise<void>;
  createShare: (userFileId: number, expireDays?: number) => Promise<{ shareId: string; shareCode: string } | null>;
  cancelShare: (shareId: string) => Promise<void>;

  // Admin
  refreshAdminUsers: () => Promise<void>;
  refreshAdminFiles: () => Promise<void>;
  refreshAdminSettings: () => Promise<void>;
  adminDisableUser: (userId: number) => Promise<void>;
  adminEnableUser: (userId: number) => Promise<void>;
  adminAllocateSpace: (userId: number, totalBytes: number) => Promise<void>;
  adminDeleteFile: (fileId: string) => Promise<void>;
  adminSetSetting: (key: string, value: string) => Promise<void>;

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
  authChecked: false,
  authLoading: false,
  authUser: null,
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

  // Drive
  driveFiles: [],
  driveParentId: 0,
  driveLoading: false,

  // Recycle
  recycleFiles: [],
  recycleLoading: false,

  // Shares
  shares: [],
  sharesTotal: 0,
  sharesLoading: false,

  // Admin
  adminUsers: [],
  adminUsersTotal: 0,
  adminFiles: [],
  adminFilesTotal: 0,
  adminSettings: [],
  adminLoading: false,

  initAuth: async () => {
    const stored = getStoredAuth();
    if (!stored?.token) {
      set({ authChecked: true, authUser: null });
      return;
    }
    set({ authUser: stored.user, authChecked: true });
    try {
      const user = await api.me();
      set({ authUser: user });
    } catch {
      clearStoredAuth();
      set({ authUser: null, quota: null, files: [], uploads: [] });
    }
  },

  login: async (req) => {
    set({ authLoading: true });
    try {
      const auth = await api.login(req);
      saveStoredAuth(auth);
      set({ authUser: auth.user, authChecked: true, authLoading: false });
      await Promise.all([get().refreshQuota(), get().refreshFiles()]);
    } catch (e) {
      set({ authLoading: false });
      throw e;
    }
  },

  register: async (req) => {
    set({ authLoading: true });
    try {
      const auth = await api.register(req);
      saveStoredAuth(auth);
      set({ authUser: auth.user, authChecked: true, authLoading: false });
      await Promise.all([get().refreshQuota(), get().refreshFiles()]);
    } catch (e) {
      set({ authLoading: false });
      throw e;
    }
  },

  logout: async () => {
    try {
      await api.logout();
    } catch {
      /* local logout still wins */
    }
    clearStoredAuth();
    set({
      authUser: null,
      quota: null,
      files: [],
      uploads: [],
      activeFileId: null,
      view: 'upload',
    });
  },

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
    if (['HASHING', 'INITIATING', 'UPLOADING', 'MERGING'].includes(task.status)) return;
    const preserveProgress = task.status === 'PAUSED' || (task.status === 'FAILED' && Boolean(task.fileId));
    const ac = new AbortController();
    set((s) =>
      patchTask(s, taskId, {
        status: 'HASHING',
        abortController: ac,
        error: undefined,
        speed: 0,
        ...(preserveProgress
          ? {}
          : {
              bytesTransferred: 0,
              uploadedChunks: 0,
            }),
      })
    );
    const fresh = get().uploads.find((t) => t.id === taskId)!;
    runUpload(fresh, {
      onStatus: (status, patch) =>
        set((s) =>
          patchTask(s, taskId, {
            status,
            ...(patch || {}),
            ...(['PAUSED', 'DONE', 'FAILED', 'CANCELLED'].includes(status)
              ? { abortController: undefined }
              : {}),
          })
        ),
      onHashProgress: (processed, total) =>
        set((s) => {
          const current = s.uploads.find((t) => t.id === taskId);
          if (current?.fileId || (current?.uploadedChunks ?? 0) > 0) return s;
          return patchTask(s, taskId, {
            bytesTransferred: Math.floor((processed / total) * fresh.file.size * 0.05),
          });
        }),
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
        get().refreshDrive();
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

  pauseUpload: (taskId) => {
    const task = get().uploads.find((t) => t.id === taskId);
    if (!task || !['HASHING', 'INITIATING', 'UPLOADING'].includes(task.status)) return;
    task.abortController?.abort('pause');
    set((s) =>
      patchTask(s, taskId, {
        status: 'PAUSED',
        speed: 0,
        error: undefined,
        abortController: undefined,
      })
    );
  },

  resumeUpload: (taskId) => {
    const task = get().uploads.find((t) => t.id === taskId);
    if (!task || task.status !== 'PAUSED') return;
    get().startUpload(taskId);
  },

  cancelUpload: (taskId) => {
    const task = get().uploads.find((t) => t.id === taskId);
    task?.abortController?.abort('cancel');
    if (task?.fileId) {
      api.cancelUpload(task.fileId).catch(() => void 0);
    }
    if (task) {
      set((s) =>
        patchTask(s, taskId, {
          status: 'CANCELLED',
          speed: 0,
          abortController: undefined,
          finishedAt: Date.now(),
        })
      );
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

  // ===== Drive =====
  refreshDrive: async (parentId) => {
    const pid = parentId ?? get().driveParentId;
    set({ driveLoading: true });
    try {
      const files = await api.driveList(pid);
      set({ driveFiles: files, driveParentId: pid, driveLoading: false });
    } catch (e) {
      set({ driveLoading: false });
      get().showToast({ kind: 'error', title: '加载失败', description: (e as Error).message });
    }
  },

  driveMkdir: async (parentId, name) => {
    try {
      await api.driveMkdir(parentId, name);
      get().showToast({ kind: 'success', title: '目录已创建' });
      get().refreshDrive();
    } catch (e) {
      get().showToast({ kind: 'error', title: '创建失败', description: (e as Error).message });
    }
  },

  driveRename: async (id, newName) => {
    try {
      await api.driveRename(id, newName);
      get().showToast({ kind: 'success', title: '已重命名' });
      get().refreshDrive();
    } catch (e) {
      get().showToast({ kind: 'error', title: '重命名失败', description: (e as Error).message });
    }
  },

  driveMove: async (id, newParentId) => {
    try {
      await api.driveMove(id, newParentId);
      get().showToast({ kind: 'success', title: '已移动' });
      get().refreshDrive();
    } catch (e) {
      get().showToast({ kind: 'error', title: '移动失败', description: (e as Error).message });
    }
  },

  driveDelete: async (id) => {
    try {
      await api.driveDelete(id);
      get().showToast({ kind: 'success', title: '已移到回收站' });
      get().refreshDrive();
      get().refreshQuota();
    } catch (e) {
      get().showToast({ kind: 'error', title: '删除失败', description: (e as Error).message });
    }
  },

  // ===== Recycle Bin =====
  refreshRecycle: async () => {
    set({ recycleLoading: true });
    try {
      const files = await api.recycleList();
      set({ recycleFiles: files, recycleLoading: false });
    } catch (e) {
      set({ recycleLoading: false });
    }
  },

  recycleRestore: async (id) => {
    try {
      await api.recycleRestore(id);
      get().showToast({ kind: 'success', title: '已还原' });
      get().refreshRecycle();
      get().refreshQuota();
    } catch (e) {
      get().showToast({ kind: 'error', title: '还原失败', description: (e as Error).message });
    }
  },

  recycleHardDelete: async (id) => {
    try {
      await api.recycleHardDelete(id);
      get().showToast({ kind: 'success', title: '已彻底删除' });
      get().refreshRecycle();
    } catch (e) {
      get().showToast({ kind: 'error', title: '删除失败', description: (e as Error).message });
    }
  },

  // ===== Shares =====
  refreshShares: async () => {
    set({ sharesLoading: true });
    try {
      const page = await api.listShares(1, 50);
      set({ shares: page.records, sharesTotal: page.total, sharesLoading: false });
    } catch {
      set({ sharesLoading: false });
    }
  },

  createShare: async (userFileId, expireDays) => {
    try {
      const result = await api.createShare(userFileId, expireDays);
      get().showToast({ kind: 'success', title: '分享链接已创建' });
      get().refreshShares();
      return result;
    } catch (e) {
      get().showToast({ kind: 'error', title: '分享失败', description: (e as Error).message });
      return null;
    }
  },

  cancelShare: async (shareId) => {
    try {
      await api.cancelShare(shareId);
      get().showToast({ kind: 'success', title: '已取消分享' });
      get().refreshShares();
    } catch (e) {
      get().showToast({ kind: 'error', title: '取消失败', description: (e as Error).message });
    }
  },

  // ===== Admin =====
  refreshAdminUsers: async () => {
    set({ adminLoading: true });
    try {
      const page = await api.adminListUsers(1, 50);
      set({ adminUsers: page.records, adminUsersTotal: page.total, adminLoading: false });
    } catch {
      set({ adminLoading: false });
    }
  },

  refreshAdminFiles: async () => {
    set({ adminLoading: true });
    try {
      const page = await api.adminListFiles(1, 50);
      set({ adminFiles: page.records, adminFilesTotal: page.total, adminLoading: false });
    } catch {
      set({ adminLoading: false });
    }
  },

  refreshAdminSettings: async () => {
    try {
      const settings = await api.adminGetSettings();
      set({ adminSettings: settings });
    } catch {
      /* ignore */
    }
  },

  adminDisableUser: async (userId) => {
    try {
      await api.adminDisableUser(userId);
      get().showToast({ kind: 'success', title: '已禁用用户' });
      get().refreshAdminUsers();
    } catch (e) {
      get().showToast({ kind: 'error', title: '操作失败', description: (e as Error).message });
    }
  },

  adminEnableUser: async (userId) => {
    try {
      await api.adminEnableUser(userId);
      get().showToast({ kind: 'success', title: '已启用用户' });
      get().refreshAdminUsers();
    } catch (e) {
      get().showToast({ kind: 'error', title: '操作失败', description: (e as Error).message });
    }
  },

  adminAllocateSpace: async (userId, totalBytes) => {
    try {
      await api.adminAllocateSpace(userId, totalBytes);
      get().showToast({ kind: 'success', title: '空间已分配' });
      get().refreshAdminUsers();
    } catch (e) {
      get().showToast({ kind: 'error', title: '操作失败', description: (e as Error).message });
    }
  },

  adminDeleteFile: async (fileId) => {
    try {
      await api.adminDeleteFile(fileId);
      get().showToast({ kind: 'success', title: '文件已删除' });
      get().refreshAdminFiles();
    } catch (e) {
      get().showToast({ kind: 'error', title: '删除失败', description: (e as Error).message });
    }
  },

  adminSetSetting: async (key, value) => {
    try {
      await api.adminSetSetting(key, value);
      get().showToast({ kind: 'success', title: '设置已保存' });
      get().refreshAdminSettings();
    } catch (e) {
      get().showToast({ kind: 'error', title: '保存失败', description: (e as Error).message });
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
