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
export interface SearchResult { evidence: Evidence[]; hasMore: boolean; mode: 'LITERAL_KEYWORD' | 'HYBRID_RRF_LIMITED' | 'SEMANTIC_NOT_INDEXED' | 'KEYWORD_FALLBACK' | 'SEMANTIC_DISABLED' | 'QDRANT_HYBRID_RRF_LIMITED' | 'QDRANT_FALLBACK' | 'QDRANT_WARMING' }
export interface BlockPage { blocks: Evidence[]; hasMore: boolean; page: number }
export interface AssistantCapabilities { enabled: boolean; retrievalMode: 'SELECTED_KEYWORD_EVIDENCE' }
export interface SemanticCapabilities { enabled: boolean; retrievalMode: string }
export interface VectorHealth {
  state: 'EMBEDDING_DISABLED' | 'MYSQL_ONLY' | 'NO_INDEX' | 'SYNCING' | 'READY' | 'DEGRADED';
  readyDocuments: number;
  mirroredDocuments: number;
  pendingDocuments: number;
  qdrantReachable: boolean;
}
export interface AiCallCounter {
  used: number; limit: number; remaining: number;
  reportedCalls: number; inputTokens: number; outputTokens: number;
  estimatedUsd: string | null;
}
export interface AiUsageSnapshot { dayUtc: string; chat: AiCallCounter; embedding: AiCallCounter }
export interface SemanticIndexStatus { status: 'NOT_REQUESTED' | 'PENDING' | 'RUNNING' | 'READY' | 'FAILED' | 'STALE'; completed: number; total: number }
export interface SourceRef { userFileId: string; generation: number; chunkIndex: number }
export interface RenameAgentCapabilities { enabled: boolean; operation: string }
export interface RenameProposal {
  id: string; userFileId: string; oldName: string; newName: string; expiresAt: string;
  status: 'PENDING' | 'APPLIED';
}
export interface RenameApplyResult { proposalId: string; userFileId: string; newName: string; status: string }
export interface MoveAgentCapabilities { enabled: boolean; operation: string }
export interface FolderOption { id: number; name: string; parentId: number }
export interface FolderChoices { folders: FolderOption[]; hasMore: boolean }
export interface MoveProposal {
  id: string; userFileId: string; fileName: string;
  sourceParentId: number; destinationId: number; destinationName: string;
  expiresAt: string; status: 'PENDING' | 'APPLIED' | 'REVERTED';
}
export interface MoveApplyResult { proposalId: string; userFileId: string; destinationId: number; status: string }
export interface MoveUndoResult { proposalId: string; userFileId: string; restoredParentId: number; status: 'REVERTED' }
export interface BatchPreviewItem {
  source: SourceRef; proposal: MoveProposal | null; status: 'READY' | 'SKIPPED';
}
export interface BatchMovePreview { items: BatchPreviewItem[]; proposed: number; skipped: number }
export interface AnswerResponse {
  answer: string;
  citations: { citationId: string; evidence: Evidence }[];
  mode: 'MODEL_WITH_CITATIONS' | 'INSUFFICIENT_EVIDENCE';
}
export type WorkflowStepState = 'PENDING' | 'APPROVED' | 'APPLIED' | 'SKIPPED' | 'REVERTED';
export type WorkflowState = 'ACTIVE' | 'CANCELLED' | 'COMPLETED';
export interface AgentWorkflowSummary {
  id: string;
  status: WorkflowState;
  total: number;
  applied: number;
  skipped: number;
  pending: number;
  createdAt: string;
}
export interface AgentWorkflowStep {
  stepNo: number;
  moveProposalId: string;
  userFileId: string;
  fileName: string;
  destinationName: string;
  expiresAt: string;
  state: WorkflowStepState;
}
export interface AgentWorkflowEvent { stepNo: number; action: string; recordedAt: string }
export interface AgentWorkflow {
  summary: AgentWorkflowSummary;
  steps: AgentWorkflowStep[];
  events: AgentWorkflowEvent[];
}
export type OrganizeMimeCategory = 'ANY' | 'TEXT' | 'PDF' | 'IMAGE' | 'VIDEO';
export type OrganizeStepState = 'READY' | 'APPROVED' | 'NOT_MATCHED' | 'ALREADY_THERE' | 'NAME_CONFLICT' | 'APPLIED' | 'SKIPPED';
export interface OrganizeTemplate {
  id: string;
  name: string;
  mimeCategory: OrganizeMimeCategory;
  destinationId: string;
  destinationName: string;
  createdAt: string;
}
export interface OrganizeStep { number: number; userFileId: string; fileName: string; state: OrganizeStepState; originalParentId: string }
export interface OrganizeAudit { number: number; action: string; at: string }
export interface OrganizeRun {
  id: string; templateId: string; name: string; mimeCategory: OrganizeMimeCategory;
  destinationId: string; destinationName: string; status: 'ACTIVE' | 'CANCELLED' | 'COMPLETED' | 'EXPIRED';
  expiresAt: string; matched: number; blocked: number; steps: OrganizeStep[]; events: OrganizeAudit[];
}

