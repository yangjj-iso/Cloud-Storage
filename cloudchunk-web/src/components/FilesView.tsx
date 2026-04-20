import { ChevronLeft, ChevronRight, FolderOpen, RefreshCw, Search, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useAppStore } from '../store';
import { Button } from './ui/Button';
import { Empty } from './ui/Empty';
import { FileCard } from './FileCard';
import { cn } from '../lib/utils';

const MIME_FILTERS: Array<{ key: string; label: string; prefix: string }> = [
  { key: 'all', label: '全部', prefix: '' },
  { key: 'image', label: '图片', prefix: 'image/' },
  { key: 'video', label: '视频', prefix: 'video/' },
  { key: 'audio', label: '音频', prefix: 'audio/' },
  { key: 'doc', label: '文档', prefix: 'application/' },
  { key: 'text', label: '文本', prefix: 'text/' },
];

export function FilesView() {
  const files = useAppStore((s) => s.files);
  const total = useAppStore((s) => s.filesTotal);
  const page = useAppStore((s) => s.filesPage);
  const size = useAppStore((s) => s.filesSize);
  const loading = useAppStore((s) => s.filesLoading);
  const mime = useAppStore((s) => s.filesMime);
  const keyword = useAppStore((s) => s.filesKeyword);
  const setParams = useAppStore((s) => s.setFilesParams);
  const refresh = useAppStore((s) => s.refreshFiles);
  const setView = useAppStore((s) => s.setView);

  const [localKeyword, setLocalKeyword] = useState(keyword);

  useEffect(() => {
    const h = setTimeout(() => {
      if (localKeyword !== keyword) setParams({ keyword: localKeyword, page: 1 });
    }, 350);
    return () => clearTimeout(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [localKeyword]);

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const totalPages = Math.max(1, Math.ceil(total / size));
  const activeFilter = useMemo(
    () => MIME_FILTERS.find((f) => f.prefix === mime)?.key ?? 'all',
    [mime]
  );

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[220px]">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <input
            value={localKeyword}
            onChange={(e) => setLocalKeyword(e.target.value)}
            placeholder="按文件名搜索…"
            className="h-9 w-full rounded-lg border border-slate-200 bg-white pl-9 pr-9 text-sm placeholder:text-slate-400 focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-500/20"
          />
          {localKeyword && (
            <button
              onClick={() => setLocalKeyword('')}
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-slate-400 hover:bg-slate-100"
              aria-label="清除"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
        <Button
          variant="secondary"
          size="md"
          onClick={refresh}
          leftIcon={<RefreshCw className={cn('h-4 w-4', loading && 'animate-spin')} />}
        >
          刷新
        </Button>
      </div>

      <div className="flex flex-wrap gap-1.5">
        {MIME_FILTERS.map((f) => (
          <button
            key={f.key}
            onClick={() => setParams({ mime: f.prefix, page: 1 })}
            className={cn(
              'rounded-full px-3 py-1 text-xs font-medium transition-colors',
              activeFilter === f.key
                ? 'bg-brand-600 text-white shadow-sm shadow-brand-600/30'
                : 'bg-white text-slate-600 ring-1 ring-inset ring-slate-200 hover:bg-slate-50'
            )}
          >
            {f.label}
          </button>
        ))}
      </div>

      {loading && files.length === 0 ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {Array.from({ length: 10 }).map((_, i) => (
            <div
              key={i}
              className="animate-pulse overflow-hidden rounded-xl border border-slate-200 bg-white"
            >
              <div className="aspect-[4/3] bg-slate-100" />
              <div className="p-3">
                <div className="h-3 w-3/4 rounded bg-slate-100" />
                <div className="mt-2 h-2 w-1/2 rounded bg-slate-100" />
              </div>
            </div>
          ))}
        </div>
      ) : files.length === 0 ? (
        <Empty
          icon={<FolderOpen className="h-12 w-12" />}
          title="暂无文件"
          description="上传后文件会在这里出现。"
          action={
            <Button onClick={() => setView('upload')}>去上传</Button>
          }
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
          {files.map((f) => (
            <FileCard key={f.fileId} file={f} />
          ))}
        </div>
      )}

      {total > 0 && (
        <div className="flex items-center justify-between border-t border-slate-200 pt-4 text-xs text-slate-500">
          <div>
            共 {total} 项 · 第 {page} / {totalPages} 页
          </div>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="sm"
              disabled={page <= 1}
              onClick={() => setParams({ page: page - 1 })}
              leftIcon={<ChevronLeft className="h-3.5 w-3.5" />}
            >
              上一页
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={page >= totalPages}
              onClick={() => setParams({ page: page + 1 })}
              rightIcon={<ChevronRight className="h-3.5 w-3.5" />}
            >
              下一页
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
