import { useEffect, useState, type MouseEvent } from 'react';
import type { FileMeta } from '../types';
import { cn, formatBytes, mimeCategory } from '../lib/utils';
import { api, downloadUrl, getAuthHeader } from '../lib/api';
import { formatZhDate } from '../lib/locale';
import { downloadFile } from '../lib/download';
import { useAppStore } from '../store';
import { MaterialIcon } from './ui/MaterialIcon';

export function FileCard({ file }: { file: FileMeta }) {
  const setActive = useAppStore((s) => s.setActiveFile);
  const deleteFile = useAppStore((s) => s.deleteFile);
  const showToast = useAppStore((s) => s.showToast);
  const [thumbnail, setThumbnail] = useState<string | null>(null);
  const category = mimeCategory(file.mimeType);
  const appearance = fileAppearance(file.fileName, category);
  const isPreviewable = category === 'image' || category === 'video';

  useEffect(() => {
    if (category !== 'image' && !file.thumbnailUrl) return;
    setThumbnail(null);
    let cancelled = false;
    let objectUrl = '';
    const load = file.thumbnailUrl
      ? fetch(file.thumbnailUrl, { headers: getAuthHeader() }).then(async (response) => {
          if (!response.ok) throw new Error('缩略图加载失败');
          objectUrl = URL.createObjectURL(await response.blob());
          return objectUrl;
        })
      : api.fileUrl(file.fileId, 900).then((result) => result.url);
    load.then((result) => { if (!cancelled) setThumbnail(result); }).catch(() => void 0);
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [category, file.fileId, file.thumbnailUrl]);

  const onDownload = async (event: MouseEvent) => {
    event.stopPropagation();
    try {
      const result = await api.fileUrl(file.fileId, 600).catch(() => null);
      if (result?.url) window.open(result.url, '_blank', 'noopener,noreferrer');
      else await downloadFile({ url: downloadUrl(file.fileId), fileName: file.fileName, fileSize: file.fileSize });
    } catch (reason) {
      showToast({ kind: 'error', title: '下载失败', description: (reason as Error).message });
    }
  };

  const onDelete = async (event: MouseEvent) => {
    event.stopPropagation();
    if (confirm(`确定将“${file.fileName}”移入回收站吗？`)) await deleteFile(file.fileId);
  };

  const processing = ['RUNNING', 'QUEUED'].includes(file.transcodeStatus);
  return (
    <article onClick={() => setActive(file.fileId)} className="group flex cursor-pointer flex-col overflow-hidden rounded-xl border border-outline-variant bg-surface-container-lowest transition-all duration-200 hover:-translate-y-0.5 hover:shadow-ambient">
      <div className="relative flex h-40 w-full items-center justify-center bg-surface-container-low">
        {thumbnail && isPreviewable ? <img src={thumbnail} alt={file.fileName} className="h-full w-full object-cover" onError={() => setThumbnail(null)} /> : <MaterialIcon className={cn('text-[64px]', appearance.previewColor)}>{appearance.icon}</MaterialIcon>}
        {category === 'video' && thumbnail && <span className="absolute inset-0 flex items-center justify-center"><span className="flex h-10 w-10 items-center justify-center rounded-full bg-surface/80 backdrop-blur-sm"><MaterialIcon>play_arrow</MaterialIcon></span></span>}
        {(category === 'image' || category === 'video') && (file.status === 'AVAILABLE' || processing) && (
          <span className="absolute right-sm top-sm flex items-center gap-xs rounded bg-surface/80 px-sm py-xs text-xs font-medium text-on-surface backdrop-blur-sm"><span className={cn('h-2 w-2 rounded-full', processing ? 'animate-pulse bg-amber-500' : 'bg-emerald-500')} />{processing ? '处理中' : '可用'}</span>
        )}
      </div>
      <div className="flex min-h-[78px] flex-1 flex-col p-md">
        <div className="mb-sm flex min-w-0 items-center gap-sm"><MaterialIcon className={cn('text-[22px]', appearance.iconColor)}>{appearance.icon}</MaterialIcon><span className="truncate text-label-md text-on-surface" title={file.fileName}>{file.fileName}</span></div>
        <div className="mt-auto flex items-center justify-between text-label-sm text-on-surface-variant"><span>{formatBytes(file.fileSize)}</span><span>{formatCardDate(file.createdAt)}</span></div>
      </div>
      <div className="flex h-0 justify-end gap-xs overflow-hidden border-outline-variant/50 bg-surface-bright px-sm opacity-0 transition-all group-hover:h-[41px] group-hover:border-t group-hover:py-sm group-hover:opacity-100">
        <CardAction title="下载" icon="download" onClick={onDownload} />
        <CardAction title="详情" icon="info" onClick={(event) => { event.stopPropagation(); setActive(file.fileId); }} />
        <CardAction danger title="删除" icon="delete" onClick={onDelete} />
      </div>
    </article>
  );
}

function CardAction({ title, icon, onClick, danger = false }: { title: string; icon: string; onClick: (event: MouseEvent<HTMLButtonElement>) => void; danger?: boolean }) {
  return <button type="button" title={title} aria-label={title} onClick={onClick} className={cn('rounded p-1.5 text-on-surface-variant hover:bg-surface-container-low hover:text-primary', danger && 'hover:bg-error-container hover:text-error')}><MaterialIcon className="text-[16px]">{icon}</MaterialIcon></button>;
}

function fileAppearance(name: string, category: ReturnType<typeof mimeCategory>) {
  const ext = name.split('.').pop()?.toLowerCase();
  if (category === 'image') return { icon: 'image', previewColor: 'text-primary', iconColor: 'text-primary' };
  if (category === 'video') return { icon: 'movie', previewColor: 'text-purple-500', iconColor: 'text-purple-500' };
  if (category === 'audio') return { icon: 'audio_file', previewColor: 'text-amber-500', iconColor: 'text-amber-600' };
  if (['csv', 'xls', 'xlsx'].includes(ext || '')) return { icon: 'table', previewColor: 'text-emerald-500', iconColor: 'text-emerald-600' };
  if (category === 'archive') return { icon: 'folder_zip', previewColor: 'text-orange-500', iconColor: 'text-orange-600' };
  return { icon: 'description', previewColor: 'text-blue-500', iconColor: 'text-blue-600' };
}

function formatCardDate(value: string) {
  return formatZhDate(value);
}