export interface AutoAskResponse {
  response: AnswerResponse;
  retrievalMode: SearchResult['mode'];
  selectedCount: number;
}

async function request<T>(path: string, signal: AbortSignal, body?: unknown, overrideMethod?: 'DELETE'): Promise<T> {
  const response = await fetch(`/api/v1/knowledge${path}`, {
    method: overrideMethod ?? (body === undefined ? 'GET' : 'POST'),
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
    const aiMessages: Record<number, string> = {
      700001: '模型问答尚未配置或未启用',
      700002: '引用来源已经变更或权限已失效，请重新检索',
      700003: '模型服务暂不可用，请稍后重试',
      700004: '模型没有返回有效的引用编号，已拒绝展示答案',
      700005: '今日模型调用额度已用完（按 UTC 日重置），请明天再试',
    };
    throw new ApiError(result?.code ?? response.status,
      aiMessages[result?.code ?? 0] || result?.message || '资料服务暂不可用，请确认后端已升级并稍后重试', result?.traceId);
  }
  return result.data;
}
export interface BatchIndexResult { accepted: number; maxBatchSize: number }
export interface ResearchBrief { evidence: Evidence[]; mode: 'EXACT_AUTHORIZED_EXCERPTS'; complete: boolean }

export const knowledgeApi = {
  organizationTemplates: (signal: AbortSignal) =>
    request<OrganizeTemplate[]>('/organize/templates',signal),
  createOrganizationTemplate: (name: string, mimeCategory: OrganizeMimeCategory,
                                destinationId: string, signal: AbortSignal) =>
    request<OrganizeTemplate>('/organize/templates',signal,{name,mimeCategory,destinationId}),
  archiveOrganizationTemplate: (id: string, signal: AbortSignal) =>
    request<void>('/organize/templates/'+encodeURIComponent(id)+'/archive',signal,{confirmed:true}),
  simulateOrganization: (templateId: string,userFileIds: string[],signal: AbortSignal) =>
    request<OrganizeRun>('/organize/simulate',signal,{templateId,userFileIds}),
  organizationRuns: (signal: AbortSignal) =>
    request<OrganizeRun[]>('/organize/runs',signal),
  organizationRun: (id: string,signal: AbortSignal) =>
    request<OrganizeRun>('/organize/runs/'+encodeURIComponent(id),signal),
  organizationStep: (id: string,stepNo: number,action: 'approve' | 'execute' | 'skip',
                     signal: AbortSignal) =>
    request<OrganizeRun>('/organize/runs/'+encodeURIComponent(id)+'/steps/'+stepNo+'/'+action,
      signal,{confirmed:true}),
  cancelOrganizationRun: (id: string,signal: AbortSignal) =>
    request<OrganizeRun>('/organize/runs/'+encodeURIComponent(id)+'/cancel',signal,{confirmed:true}),

  researchBrief: (sources: SourceRef[], signal: AbortSignal) =>
    request<ResearchBrief>('/research/brief', signal, { sources }),
  indexBatch: (userFileIds: string[], signal: AbortSignal) =>
    request<BatchIndexResult>('/documents/index/batch', signal, { userFileIds }),
  library: (page: number, signal: AbortSignal) => request<Library>(`/documents?page=${page}`, signal),
  search: (query: string, signal: AbortSignal) => request<SearchResult>('/search', signal, { query }),
  semanticCapabilities: (signal: AbortSignal) =>
    request<SemanticCapabilities>('/semantic/capabilities',signal),
  semanticHealth: (signal: AbortSignal) =>
    request<VectorHealth>('/semantic/health', signal),
  semanticSearch: (query: string, consent: boolean, signal: AbortSignal) =>
    request<SearchResult>('/semantic/search', signal, { query, consent }),
  semanticIndex: (id: string, consent: boolean, signal: AbortSignal) =>
    request<SemanticIndexStatus>(`/documents/${encodeURIComponent(id)}/semantic-index`,signal,{consent}),
  semanticStatus: (id: string, signal: AbortSignal) =>
    request<SemanticIndexStatus>(`/documents/${encodeURIComponent(id)}/semantic-index`,signal),
  semanticRevoke: (id: string, signal: AbortSignal) =>
    request<void>(`/documents/${encodeURIComponent(id)}/semantic-index`,signal,undefined,'DELETE'),
  index: (id: string, signal: AbortSignal) => request<void>(`/documents/${encodeURIComponent(id)}/index`, signal, {}),
  assistantCapabilities: (signal: AbortSignal) =>
    request<AssistantCapabilities>('/assistant/capabilities', signal),
  aiUsage: (signal: AbortSignal) =>
    request<AiUsageSnapshot>('/assistant/usage', signal),
  renameAgentCapabilities: (signal: AbortSignal) =>
    request<RenameAgentCapabilities>('/assistant/rename/capabilities',signal),
  suggestRename: (source: SourceRef, consent: boolean, signal: AbortSignal) =>
    request<RenameProposal>('/assistant/rename/suggest',signal,{source,consent}),
  applyRename: (id: string, confirmed: boolean, signal: AbortSignal) =>
    request<RenameApplyResult>('/assistant/rename/'+encodeURIComponent(id)+'/apply',signal,{confirmed}),
  recentRenameProposals: (signal: AbortSignal) =>
    request<RenameProposal[]>('/assistant/rename/recent',signal),
  moveAgentCapabilities: (signal: AbortSignal) =>
    request<MoveAgentCapabilities>('/assistant/move/capabilities',signal),
  folderChoices: (userFileId: string, signal: AbortSignal) =>
    request<FolderChoices>('/assistant/move/folders?userFileId='+encodeURIComponent(userFileId),signal),
  suggestMove: (source: SourceRef, candidateFolderIds: number[], consent: boolean, signal: AbortSignal) =>
    request<MoveProposal>('/assistant/move/suggest',signal,{source,consent,candidateFolderIds}),
  applyMove: (id: string, confirmed: boolean, signal: AbortSignal) =>
    request<MoveApplyResult>('/assistant/move/'+encodeURIComponent(id)+'/apply',signal,{confirmed}),
  createWorkflow: (moveProposalIds: string[], signal: AbortSignal) =>
    request<AgentWorkflow>('/assistant/workflows',signal,{moveProposalIds,confirmed:true}),
  recentWorkflows: (signal: AbortSignal) =>
    request<AgentWorkflowSummary[]>('/assistant/workflows',signal),
  workflow: (id: string, signal: AbortSignal) =>
    request<AgentWorkflow>('/assistant/workflows/'+encodeURIComponent(id),signal),
  workflowAction: (id: string, stepNo: number, action: 'approve' | 'skip' | 'execute' | 'undo',
                   signal: AbortSignal) =>
    request<AgentWorkflow>('/assistant/workflows/'+encodeURIComponent(id)+'/steps/'+stepNo+'/'+action,
      signal,{confirmed:true}),
  cancelWorkflow: (id: string, signal: AbortSignal) =>
    request<AgentWorkflow>('/assistant/workflows/'+encodeURIComponent(id)+'/cancel',signal,{confirmed:true}),
  recentMoveProposals: (signal: AbortSignal) =>
    request<MoveProposal[]>('/assistant/move/recent',signal),
  previewBatchMove: (sources: SourceRef[],candidateFolderIds: number[],consent: boolean,signal: AbortSignal) =>
    request<BatchMovePreview>('/assistant/move/batch/suggest',signal,{sources,candidateFolderIds,consent}),
  undoMove: (proposalId: string,confirmed: boolean,signal: AbortSignal) =>
    request<MoveUndoResult>('/assistant/move/'+encodeURIComponent(proposalId)+'/undo',signal,{confirmed}),
  autoAsk: (question: string, consent: boolean, signal: AbortSignal) =>
    request<AutoAskResponse>('/assistant/ask', signal, { question, consent }),
  answer: (question: string, sources: SourceRef[], consent: boolean, signal: AbortSignal) =>
    request<AnswerResponse>('/assistant/answer', signal, { question, sources, consent }),
  blocks: (id: string, generation: number, page: number, signal: AbortSignal) =>
    request<BlockPage>(`/documents/${encodeURIComponent(id)}/blocks?generation=${generation}&page=${page}`, signal),
};
export const statusLabels: Record<KnowledgeStatus, string> = {
  NOT_INDEXED: '待建立索引', PENDING: '排队中', EXTRACTING: '正在解析', READY: '正文可检索',
  PARTIAL: '部分可检索', FAILED: '解析失败', EMPTY: '未提取到正文', UNSUPPORTED: '超出处理范围',
};
