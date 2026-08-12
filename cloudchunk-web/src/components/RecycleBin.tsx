import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { MaterialIcon } from './ui/MaterialIcon';
import { formatZhDate } from '../lib/locale';

export function RecycleBin() {
  const { recycleFiles, recycleLoading, refreshRecycle, recycleRestore, recycleHardDelete } = useAppStore();
  const [selected, setSelected] = useState<Set<number>>(new Set());
  useEffect(() => { void refreshRecycle(); }, [refreshRecycle]);

  const emptyTrash = async () => {
    if (!recycleFiles.length || !confirm('确定永久删除回收站中的全部内容吗？此操作无法撤销。')) return;
    for (const file of recycleFiles) await recycleHardDelete(file.id);
    setSelected(new Set());
  };

  return (
    <div className="p-margin-mobile md:p-lg">
      <div className="mb-xl flex flex-col justify-between gap-md sm:flex-row sm:items-start">
        <div><h1 className="text-headline-lg text-on-background">回收站</h1><p className="mt-sm text-body-md text-on-surface-variant">在这里可以还原文件或将其永久删除。</p></div>
        <div className="flex gap-sm">
          <button type="button" onClick={() => refreshRecycle()} className="flex h-10 items-center gap-sm rounded-lg border border-outline-variant bg-surface-container-lowest px-md text-label-md text-on-surface shadow-sm hover:bg-surface-container"><MaterialIcon className={recycleLoading ? 'animate-spin text-[18px]' : 'text-[18px]'}>refresh</MaterialIcon>刷新</button>
          <button type="button" onClick={emptyTrash} disabled={!recycleFiles.length} className="flex h-10 items-center gap-sm rounded-lg bg-error-container px-md text-label-md text-on-error-container shadow-sm hover:bg-error-container/80 disabled:opacity-50"><MaterialIcon className="text-[18px]">delete_sweep</MaterialIcon>清空回收站</button>
        </div>
      </div>

      <section className="overflow-hidden rounded-xl border border-outline-variant bg-surface-container-lowest shadow-sm">
        <div className="grid grid-cols-12 gap-sm border-b border-surface-variant bg-surface-container-low px-md py-sm text-label-md uppercase tracking-wider text-on-surface-variant">
          <div className="col-span-5 flex items-center gap-sm"><input aria-label="全选" type="checkbox" checked={recycleFiles.length > 0 && selected.size === recycleFiles.length} onChange={() => setSelected(selected.size === recycleFiles.length ? new Set() : new Set(recycleFiles.map((file) => file.id)))} className="h-4 w-4 rounded accent-primary" /><span>名称</span></div>
          <div className="col-span-2">大小</div><div className="col-span-3">删除时间</div><div className="col-span-2 text-right">操作</div>
        </div>
        {recycleLoading ? (
          <div className="flex h-28 items-center justify-center text-body-md text-outline"><MaterialIcon className="mr-sm animate-spin">progress_activity</MaterialIcon>正在加载回收站…</div>
        ) : recycleFiles.length === 0 ? (
          <div className="flex h-36 flex-col items-center justify-center text-body-md text-outline"><MaterialIcon className="mb-sm text-[36px]">delete_outline</MaterialIcon>回收站为空</div>
        ) : recycleFiles.map((file, index) => {
          const appearance = recycleAppearance(file.fileName, file.isDir);
          return (
            <div key={file.id} className={`group grid min-h-[72px] grid-cols-12 items-center gap-sm px-md py-md transition-colors hover:bg-surface-container-lowest ${index < recycleFiles.length - 1 ? 'border-b border-surface-variant' : ''} ${selected.has(file.id) ? 'bg-secondary-container/20' : ''}`}>
              <div className="col-span-5 flex min-w-0 items-center gap-md">
                <input aria-label={`选择 ${file.fileName}`} type="checkbox" checked={selected.has(file.id)} onChange={() => setSelected((current) => { const next = new Set(current); if (next.has(file.id)) next.delete(file.id); else next.add(file.id); return next; })} className="h-4 w-4 shrink-0 rounded accent-primary" />
                <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded ${appearance.background}`}><MaterialIcon className={appearance.color}>{appearance.icon}</MaterialIcon></span>
                <span className="truncate text-body-md font-medium text-on-surface">{file.fileName}</span>
              </div>
              <div className="col-span-2 text-body-md text-on-surface-variant">{file.isDir ? '--' : formatBytes(file.fileSize)}</div>
              <div className="col-span-3 text-body-md text-on-surface-variant">{formatDeletedDate(file.updatedAt || file.createdAt)}</div>
              <div className="col-span-2 flex justify-end gap-xs opacity-0 transition-opacity group-hover:opacity-100">
                <button type="button" onClick={() => recycleRestore(file.id)} title="还原" className="stitch-icon-button"><MaterialIcon className="text-[19px]">restore</MaterialIcon></button>
                <button type="button" onClick={() => { if (confirm(`确定永久删除“${file.fileName}”吗？`)) recycleHardDelete(file.id); }} title="永久删除" className="stitch-icon-button text-error hover:bg-error-container"><MaterialIcon className="text-[19px]">delete_forever</MaterialIcon></button>
              </div>
            </div>
          );
        })}
      </section>
    </div>
  );
}

function recycleAppearance(name: string, isDir: boolean) {
  const ext = name.split('.').pop()?.toLowerCase();
  if (isDir) return { icon: 'folder', color: 'text-secondary', background: 'bg-secondary-container' };
  if (ext === 'pdf') return { icon: 'picture_as_pdf', color: 'text-primary', background: 'bg-primary-fixed' };
  if (['xls', 'xlsx', 'csv'].includes(ext || '')) return { icon: 'description', color: 'text-emerald-700', background: 'bg-emerald-50' };
  if (['zip', 'rar', '7z'].includes(ext || '')) return { icon: 'image', color: 'text-tertiary', background: 'bg-orange-100' };
  return { icon: 'draft', color: 'text-secondary', background: 'bg-surface-container' };
}

function formatDeletedDate(value: string) {
  return formatZhDate(value, true);
}
