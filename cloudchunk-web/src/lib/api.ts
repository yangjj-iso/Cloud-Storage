import type {
  ApiResult,
  ChunkUploadResponse,
  FileMeta,
  InitUploadRequest,
  InitUploadResponse,
  MergeResult,
  PageResult,
  TranscodeStatusResp,
  UploadProgress,
  UserQuota,
} from '../types';

const API_BASE = '/api/v1';
const USER_ID = '1'; // dev default; swap for real auth later.

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

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok && res.status !== 206) {
    let body: ApiResult<unknown> | null = null;
    try {
      body = await res.json();
    } catch {
      /* non-json */
    }
    throw new ApiError(
      body?.code ?? res.status,
      body?.message ?? res.statusText ?? `HTTP ${res.status}`,
      body?.traceId
    );
  }
  const body: ApiResult<T> = await res.json();
  if (body.code !== 0) {
    throw new ApiError(body.code, body.message, body.traceId);
  }
  return body.data;
}

function authHeaders(): HeadersInit {
  return {
    'X-User-Id': USER_ID,
  };
}

export const api = {
  ping: async () => {
    const res = await fetch(`${API_BASE}/ping`, { headers: authHeaders() });
    return parse<{ status: string; storage: string; bucket: string }>(res);
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

  initUpload: async (req: InitUploadRequest): Promise<InitUploadResponse> => {
    const res = await fetch(`${API_BASE}/upload/init`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
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
        'Content-Length': String(args.blob.size),
      },
      body: args.blob,
      signal,
    });
    return parse<ChunkUploadResponse>(res);
  },

  presignChunks: async (fileId: string, indices: number[]): Promise<Record<string, string>> => {
    const q = new URLSearchParams({ indices: indices.join(',') });
    const res = await fetch(`${API_BASE}/upload/presign/${fileId}?${q}`, { headers: authHeaders() });
    return parse<Record<string, string>>(res);
  },

  confirmChunk: async (
    fileId: string,
    chunkIndex: number,
    chunkMd5: string,
  ): Promise<ChunkUploadResponse> => {
    const q = new URLSearchParams({ fileId, chunkIndex: String(chunkIndex), chunkMd5 });
    const res = await fetch(`${API_BASE}/upload/confirm?${q}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    return parse<ChunkUploadResponse>(res);
  },

  dedupChunks: async (fileId: string, chunkMd5Map: Record<number, string>): Promise<number[]> => {
    const res = await fetch(`${API_BASE}/upload/dedup/${fileId}`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(chunkMd5Map),
    });
    return parse<number[]>(res);
  },

  mergeUpload: async (fileId: string): Promise<MergeResult> => {
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
};

export function downloadUrl(fileId: string): string {
  return `${API_BASE}/file/${fileId}/download`;
}
