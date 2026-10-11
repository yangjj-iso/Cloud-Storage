import type {
  ApiResult,
  AuthResponse,
  AuthUser,
  ChunkUploadResponse,
  FileMeta,
  InitUploadRequest,
  InitUploadResponse,
  LoginRequest,
  MergeResult,
  PageResult,
  PreviewResult,
  RegisterRequest,
  ShareDetail,
  ShareItem,
  ShareResult,
  SystemSetting,
  TranscodeStatusResp,
  UploadProgress,
  UserFileItem,
  UserQuota,
  AdminUserItem,
  AdminFileItem,
} from '../types';

const API_BASE = '/api/v1';
const AUTH_STORAGE_KEY = 'cloudchunk.auth';

class ApiError extends Error {
  code: number;
  traceId?: string;
  constructor(code: number, message: string, traceId?: string) {
    super(message);
    this.code = code;
    this.traceId = traceId;
  }
}

export { ApiError };

interface StoredAuth {
  token: string;
  user: AuthUser;
}

export function getStoredAuth(): StoredAuth | null {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    return raw ? (JSON.parse(raw) as StoredAuth) : null;
  } catch {
    return null;
  }
}

export function saveStoredAuth(auth: AuthResponse) {
  localStorage.setItem(
    AUTH_STORAGE_KEY,
    JSON.stringify({ token: auth.token, user: auth.user } satisfies StoredAuth)
  );
}

export function saveStoredUser(user: AuthUser) {
  const stored = getStoredAuth();
  if (!stored) return;
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ ...stored, user } satisfies StoredAuth));
}

export function clearStoredAuth() {
  localStorage.removeItem(AUTH_STORAGE_KEY);
}

function authToken(): string | null {
  return getStoredAuth()?.token ?? null;
}

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok && res.status !== 206) {
    let body: ApiResult<unknown> | null = null;
    try {
      body = await res.json();
    } catch {
      /* non-json */
    }
    const code = body?.code ?? res.status;
    if (res.status === 401 || code === 100002) {
      clearStoredAuth();
      window.dispatchEvent(new CustomEvent('cloudchunk:unauthorized'));
    }
    throw new ApiError(code, localizeApiMessage(code, body?.message ?? `请求失败（HTTP ${res.status}）`), body?.traceId);
  }
  const body: ApiResult<T> = await res.json();
  if (body.code !== 0) {
    throw new ApiError(body.code, localizeApiMessage(body.code, body.message), body.traceId);
  }
  return body.data;
}

const apiMessages: Record<number, string> = {
  100001: '请求参数不正确',
  100002: '登录状态已失效，请重新登录',
  100003: '没有权限执行此操作',
  100004: '请求的资源不存在',
  100005: '操作过于频繁，请稍后重试',
  100500: '服务器内部错误',
  200001: '上传会话已过期，请重新上传',
  200002: '文件分片序号不正确',
  200003: '文件分片校验失败',
  200004: '相同文件正在上传中',
  200005: '文件完整性校验失败',
  200006: '文件分片合并失败',
  200007: '文件分片尚未上传完成',
  300001: '文件不存在',
  300002: '文件已损坏',
  300003: '下载范围无效',
  300004: '此目录已有同名文件或文件夹，请改名或选择其他位置',
  400001: '转码记录不存在',
  400002: '文件正在转码中',
  500001: '存储服务暂不可用',
  500002: '存储空间不足',
  600001: '存储配额不足',
};

function localizeApiMessage(code: number, message: string): string {
  const base = apiMessages[code];
  if (!base) return message || '请求失败';
  return base;
}

