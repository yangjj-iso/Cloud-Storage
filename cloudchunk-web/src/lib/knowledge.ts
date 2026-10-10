import { ApiError, clearStoredAuth, getAuthHeader } from './api';
import type { ApiResult } from '../types';

export type KnowledgeStatus = 'NOT_INDEXED' | 'PENDING' | 'EXTRACTING' | 'READY' | 'PARTIAL' | 'FAILED' | 'EMPTY' | 'UNSUPPORTED';
export interface KnowledgeDocument {
  userFileId: string;
  fileName: string;
  mimeType: string;
  status: KnowledgeStatus;
  generation: number;
  contentChars: number;
  chunkCount: number;
  reason: string | null;
}
export interface Evidence {
  userFileId: string;
  fileName: string;
  generation: number;
  chunkIndex: number;
  startOffset: number;
  endOffset: number;
  content: string;
  status: KnowledgeStatus;
}
export interface Library { documents: KnowledgeDocument[]; hasMore: boolean; page: number }
export interface SearchResult { evidence: Evidence[]; hasMore: boolean; mode: 'LITERAL_KEYWORD' }
export interface BlockPage { blocks: Evidence[]; hasMore: boolean; page: number }

async function request<T>(path: string, signal: AbortSignal, body?: unknown): Promise<T> {
  const response = await fetch(`/api/v1/knowledge${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { ...getAuthHeader(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    cache: 'no-store', signal,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let result: ApiResult<T> | null = null;
  try { result = await response.json() as ApiResult<T>; } catch { /* handled below */ }
  if (response.status === 401 || result?.code === 100002) {
    clearStoredAuth();
    window.dispatchEvent(new CustomEvent('cloudchunk:unauthorized'));
    throw new ApiError(100002, '登录状态已失效，请重新登录');
  }
  if (!response.ok || !result || result.code !== 0) {
    throw new ApiError(result?.code ?? response.status,
      result?.message || '资料服务暂不可用，请确认后端已升级并稍后重试', result?.traceId);
  }
  return result.data;
}
export const knowledgeApi = {
  library: (page: number, signal: AbortSignal) => request<Library>(`/documents?page=${page}`, signal),
  search: (query: string, signal: AbortSignal) => request<SearchResult>('/search', signal, { query }),
  index: (id: string, signal: AbortSignal) => request<void>(`/documents/${encodeURIComponent(id)}/index`, signal, {}),
  blocks: (id: string, generation: number, page: number, signal: AbortSignal) =>
    request<BlockPage>(`/documents/${encodeURIComponent(id)}/blocks?generation=${generation}&page=${page}`, signal),
};
export const statusLabels: Record<KnowledgeStatus, string> = {
  NOT_INDEXED: '待建立索引', PENDING: '排队中', EXTRACTING: '正在解析', READY: '正文可检索',
  PARTIAL: '部分可检索', FAILED: '解析失败', EMPTY: '未提取到正文', UNSUPPORTED: '超出处理范围',
};
