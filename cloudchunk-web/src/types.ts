export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
  traceId?: string;
}

export interface PageResult<T> {
  total: number;
  page: number;
  size: number;
  records: T[];
}

export type UploadMode = 'INSTANT' | 'UPLOAD' | 'RESUME';

export interface InitUploadRequest {
  fileName: string;
  fileSize: number;
  fileMd5: string;
  chunkSize: number;
  chunkTotal: number;
  mimeType?: string;
}

export interface InitUploadResponse {
  mode: UploadMode;
  fileId: string;
  chunkSize?: number;
  chunkTotal?: number;
  uploaded?: number[];
  missing?: number[] | null;
  url?: string;
  status?: number | null;
  expireAt?: string;
}

export interface ChunkUploadResponse {
  fileId: string;
  chunkIndex: number;
  etag: string;
  status: number;
  allReady: boolean;
}

export interface UploadProgress {
  fileId: string;
  chunkTotal: number;
  uploaded: number[];
  missing: number[];
  percent: number;
}

export interface MergeResult {
  fileId: string;
  status: string;
  objectKey: string;
  etag: string;
}

export type FileStatus = 'INIT' | 'MERGING' | 'MERGED' | 'AVAILABLE' | 'BROKEN' | 'DELETED';
export type TranscodeStatus = 'NONE' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

export interface FileMeta {
  id: number;
  fileId: string;
  fileMd5: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  ext?: string;
  storageType: string;
  bucket: string;
  objectKey: string;
  status: FileStatus;
  transcodeStatus: TranscodeStatus;
  thumbnailUrl?: string | null;
  extra?: string | null;
  ownerId: number;
  refCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface UserQuota {
  userId: number;
  totalBytes: number;
  usedBytes: number;
  fileCount: number;
  updatedAt: string;
}

export interface AuthUser {
  id: number;
  username: string;
  email?: string | null;
  nickname?: string | null;
  role?: string;
  lastLoginAt?: string | null;
  createdAt?: string | null;
}

export interface AuthResponse {
  token: string;
  tokenType: string;
  expiresInSeconds: number;
  user: AuthUser;
}

export interface LoginRequest {
  account: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email?: string;
  password: string;
  nickname?: string;
}

export interface TranscodeRecord {
  id: number;
  fileId: string;
  taskType: string;
  status: number;
  retryCount: number;
  result?: string;
  errorMsg?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
}

export interface TranscodeStatusResp {
  fileId: string;
  transcodeStatus: TranscodeStatus;
  extra: string;
  records: TranscodeRecord[];
}

export type UploadTaskStatus =
  | 'PENDING'
  | 'HASHING'
  | 'INITIATING'
  | 'UPLOADING'
  | 'MERGING'
  | 'PAUSED'
  | 'DONE'
  | 'FAILED'
  | 'CANCELLED';

export interface UploadTask {
  id: string;
  file: File;
  fileId?: string;
  md5?: string;
  status: UploadTaskStatus;
  mode?: UploadMode;
  chunkSize: number;
  chunkTotal: number;
  uploadedChunks: number;
  bytesTransferred: number;
  speed: number; // bytes/sec
  error?: string;
  startedAt?: number;
  finishedAt?: number;
  abortController?: AbortController;
}

// ===================== Drive / UserFile =====================

export interface UserFileItem {
  id: number;
  fileId: string;
  parentId: number;
  fileName: string;
  isDir: boolean;
  fileSize: number;
  status: number;
  createdAt: string;
  updatedAt: string;
}

// ===================== Share =====================

export interface ShareItem {
  shareId: string;
  userFileId: number;
  fileId: string;
  fileName: string;
  fileSize: number;
  expireAt?: string | null;
  viewCount: number;
  saveCount: number;
  createdAt: string;
}

export interface ShareResult {
  shareId: string;
  shareCode: string;
  shareUrl: string;
}

export interface ShareDetail {
  shareId: string;
  fileId: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  isDir: boolean;
}

// ===================== Preview =====================

export interface PreviewResult {
  fileId: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
  status?: string | null;
  transcodeStatus?: string | null;
  previewType: 'image' | 'video' | 'audio' | 'text' | 'pdf' | 'other';
  thumbnailUrl?: string | null;
  downloadUrl: string;
  extra?: string | null;
}

// ===================== Admin =====================

export interface AdminUserItem {
  id: number;
  username: string;
  email: string;
  nickname: string;
  role: string;
  status: number;
  totalBytes: number;
  usedBytes: number;
  createdAt: string;
}

export interface AdminFileItem {
  id: number;
  fileId: string;
  fileName: string;
  fileSize: number;
  isDir: boolean;
  userId: number;
  username: string;
  status: number;
  createdAt: string;
}

export interface SystemSetting {
  id: number;
  key: string;
  value: string;
  updatedBy: number;
  updatedAt: string;
}