function authHeaders(): HeadersInit {
  const token = authToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

// Export for use by download.ts
export function getAuthHeader(): Record<string, string> {
  const token = authToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export const api = {
  login: async (req: LoginRequest): Promise<AuthResponse> => {
    const res = await fetch(`${API_BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    return parse<AuthResponse>(res);
  },

  register: async (req: RegisterRequest): Promise<AuthResponse> => {
    const res = await fetch(`${API_BASE}/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
    });
    return parse<AuthResponse>(res);
  },

  me: async (): Promise<AuthUser> => {
    const res = await fetch(`${API_BASE}/auth/me`, { headers: authHeaders() });
    return parse<AuthUser>(res);
  },

  logout: async (): Promise<void> => {
    const res = await fetch(`${API_BASE}/auth/logout`, {
      method: 'POST',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  ping: async () => {
    const res = await fetch(`${API_BASE}/ping`, { headers: authHeaders() });
    return parse<{ status: string }>(res);
  },

  quota: async (): Promise<UserQuota> => {
    const res = await fetch(`${API_BASE}/quota/me`, { headers: authHeaders() });
    return parse<UserQuota>(res);
  },

  files: async (params: {
    page?: number;
    size?: number;
    mimePrefix?: string;
    keyword?: string;
  }): Promise<PageResult<FileMeta>> => {
    const q = new URLSearchParams();
    if (params.page) q.set('page', String(params.page));
    if (params.size) q.set('size', String(params.size));
    if (params.mimePrefix) q.set('mimePrefix', params.mimePrefix);
    if (params.keyword) q.set('keyword', params.keyword);
    const res = await fetch(`${API_BASE}/file?${q.toString()}`, { headers: authHeaders() });
    return parse<PageResult<FileMeta>>(res);
  },

  fileMeta: async (fileId: string): Promise<FileMeta> => {
    const res = await fetch(`${API_BASE}/file/${fileId}`, { headers: authHeaders() });
    return parse<FileMeta>(res);
  },

  fileUrl: async (fileId: string, ttlSeconds = 1800): Promise<{ url: string; expireInSeconds: number }> => {
    const res = await fetch(`${API_BASE}/file/${fileId}/url?ttlSeconds=${ttlSeconds}`, {
      headers: authHeaders(),
    });
    return parse(res);
  },

  deleteFile: async (fileId: string): Promise<{ fileId: string; deleted: boolean }> => {
    const res = await fetch(`${API_BASE}/file/${fileId}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    return parse(res);
  },

  initUpload: async (req: InitUploadRequest, signal?: AbortSignal): Promise<InitUploadResponse> => {
    // 初始化上传会话：只传文件元数据，不传文件内容。
    // 后端根据 fileMd5 返回秒传、续传或新上传模式。
    const res = await fetch(`${API_BASE}/upload/init`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      signal,
    });
    return parse<InitUploadResponse>(res);
  },

  progress: async (fileId: string): Promise<UploadProgress> => {
    const res = await fetch(`${API_BASE}/upload/progress/${fileId}`, { headers: authHeaders() });
    return parse<UploadProgress>(res);
  },

  uploadChunk: async (
    args: {
      fileId: string;
      chunkIndex: number;
      chunkMd5: string;
      chunkSize: number;
      blob: Blob;
    },
    signal?: AbortSignal
  ): Promise<ChunkUploadResponse> => {
    // 后端代理上传路径：请求体就是单个分片的二进制内容。
    // 该路径用于 presigned PUT 不可用时兜底，数据会经过 Java 服务再写入存储。
    const q = new URLSearchParams({
      fileId: args.fileId,
      chunkIndex: String(args.chunkIndex),
      chunkMd5: args.chunkMd5,
      chunkSize: String(args.chunkSize),
    });
    const res = await fetch(`${API_BASE}/upload/chunk?${q}`, {
      method: 'POST',
      headers: {
        ...authHeaders(),
        'Content-Type': 'application/octet-stream',
      },
      body: args.blob,
      signal,
    });
    return parse<ChunkUploadResponse>(res);
  },

  presignChunks: async (
    fileId: string,
    indices: number[],
    signal?: AbortSignal,
  ): Promise<Record<string, string>> => {
    // 直传准备阶段：向后端申请一批分片的 MinIO presigned PUT URL。
    // 返回值是 chunkIndex -> url 的映射。
    const q = new URLSearchParams({ indices: indices.join(',') });
    const res = await fetch(`${API_BASE}/upload/presign/${fileId}?${q}`, { headers: authHeaders(), signal });
    return parse<Record<string, string>>(res);
  },

  confirmChunk: async (
    fileId: string,
    chunkIndex: number,
    chunkMd5: string,
    signal?: AbortSignal,
  ): Promise<ChunkUploadResponse> => {
    // 直传确认阶段：分片已经由浏览器 PUT 到 MinIO。
    // 后端通过 statObject 确认对象存在，然后记录 ChunkRecord 和 Redis 进度。
    const q = new URLSearchParams({ fileId, chunkIndex: String(chunkIndex), chunkMd5 });
    const res = await fetch(`${API_BASE}/upload/confirm?${q}`, {
      method: 'POST',
      headers: authHeaders(),
      signal,
    });
    return parse<ChunkUploadResponse>(res);
  },

  dedupChunks: async (
    fileId: string,
    chunkMd5Map: Record<number, string>,
    signal?: AbortSignal,
  ): Promise<number[]> => {
    // 分片级去重：后端查找已有相同 chunkMd5 的分片，并在存储端 copy 到当前会话。
    // 命中的分片无需前端重新上传。
    const res = await fetch(`${API_BASE}/upload/dedup/${fileId}`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(chunkMd5Map),
      signal,
    });
    return parse<number[]>(res);
  },

  mergeUpload: async (fileId: string): Promise<MergeResult> => {
    // 合并阶段：后端把所有 upload/.../part.NNNNNN 临时对象 compose 为最终文件对象。
    const res = await fetch(`${API_BASE}/upload/merge/${fileId}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    return parse<MergeResult>(res);
  },

  cancelUpload: async (fileId: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/upload/${fileId}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  transcodeStatus: async (fileId: string): Promise<TranscodeStatusResp> => {
    const res = await fetch(`${API_BASE}/transcode/${fileId}`, { headers: authHeaders() });
    return parse<TranscodeStatusResp>(res);
  },

  transcodeRetry: async (fileId: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/transcode/${fileId}/retry`, {
      method: 'POST',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  // ===== Email / Auth =====
  sendCode: async (email: string, type: 'register' | 'reset' | 'bind'): Promise<void> => {
    const res = await fetch(`${API_BASE}/auth/send-code`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, type }),
    });
    await parse<void>(res);
  },

  resetPassword: async (email: string, code: string, newPassword: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/auth/reset-password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, code, newPassword }),
    });
    await parse<void>(res);
  },

  changeEmail: async (newEmail: string, code: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/auth/change-email`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ newEmail, code }),
    });
    await parse<void>(res);
  },

  // ===== Drive (directory tree) =====
  driveAudit: async (): Promise<Array<{operation:string;success:boolean;costMs:number;at:string}>> => {
    const res=await fetch(`${API_BASE}/drive/audit`,{headers:authHeaders(),cache:'no-store'});
    return parse(res);
  },
  driveList: async (parentId = 0): Promise<UserFileItem[]> => {
    const res = await fetch(`${API_BASE}/drive/list?parentId=${parentId}`, { headers: authHeaders() });
    return parse<UserFileItem[]>(res);
  },

  driveMkdir: async (parentId: number, name: string): Promise<UserFileItem> => {
    const res = await fetch(`${API_BASE}/drive/mkdir`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentId, name }),
    });
    return parse<UserFileItem>(res);
  },

  driveRename: async (id: number, newName: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/drive/rename`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, newName }),
    });
    await parse<void>(res);
  },

  driveMove: async (id: number, newParentId: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/drive/move`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, newParentId }),
    });
    await parse<void>(res);
  },

  driveDelete: async (id: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/drive/${id}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  driveDownloadZip: async (ids: number[]): Promise<Blob> => {
    const res = await fetch(`${API_BASE}/drive/download-zip`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ ids }),
    });
    if (!res.ok) {
      let body: ApiResult<unknown> | null = null;
      try { body = await res.json(); } catch { /* non-json */ }
      const code = body?.code ?? res.status;
      throw new ApiError(code, localizeApiMessage(code, body?.message ?? `请求失败（HTTP ${res.status}）`), body?.traceId);
    }
    return res.blob();
  },

  // ===== Recycle Bin =====
  recycleList: async (): Promise<UserFileItem[]> => {
    const res = await fetch(`${API_BASE}/recycle/list`, { headers: authHeaders() });
    return parse<UserFileItem[]>(res);
  },

  recycleRestore: async (id: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/recycle/${id}/restore`, {
      method: 'POST',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  recycleHardDelete: async (id: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/recycle/${id}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  // ===== Share =====
  createShare: async (userFileId: number, expireDays?: number): Promise<ShareResult> => {
    const res = await fetch(`${API_BASE}/share`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ userFileId, expireDays: expireDays || 0 }),
    });
    return parse<ShareResult>(res);
  },

  listShares: async (page = 1, size = 20): Promise<PageResult<ShareItem>> => {
    const res = await fetch(`${API_BASE}/share/list?page=${page}&size=${size}`, { headers: authHeaders() });
    return parse<PageResult<ShareItem>>(res);
  },

  cancelShare: async (shareId: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/share/${shareId}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  saveToMyDrive: async (shareId: string, shareCode: string, parentId = 0): Promise<void> => {
    const res = await fetch(`${API_BASE}/share/save`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ shareId, shareCode, parentId }),
    });
    await parse<void>(res);
  },

  // Public share (no auth)
  publicShare: async (shareId: string, shareCode: string): Promise<ShareDetail> => {
    const q = new URLSearchParams({ code: shareCode });
    const res = await fetch(`${API_BASE}/share/${shareId}?${q.toString()}`);
    return parse<ShareDetail>(res);
  },

  publicShareChildren: async (shareId: string, shareCode: string, parentId = 0): Promise<UserFileItem[]> => {
    const q = new URLSearchParams({ code: shareCode, parentId: String(parentId) });
    const res = await fetch(`${API_BASE}/share/${shareId}/children?${q.toString()}`);
    return parse<UserFileItem[]>(res);
  },

  publicShareDownloadUrl: (shareId: string, shareCode: string): string => {
    const q = new URLSearchParams({ code: shareCode });
    return `${API_BASE}/share/${shareId}/download?${q.toString()}`;
  },

  publicShareZipUrl: (shareId: string, shareCode: string): string => {
    const q = new URLSearchParams({ code: shareCode });
    return `${API_BASE}/share/${shareId}/download-zip?${q.toString()}`;
  },

  // ===== Preview =====
  preview: async (id: number): Promise<PreviewResult> => {
    const res = await fetch(`${API_BASE}/preview/${id}`, { headers: authHeaders() });
    return parse<PreviewResult>(res);
  },

  // ===== Admin =====
  adminOperations: async (): Promise<AdminOperationsOverview> => {
    const res = await fetch(`${API_BASE}/admin/operations`, {
      headers: authHeaders(), cache: 'no-store',
    });
    return parse<AdminOperationsOverview>(res);
  },
  adminDependencyHealth: async (): Promise<AdminHealthSnapshot> => {
    const res = await fetch(`${API_BASE}/admin/health-summary`, {
      headers: authHeaders(),
      cache: 'no-store',
    });
    return parse<AdminHealthSnapshot>(res);
  },

  adminListUsers: async (page = 1, size = 20, keyword = ''): Promise<PageResult<AdminUserItem>> => {
    const q = new URLSearchParams({ page: String(page), size: String(size) });
    if (keyword) q.set('keyword', keyword);
    const res = await fetch(`${API_BASE}/admin/users?${q.toString()}`, { headers: authHeaders() });
    return parse<PageResult<AdminUserItem>>(res);
  },

  adminDisableUser: async (userId: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/admin/users/${userId}/disable`, {
      method: 'POST',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  adminEnableUser: async (userId: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/admin/users/${userId}/enable`, {
      method: 'POST',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  adminResetPassword: async (userId: number, newPassword: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/admin/users/${userId}/reset-password`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ newPassword }),
    });
    await parse<void>(res);
  },

  adminSetRole: async (userId: number, role: 'user' | 'admin'): Promise<void> => {
    const res = await fetch(`${API_BASE}/admin/users/${userId}/role`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ role }),
    });
    await parse<void>(res);
  },

  adminAllocateSpace: async (userId: number, totalBytes: number): Promise<void> => {
    const res = await fetch(`${API_BASE}/admin/users/space`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, totalBytes }),
    });
    await parse<void>(res);
  },

  adminListFiles: async (page = 1, size = 20): Promise<PageResult<AdminFileItem>> => {
    const res = await fetch(`${API_BASE}/admin/files?page=${page}&size=${size}`, { headers: authHeaders() });
    return parse<PageResult<AdminFileItem>>(res);
  },

  adminDeleteFile: async (fileId: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/admin/files/${fileId}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    await parse<void>(res);
  },

  adminDownloadUrl: (fileId: string): string => {
    return `${API_BASE}/admin/files/${fileId}/download`;
  },

  adminGetSettings: async (): Promise<SystemSetting[]> => {
    const res = await fetch(`${API_BASE}/admin/settings`, { headers: authHeaders() });
    return parse<SystemSetting[]>(res);
  },

  adminSetSetting: async (key: string, value: string): Promise<void> => {
    const res = await fetch(`${API_BASE}/admin/settings`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ key, value }),
    });
    await parse<void>(res);
  },
};

