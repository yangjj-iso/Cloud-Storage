import { useEffect, useMemo } from 'react';
import { useAppStore } from '../store';
import { cn } from '../lib/utils';
import { FileCard } from './FileCard';
import { MaterialIcon } from './ui/MaterialIcon';

const filters = [
  { label: '全部', mime: '' },
  { label: '图片', mime: 'image/' },
  { label: '视频', mime: 'video/' },
  { label: '音频', mime: 'audio/' },
  { label: '文档', mime: 'application/' },
];

export function FilesView() {
  const files = useAppStore((s) => s.files);
  const total = useAppStore((s) => s.filesTotal);
  const page = useAppStore((s) => s.filesPage);
  const size = useAppStore((s) => s.filesSize);
  const mime = useAppStore((s) => s.filesMime);
  const loading = useAppStore((s) => s.filesLoading);
  const refresh = useAppStore((s) => s.refreshFiles);
  const setParams = useAppStore((s) => s.setFilesParams);
  const setView = useAppStore((s) => s.setView);
  const totalPages = useMemo(() => Math.max(1, Math.ceil(total / size)), [size, total]);
  const applyParams = (patch: Partial<{ page: number; mime: string }>) => {
    setParams(patch);
  };

  useEffect(() => { void refresh(); }, [refresh]);

  return (
    <div className="p-margin-mobile md:p-margin-desktop">
      <div className="mb-lg flex flex-col items-start justify-between gap-md sm:flex-row sm:items-center">
        <h1 className="text-headline-lg text-on-surface">文件列表</h1>
        <div className="flex flex-wrap gap-xs">
          {filters.map((filter) => (
            <button key={filter.label} type="button" onClick={() => applyParams({ mime: filter.mime, page: 1 })} className={cn('rounded-full px-md py-1.5 text-label-md transition-colors', mime === filter.mime ? 'bg-primary text-on-primary' : 'border border-outline-variant bg-surface-container-lowest text-on-surface hover:bg-surface-container-low')}>{filter.label}</button>
          ))}
        </div>
      </div>

      {loading && files.length === 0 ? (
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">{Array.from({ length: 8 }, (_, index) => <div key={index} className="h-[242px] animate-pulse rounded-xl border border-outline-variant bg-surface-container-lowest"><div className="h-40 bg-surface-container" /></div>)}</div>
      ) : files.length === 0 ? (
        <section className="flex min-h-[320px] flex-col items-center justify-center rounded-xl border border-dashed border-outline-variant bg-surface-container-lowest text-on-surface-variant">
          <MaterialIcon className="mb-md text-[52px] text-primary">folder_open</MaterialIcon>
          <h2 className="text-headline-md text-on-surface">暂无文件</h2>
          <p className="mt-xs text-body-md">上传文件后即可在这里查看。</p>
          <button type="button" onClick={() => setView('upload')} className="mt-lg rounded-full bg-primary px-lg py-sm text-label-md text-white">上传文件</button>
        </section>
      ) : (
        <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">{files.map((file) => <FileCard key={file.fileId} file={file} />)}</div>
      )}

      {total > 0 && (
        <div className="mt-xl flex items-center justify-center gap-sm">
          <PageButton disabled={page <= 1} onClick={() => applyParams({ page: page - 1 })} icon="chevron_left" />
          {pagination(page, totalPages).map((item, index) => item === 'ellipsis'
            ? <span key={`ellipsis-${index}`} className="text-on-surface-variant">...</span>
            : <button key={item} type="button" onClick={() => applyParams({ page: item as number })} className={cn('flex h-8 w-8 items-center justify-center rounded text-label-md', page === item ? 'bg-primary text-on-primary' : 'border border-outline-variant text-on-surface-variant hover:bg-surface-container-low')}>{item}</button>)}
          <PageButton disabled={page >= totalPages} onClick={() => applyParams({ page: page + 1 })} icon="chevron_right" />
        </div>
      )}
    </div>
  );
}

function PageButton({ disabled, onClick, icon }: { disabled: boolean; onClick: () => void; icon: string }) {
  return <button type="button" disabled={disabled} onClick={onClick} className="flex h-8 w-8 items-center justify-center rounded border border-outline-variant text-on-surface-variant hover:bg-surface-container-low disabled:opacity-40"><MaterialIcon className="text-[18px]">{icon}</MaterialIcon></button>;
}

function pagination(page: number, totalPages: number): Array<number | 'ellipsis'> {
  if (totalPages <= 4) return Array.from({ length: totalPages }, (_, index) => index + 1);
  if (page <= 2) return [1, 2, 3, 'ellipsis'];
  if (page >= totalPages - 1) return ['ellipsis', totalPages - 2, totalPages - 1, totalPages];
  return [page - 1, page, page + 1, 'ellipsis'];
}
