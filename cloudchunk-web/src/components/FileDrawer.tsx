import {
  Copy,
  Download,
  ExternalLink,
  Hash,
  HardDrive,
  Loader2,
  RotateCcw,
  Trash2,
  X,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { api, downloadUrl } from '../lib/api';
import type { FileMeta, TranscodeStatusResp } from '../types';
import { Button } from './ui/Button';
import { Badge } from './ui/Badge';
import { cn, formatBytes, formatDate } from '../lib/utils';

export function FileDrawer() {
  const fileId = useAppStore((s) => s.activeFileId);
  const close = () => useAppStore.getState().setActiveFile(null);
  const deleteFile = useAppStore((s) => s.deleteFile);
  const showToast = useAppStore((s) => s.showToast);

  const [meta, setMeta] = useState<FileMeta | null>(null);
  const [url, setUrl] = useState<string>('');
  const [transcode, setTranscode] = useState<TranscodeStatusResp | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!fileId) {
      setMeta(null);
      setUrl('');
      setTranscode(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    Promise.all([
      api.fileMeta(fileId),
      api.fileUrl(fileId, 900).catch(() => ({ url: '', expireInSeconds: 0 })),
      api.transcodeStatus(fileId).catch(() => null),
    ])
      .then(([m, u, t]) => {
        if (cancelled) return;
        setMeta(m);
        setUrl(u.url);
        setTranscode(t);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [fileId]);

  const onRetryTranscode = async () => {
    if (!fileId) return;
    try {
      await api.transcodeRetry(fileId);
      showToast({ kind: 'success', title: '转码任务已重投' });
      const t = await api.transcodeStatus(fileId);
      setTranscode(t);
    } catch (e) {
      showToast({ kind: 'error', title: '重试失败', description: (e as Error).message });
    }
  };

  const copyUrl = async () => {
    if (!url) return;
    await navigator.clipboard.writeText(url);
    showToast({ kind: 'success', title: '链接已复制' });
  };

  if (!fileId) return null;

  return (
    <div
      className="fixed inset-0 z-40 flex justify-end bg-slate-900/20 backdrop-blur-sm animate-fade-in"
      onClick={close}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="flex h-full w-full max-w-md flex-col overflow-hidden bg-white shadow-2xl shadow-slate-900/10 animate-slide-in"
      >
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <div className="text-base font-semibold text-slate-900">文件详情</div>
          <button
            onClick={close}
            className="rounded p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {loading || !meta ? (
            <div className="flex flex-1 items-center justify-center py-20 text-slate-400">
              <Loader2 className="mr-2 h-5 w-5 animate-spin" />
              加载中…
            </div>
          ) : (
            <div className="flex flex-col gap-5 px-5 py-5">
              {meta.mimeType?.startsWith('image/') && url && (
                <div className="overflow-hidden rounded-lg bg-slate-100">
                  <img src={url} alt={meta.fileName} className="mx-auto max-h-80 w-full object-contain" />
                </div>
              )}
              {meta.mimeType?.startsWith('video/') && url && (
                <video
                  src={url}
                  controls
                  className="w-full rounded-lg bg-black"
                  preload="metadata"
                />
              )}
              {meta.mimeType?.startsWith('audio/') && url && (
                <audio src={url} controls className="w-full" />
              )}

              <div>
                <div
                  className="break-all text-lg font-semibold text-slate-900"
                  title={meta.fileName}
                >
                  {meta.fileName}
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
                  <Badge tone="neutral">{meta.mimeType || 'unknown'}</Badge>
                  <Badge tone={meta.status === 'AVAILABLE' ? 'success' : meta.status === 'BROKEN' ? 'danger' : 'info'}>
                    {meta.status}
                  </Badge>
                  {meta.transcodeStatus !== 'NONE' && (
                    <Badge
                      tone={
                        meta.transcodeStatus === 'SUCCESS'
                          ? 'success'
                          : meta.transcodeStatus === 'FAILED'
                          ? 'danger'
                          : 'info'
                      }
                    >
                      转码 {meta.transcodeStatus}
                    </Badge>
                  )}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3 text-xs">
                <Field label="大小" value={formatBytes(meta.fileSize)} />
                <Field label="扩展名" value={meta.ext || '—'} />
                <Field label="存储" value={`${meta.storageType} · ${meta.bucket}`} />
                <Field label="引用计数" value={String(meta.refCount)} />
                <Field label="创建" value={formatDate(meta.createdAt)} />
                <Field label="更新" value={formatDate(meta.updatedAt)} />
              </div>

              <div>
                <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-medium uppercase tracking-wider text-slate-400">
                  <Hash className="h-3 w-3" /> MD5
                </div>
                <div className="select-all rounded-lg bg-slate-50 px-3 py-2 font-mono text-xs text-slate-700">
                  {meta.fileMd5}
                </div>
              </div>

              <div>
                <div className="mb-1.5 flex items-center gap-1.5 text-[11px] font-medium uppercase tracking-wider text-slate-400">
                  <HardDrive className="h-3 w-3" /> Object Key
                </div>
                <div className="select-all break-all rounded-lg bg-slate-50 px-3 py-2 font-mono text-xs text-slate-700">
                  {meta.objectKey}
                </div>
              </div>

              {url && (
                <div>
                  <div className="mb-1.5 flex items-center justify-between">
                    <div className="flex items-center gap-1.5 text-[11px] font-medium uppercase tracking-wider text-slate-400">
                      <ExternalLink className="h-3 w-3" /> 预签名 URL
                    </div>
                    <button
                      onClick={copyUrl}
                      className="inline-flex items-center gap-1 text-xs text-brand-600 hover:text-brand-700"
                    >
                      <Copy className="h-3 w-3" /> 复制
                    </button>
                  </div>
                  <div className="select-all break-all rounded-lg bg-slate-50 px-3 py-2 font-mono text-[10px] text-slate-600">
                    {url}
                  </div>
                </div>
              )}

              {transcode && transcode.records.length > 0 && (
                <div>
                  <div className="mb-1.5 text-[11px] font-medium uppercase tracking-wider text-slate-400">
                    转码记录
                  </div>
                  <div className="space-y-2">
                    {transcode.records.map((r) => (
                      <div
                        key={r.id}
                        className={cn(
                          'rounded-lg border px-3 py-2 text-xs',
                          r.status === 2
                            ? 'border-emerald-200 bg-emerald-50'
                            : r.status === 3
                            ? 'border-rose-200 bg-rose-50'
                            : 'border-slate-200 bg-slate-50'
                        )}
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-medium text-slate-700">{r.taskType}</span>
                          <span className="text-[10px] text-slate-500">
                            {formatDate(r.createdAt)}
                          </span>
                        </div>
                        {r.errorMsg && (
                          <div className="mt-1 text-[11px] text-rose-600">{r.errorMsg}</div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between gap-2 border-t border-slate-200 bg-slate-50 px-5 py-3">
          <div className="flex gap-1.5">
            {meta?.transcodeStatus === 'FAILED' && (
              <Button
                size="sm"
                variant="secondary"
                leftIcon={<RotateCcw className="h-3.5 w-3.5" />}
                onClick={onRetryTranscode}
              >
                重试转码
              </Button>
            )}
          </div>
          <div className="flex gap-1.5">
            <Button
              size="sm"
              variant="danger"
              leftIcon={<Trash2 className="h-3.5 w-3.5" />}
              onClick={async () => {
                if (!meta) return;
                if (!confirm(`确定删除 "${meta.fileName}" 吗？`)) return;
                await deleteFile(meta.fileId);
                close();
              }}
            >
              删除
            </Button>
            <Button
              size="sm"
              variant="primary"
              leftIcon={<Download className="h-3.5 w-3.5" />}
              onClick={() => window.open(url || downloadUrl(fileId), '_blank')}
            >
              下载
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[10px] font-medium uppercase tracking-wider text-slate-400">
        {label}
      </div>
      <div className="mt-0.5 break-all text-sm font-medium text-slate-700">{value}</div>
    </div>
  );
}
