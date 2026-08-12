import type { FileStatus, TranscodeStatus, UploadTaskStatus } from '../types';

export const uploadStatusLabels: Record<UploadTaskStatus, string> = {
  PENDING: '等待上传',
  HASHING: '正在计算 MD5',
  INITIATING: '正在准备',
  UPLOADING: '正在上传',
  MERGING: '正在合并分片',
  PAUSED: '已暂停',
  DONE: '已完成',
  FAILED: '上传失败',
  CANCELLED: '已取消',
};

export const fileStatusLabels: Record<FileStatus, string> = {
  UPLOADING: '上传中',
  MERGED: '待校验',
  AVAILABLE: '可用',
  BROKEN: '已损坏',
  DELETED: '已删除',
  DELETING: '删除中',
};

export const transcodeStatusLabels: Record<TranscodeStatus, string> = {
  NONE: '无需转码',
  RUNNING: '转码中',
  SUCCESS: '转码完成',
  FAILED: '转码失败',
  SKIP: '已跳过',
  QUEUED: '等待转码',
};

export function roleLabel(role?: string | null): string {
  return role === 'admin' ? '管理员' : '普通用户';
}

export function formatZhDate(value: string, withTime = false): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return withTime
    ? date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
    : date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' });
}

