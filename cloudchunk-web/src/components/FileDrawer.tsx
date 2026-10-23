import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { api, downloadUrl as fallbackDownloadUrl } from '../lib/api';
import { downloadFile, type DownloadProgress } from '../lib/download';
import type { FileMeta, TranscodeStatusResp } from '../types';
import { cn, formatBytes, mimeCategory } from '../lib/utils';
import { MaterialIcon } from './ui/MaterialIcon';
import { fileStatusLabels, formatZhDate, transcodeStatusLabels } from '../lib/locale';

export function FileDrawer() {
  const fileId = useAppStore((s) => s.activeFileId);
  const setActiveFile = useAppStore((s) => s.setActiveFile);
  const deleteFile = useAppStore((s) => s.deleteFile);
  const showToast = useAppStore((s) => s.showToast);
  const [meta, setMeta] = useState<FileMeta | null>(null);
  const [url, setUrl] = useState('');
  const [transcode, setTranscode] = useState<TranscodeStatusResp | null>(null);
  const [loading, setLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [revision, setRevision] = useState(0);
  const [downloadProgress, setDownloadProgress] = useState<DownloadProgress | null>(null);

  useEffect(() => {
    if (!fileId) {
      setMeta(null);
      setUrl('');
      setTranscode(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setDetailError('');
    setMeta(null);
    setUrl('');
    setTranscode(null);
    Promise.all([
      api.fileMeta(fileId),
      api.fileUrl(fileId, 900).catch(() => ({ url: '', expireInSeconds: 0 })),
      api.transcodeStatus(fileId).catch(() => null),
    ])
      .then(([nextMeta, nextUrl, nextTranscode]) => {
        if (cancelled) return;
        setMeta(nextMeta);
        setUrl(nextUrl.url);
        setTranscode(nextTranscode);
      })
      .catch((reason) => {
        if (!cancelled) setDetailError(reason instanceof Error ? reason.message : '详情加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [fileId, showToast, revision]);

  if (!fileId) return null;
  const close = () => setActiveFile(null);
  const processing = meta && ['RUNNING', 'QUEUED'].includes(meta.transcodeStatus);

  const onDownload = async () => {
    if (!meta) return;
    let target = url;
    if (!target) {
      const result = await api.fileUrl(fileId, 900).catch(() => null);
      target = result?.url || fallbackDownloadUrl(fileId);
      if (result?.url) setUrl(result.url);
    }
    setDownloadProgress({ loaded: 0, total: meta.fileSize, percent: 0 });
    try {
      await downloadFile({
        url: target,
        fileName: meta.fileName,
        fileSize: meta.fileSize,
        onProgress: setDownloadProgress,
      });
    } catch (reason) {
      showToast({ kind: 'error', title: '下载失败', description: (reason as Error).message });
    } finally {
      setDownloadProgress(null);
    }
  };

  const copyUrl = async () => {
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      showToast({ kind: 'success', title: '下载链接已复制' });
    } catch {
      showToast({ kind: 'error', title: '无法复制，请允许剪贴板访问' });
    }
  };

  return (
    <>
      <button
        type="button"
        aria-label="关闭文件详情"
        onClick={close}
        className="fixed bottom-0 left-0 right-0 top-[76px] z-[45] bg-surface/55 backdrop-blur-[3px] md:left-[var(--sidebar)] md:right-[380px]"
      />
      <aside className="fixed bottom-0 right-0 top-0 z-50 flex w-full animate-slide-in flex-col border-l border-outline-variant bg-surface-container-lowest shadow-drawer md:top-[76px] md:w-[380px]">
        <div className="flex h-16 shrink-0 items-center justify-between border-b border-outline-variant px-md">
          <h2 className="text-headline-md text-on-surface">文件详情</h2>
          <button type="button" onClick={close} className="stitch-icon-button" aria-label="关闭">
            <MaterialIcon>close</MaterialIcon>
          </button>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-md">
          {detailError ? (
            <div className="page-fallback" role="alert">
              <p>{detailError}</p>
              <button className="forge-button" onClick={() => setRevision((value) => value + 1)}>
                重试
              </button>
            </div>
          ) : loading || !meta ? (
            <div className="flex h-56 items-center justify-center text-body-md text-outline">
              <MaterialIcon className="mr-sm animate-spin">progress_activity</MaterialIcon>正在加载详情…
            </div>
          ) : (
            <div>
              <Preview meta={meta} url={url} />
              <h3 className="mt-lg break-words text-[18px] font-medium leading-6 text-on-surface">
                {meta.fileName}
              </h3>
              <div className="mt-sm flex flex-wrap gap-sm">
                <span className="inline-flex items-center gap-xs rounded-lg bg-secondary-container px-sm py-xs text-body-md text-on-secondary-container">
                  <MaterialIcon className="text-[16px]">{mimeIcon(meta.mimeType)}</MaterialIcon>
                  {meta.mimeType || '未知类型'}
                </span>
                <span className="inline-flex items-center gap-xs rounded-lg border border-outline-variant bg-surface-container-low px-sm py-xs text-body-md text-on-surface-variant">
                  <span
                    className={cn(
                      'h-2 w-2 rounded-full',
                      meta.status === 'AVAILABLE' ? 'bg-emerald-500' : 'bg-amber-500',
                    )}
                  />
                  {fileStatusLabels[meta.status]}
                </span>
              </div>

              {processing && (
                <section className="mt-lg rounded-xl border border-outline-variant bg-surface-container-low p-md">
                  <div className="flex items-center justify-between text-body-md">
                    <span className="font-medium text-on-surface">转码状态</span>
                    <span className="max-w-[170px] truncate text-primary">
                      {transcodeStatusLabels[meta.transcodeStatus]}
                    </span>
                  </div>
                  <div className="mt-sm h-1.5 overflow-hidden rounded-full bg-surface-variant">
                    <div className="h-full w-1/2 animate-pulse rounded-full bg-primary" />
                  </div>
                  <div className="mt-sm text-body-md text-on-surface-variant">
                    服务器正在处理媒体文件，请稍候。
                  </div>
                </section>
              )}

              {meta.transcodeStatus === 'FAILED' && (
                <button
                  type="button"
                  onClick={async () => {
                    await api.transcodeRetry(fileId);
                    setTranscode(await api.transcodeStatus(fileId));
                    showToast({ kind: 'success', title: '转码任务已重新提交' });
                  }}
                  className="mt-lg flex h-10 w-full items-center justify-center gap-sm rounded border border-error/30 bg-error-container text-label-md text-on-error-container"
                >
                  <MaterialIcon className="text-[18px]">refresh</MaterialIcon>重试转码
                </button>
              )}

              <h4 className="mb-sm mt-lg text-body-lg tracking-wide text-on-surface-variant">文件信息</h4>
              <section className="overflow-hidden rounded-xl border border-outline-variant bg-surface-container-low">
                <MetadataRow
                  label="文件大小"
                  value={`${formatBytes(meta.fileSize)}（${meta.fileSize.toLocaleString()} 字节）`}
                />
                <MetadataRow label="扩展名" value={meta.ext ? `.${meta.ext.replace(/^\./, '')}` : '—'} />
                <MetadataRow label="创建时间" value={formatDrawerDate(meta.createdAt)} />
                <MetadataRow label="修改时间" value={formatDrawerDate(meta.updatedAt)} />
                <div className="p-sm">
                  <div className="mb-xs text-body-md text-on-surface-variant">MD5 摘要</div>
                  <button
                    type="button"
                    onClick={async () => {
                      await navigator.clipboard.writeText(meta.fileMd5);
                      showToast({ kind: 'success', title: 'MD5 已复制' });
                    }}
                    className="flex h-10 w-full items-center gap-xs rounded border border-outline-variant bg-surface-container-lowest px-sm text-left font-mono text-body-md text-on-surface"
                  >
                    <span className="min-w-0 flex-1 truncate">{meta.fileMd5}</span>
                    <MaterialIcon className="text-[18px] text-outline">content_copy</MaterialIcon>
                  </button>
                </div>
              </section>

              {transcode && transcode.records.length > 0 && (
                <p className="mt-sm text-label-sm text-outline">
                  已记录 {transcode.records.length} 条转码事件
                </p>
              )}
            </div>
          )}
        </div>

        <div className="shrink-0 border-t border-outline-variant bg-surface-container-lowest p-md">
          <button
            type="button"
            onClick={onDownload}
            disabled={!meta || !!downloadProgress}
            className="flex h-11 w-full items-center justify-center gap-sm rounded bg-primary text-body-lg font-medium text-white shadow-sm hover:bg-primary-container disabled:opacity-60"
          >
            <MaterialIcon className={cn('text-[19px]', downloadProgress && 'animate-pulse')}>
              download
            </MaterialIcon>
            {downloadProgress ? `正在下载 ${downloadProgress.percent}%` : '下载文件'}
          </button>
          <div className="mt-sm grid grid-cols-[1fr_42px] gap-sm">
            <button
              type="button"
              onClick={copyUrl}
              disabled={!url}
              className="flex h-10 items-center justify-center gap-sm rounded border border-outline-variant text-body-md font-medium text-on-surface hover:bg-surface-container disabled:opacity-50"
            >
              <MaterialIcon className="text-[18px]">link</MaterialIcon>复制临时链接
            </button>
            <button
              type="button"
              aria-label="删除文件"
              onClick={async () => {
                if (!meta || !confirm(`确定将“${meta.fileName}”移入回收站吗？`)) return;
                await deleteFile(meta.fileId);
                close();
              }}
              className="flex h-10 items-center justify-center rounded border border-outline-variant text-error hover:bg-error-container"
            >
              <MaterialIcon className="text-[18px]">delete</MaterialIcon>
            </button>
          </div>
        </div>
      </aside>
    </>
  );
}

function Preview({ meta, url }: { meta: FileMeta; url: string }) {
  const source = url || '';
  if (meta.mimeType?.startsWith('image/') && source)
    return (
      <div className="h-48 overflow-hidden rounded-xl bg-surface-container">
        <img src={source} alt={meta.fileName} className="h-full w-full object-cover" />
      </div>
    );
  if (meta.mimeType?.startsWith('video/') && source)
    return (
      <div className="h-48 overflow-hidden rounded-xl bg-inverse-surface">
        <video src={source} className="h-full w-full object-cover" preload="metadata" />
      </div>
    );
  return (
    <div className="flex h-48 items-center justify-center rounded-xl bg-surface-container">
      <MaterialIcon className="text-[72px] text-primary">draft</MaterialIcon>
    </div>
  );
}

function MetadataRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex min-h-10 items-center justify-between gap-md border-b border-outline-variant px-sm py-xs text-body-md">
      <span className="shrink-0 text-on-surface-variant">{label}</span>
      <span className="min-w-0 break-all text-right font-medium text-on-surface">{value}</span>
    </div>
  );
}

function formatDrawerDate(value: string) {
  return formatZhDate(value, true);
}

function mimeIcon(mime?: string) {
  const category = mimeCategory(mime);
  if (category === 'image') return 'image';
  if (category === 'video') return 'movie';
  if (category === 'audio') return 'audio_file';
  if (category === 'archive') return 'folder_zip';
  return 'description';
}
