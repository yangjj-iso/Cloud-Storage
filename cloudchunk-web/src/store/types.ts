import type { StateCreator } from 'zustand';
import type {
  AdminFileItem, AdminUserItem, AuthUser, FileMeta, LoginRequest, RegisterRequest,
  ShareItem, SystemSetting, UploadTask, UserFileItem, UserQuota,
} from '../types';

export type ToastKind = 'success' | 'error' | 'info';
export interface Toast { id: string; kind: ToastKind; title: string; description?: string; ttl?: number }
export type View = 'upload' | 'files' | 'drive' | 'recycle' | 'shares' | 'admin';
export type AdminTab = 'users' | 'files' | 'settings' | 'health';

export interface AuthSlice {
  authChecked: boolean; authLoading: boolean; authUser: AuthUser | null;
  initAuth: () => Promise<void>; login: (req: LoginRequest) => Promise<void>;
  register: (req: RegisterRequest) => Promise<void>; logout: () => Promise<void>;
}

export interface UiSlice {
  view: View; adminTab: AdminTab; toasts: Toast[]; activeFileId: string | null;
  setView: (view: View) => void; showToast: (toast: Omit<Toast, 'id'>) => void;
  setAdminTab: (tab: AdminTab) => void;
  dismissToast: (id: string) => void; setActiveFile: (fileId: string | null) => void;
}

export interface UploadSlice {
  uploads: UploadTask[];
  enqueueFiles: (files: FileList | File[]) => void; startUpload: (taskId: string) => void;
  pauseUpload: (taskId: string) => void; resumeUpload: (taskId: string) => void;
  cancelUpload: (taskId: string) => void; retryUpload: (taskId: string) => void;
  removeUpload: (taskId: string) => void; clearFinished: () => void;
}

export interface FilesSlice {
  files: FileMeta[]; filesTotal: number; filesPage: number; filesSize: number;
  filesKeyword: string; filesMime: string; filesLoading: boolean; quota: UserQuota | null;
  refreshFiles: () => Promise<void>;
  setFilesParams: (patch: Partial<{ page: number; size: number; keyword: string; mime: string }>) => void;
  refreshQuota: () => Promise<void>; deleteFile: (fileId: string) => Promise<void>;
}

export interface DriveSlice {
  driveFiles: UserFileItem[]; driveParentId: number; driveLoading: boolean;
  recycleFiles: UserFileItem[]; recycleLoading: boolean;
  refreshDrive: (parentId?: number) => Promise<void>; driveMkdir: (parentId: number, name: string) => Promise<void>;
  driveRename: (id: number, newName: string) => Promise<void>; driveMove: (id: number, newParentId: number) => Promise<void>;
  driveDelete: (id: number) => Promise<void>; refreshRecycle: () => Promise<void>;
  recycleRestore: (id: number) => Promise<void>; recycleHardDelete: (id: number) => Promise<void>;
}

export interface ShareSlice {
  shares: ShareItem[]; sharesTotal: number; sharesLoading: boolean;
  refreshShares: () => Promise<void>;
  createShare: (userFileId: number, expireDays?: number) => Promise<{ shareId: string; shareCode: string } | null>;
  cancelShare: (shareId: string) => Promise<void>;
}

export interface AdminSlice {
  adminUsers: AdminUserItem[]; adminUsersTotal: number; adminFiles: AdminFileItem[];
  adminUsersPage: number; adminUsersSize: number; adminUsersKeyword: string;
  adminFilesTotal: number; adminSettings: SystemSetting[]; adminLoading: boolean;
  refreshAdminUsers: () => Promise<void>; refreshAdminFiles: () => Promise<void>;
  setAdminUsersParams: (patch: Partial<{ page: number; size: number; keyword: string }>) => void;
  refreshAdminSettings: () => Promise<void>; adminDisableUser: (userId: number) => Promise<void>;
  adminEnableUser: (userId: number) => Promise<void>; adminAllocateSpace: (userId: number, totalBytes: number) => Promise<void>;
  adminResetPassword: (userId: number, newPassword: string) => Promise<void>;
  adminSetRole: (userId: number, role: 'user' | 'admin') => Promise<void>;
  adminDeleteFile: (fileId: string) => Promise<void>; adminSetSetting: (key: string, value: string) => Promise<void>;
}

export type AppState = AuthSlice & UiSlice & UploadSlice & FilesSlice & DriveSlice & ShareSlice & AdminSlice;
export type AppSlice<T> = StateCreator<AppState, [], [], T>;
