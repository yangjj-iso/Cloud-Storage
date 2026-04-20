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