export function downloadUrl(fileId: string): string {
  return `${API_BASE}/file/${fileId}/download`;
}

export interface AdminHealthComponent {
  id: 'mysql' | 'redis' | 'rocketmq' | 'minio' | 'ai-chat' | 'ai-embedding' | 'qdrant';
  status: 'UP' | 'DOWN' | 'REACHABLE_ONLY' | 'UNREACHABLE' | 'NOT_PROBED' | 'DISABLED';
  check: 'JDBC' | 'PING' | 'TCP' | 'CONFIGURATION';
  note: string;
}

export interface AdminHealthSnapshot {
  status: 'PARTIALLY_VERIFIED' | 'DEGRADED';
  sampledAt: string;
  components: AdminHealthComponent[];
  oldOutboxEvents: number | null;
}

export interface AdminOperationsSlo {
  state: 'UNCONFIGURED' | 'UNAVAILABLE' | 'WARMING' | 'LOW_TRAFFIC' | 'BREACH' | 'OBSERVED';
  objective: number;
  observedAvailability: number | null;
  burnRate1h: number | null;
  requests7d: number;
  scrapeCoverage: number;
  window: string;
  note: string;
}
export interface AdminOperationsAlert {
  fingerprint: string;
  name: string;
  severity: 'critical' | 'warning' | 'unknown';
  state: string;
  startedAt: string;
}
export interface AdminOperationsOverview {
  status: 'READ_ONLY' | 'PARTIAL';
  sampledAt: string;
  slo: AdminOperationsSlo;
  alerts: AdminOperationsAlert[];
  alertSource: 'VERIFIED' | 'UNAVAILABLE' | 'UNCONFIGURED';
}
