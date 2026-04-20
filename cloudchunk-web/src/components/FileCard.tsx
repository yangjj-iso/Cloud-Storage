import {
  FileArchive,
  FileText,
  FileImage,
  FileVideo,
  Music,
  MoreHorizontal,
  Download,
  Trash2,
  Info,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import type { FileMeta } from '../types';
import { cn, formatBytes, formatDate, mimeCategory } from '../lib/utils';
import { api, downloadUrl } from '../lib/api';
import { Badge } from './ui/Badge';
import { useAppStore } from '../store';

function iconFor(mime: string) {
  const cat = mimeCategory(mime);
  if (cat === 'image') return { Icon: FileImage, color: 'from-fuchsia-500 to-pink-500' };
  if (cat === 'video') return { Icon: FileVideo, color: 'from-sky-500 to-indigo-500' };
  if (cat === 'audio') return { Icon: Music, color: 'from-amber-400 to-orange-500' };
  if (cat === 'archive') return { Icon: FileArchive, color: 'from-orange-400 to-red-500' };
  if (cat === 'doc') return { Icon: FileText, color: 'from-emerald-400 to-teal-500' };
  return { Icon: FileText, color: 'from-slate-400 to-slate-500' };
}

function transcodeBadge(status: string) {
  if (status === 'SUCCESS')
    return <Badge tone="success">转码完成</Badge>;
  if (status === 'RUNNING')
    return <Badge tone="info">转码中</Badge>;
  if (status === 'PENDING')
    return <Badge tone="neutral">排队</Badge>;
  if (status === 'FAILED')
    return <Badge tone="danger">转码失败</Badge>;
  return null;
}

interface Props {
  file: FileMeta;
}

export function FileCard({ file }: Props) {
  const setActive = useAppStore((s) => s.setActiveFile);
  const deleteFile = useAppStore((s) => s.deleteFile);
  const [thumbUrl, setThumbUrl] = useState<string | null>(null);
  const { Icon, color } = iconFor(file.mimeType);
  const isImage = mimeCategory(file.mimeType) === 'image';

  useEffect(() => {
    let cancelled = false;
    if (!isImage) return;
    api
      .fileUrl(file.fileId, 900)
      .then((r) => {
        if (!cancelled) setThumbUrl(r.url);
      })
      .catch(() => void 0);
    return () => {
      cancelled = true;
    };
  }, [file.fileId, isImage]);

  const onDownload = async () => {
    try {
      const r = await api.fileUrl(file.fileId, 600);
      window.open(r.url, '_blank');
    } catch {
      // Fallback to streaming through backend
      window.open(downloadUrl(file.fileId), '_blank');
    }
  };

  const onDelete = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!confirm(`确定删除 "${file.fileName}" 吗？`)) return;
    await deleteFile(file.fileId);
  };

  return (
    <div
      onClick={() => setActive(file.fileId)}
      className="group relative flex cursor-pointer flex-col overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm transition-all hover:-translate-y-0.5 hover:border-brand-300 hover:shadow-lg"
    >
      <div className="relative aspect-[4/3] overflow-hidden bg-slate-50">
        {thumbUrl ? (
          <img
            src={thumbUrl}
            alt={file.fileName}
            loading="lazy"
            className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
            onError={() => setThumbUrl(null)}
          />
        ) : (
          <div className={cn('flex h-full w-full items-center justify-center bg-gradient-to-br text-white', color)}>
            <Icon className="h-12 w-12 opacity-90" strokeWidth={1.4} />
          </div>
        )}

        <div className="pointer-events-none absolute inset-x-0 bottom-0 flex items-center justify-between gap-1 bg-gradient-to-t from-black/60 to-transparent px-2 py-1.5 text-[10px] text-white opacity-0 transition-opacity group-hover:opacity-100">
          <span className="truncate font-medium">{file.ext || '—'}</span>
          <span className="shrink-0">{formatBytes(file.fileSize)}</span>
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-2 p-3">
        <div
          className="truncate text-sm font-medium text-slate-900"
          title={file.fileName}
        >
          {file.fileName}
        </div>
        <div className="flex flex-wrap items-center gap-1 text-[11px] text-slate-500">
          <span>{formatDate(file.createdAt)}</span>
          {file.refCount > 1 && (
            <Badge tone="info" className="!text-[10px]">
              引用 {file.refCount}
            </Badge>
          )}
          {transcodeBadge(file.transcodeStatus)}
        </div>

        <div className="mt-auto flex items-center justify-between pt-1">
          <div className="flex items-center gap-1">
            <button
              onClick={(e) => {
                e.stopPropagation();
                onDownload();
              }}
              className="inline-flex h-7 w-7 items-center justify-center rounded-md text-slate-500 transition-colors hover:bg-slate-100 hover:text-brand-600"
              aria-label="下载"
            >
              <Download className="h-4 w-4" />
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation();
                setActive(file.fileId);
              }}
              className="inline-flex h-7 w-7 items-center justify-center rounded-md text-slate-500 transition-colors hover:bg-slate-100 hover:text-sky-600"
              aria-label="详情"
            >
              <Info className="h-4 w-4" />
            </button>
            <button
              onClick={onDelete}
              className="inline-flex h-7 w-7 items-center justify-center rounded-md text-slate-500 transition-colors hover:bg-slate-100 hover:text-rose-600"
              aria-label="删除"
            >
              <Trash2 className="h-4 w-4" />
            </button>
          </div>
          <MoreHorizontal className="h-4 w-4 text-slate-300" />
        </div>
      </div>
    </div>
  );
}
