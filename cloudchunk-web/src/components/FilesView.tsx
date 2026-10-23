import { useEffect, useMemo, useState } from 'react';
import { useAppStore } from '../store';
import { cn } from '../lib/utils';
import { FileCard } from './FileCard';
import { MaterialIcon } from './ui/MaterialIcon';
import { KnowledgeWorkspace } from './KnowledgeWorkspace';

const filters = [
  { label: '全部', mime: '' },
  { label: '图片', mime: 'image/' },
  { label: '视频', mime: 'video/' },
  { label: '音频', mime: 'audio/' },
  { label: '文档与其他', mime: 'application/' },
];

export function FilesView() {
  const files = useAppStore((s) => s.files);
  const total = useAppStore((s) => s.filesTotal);
  const page = useAppStore((s) => s.filesPage);
  const size = useAppStore((s) => s.filesSize);
  const mime = useAppStore((s) => s.filesMime);
  const keyword = useAppStore((s) => s.filesKeyword);
  const loading = useAppStore((s) => s.filesLoading);
  const refresh = useAppStore((s) => s.refreshFiles);
  const setParams = useAppStore((s) => s.setFilesParams);
  const setView = useAppStore((s) => s.setView);
  const userId = useAppStore((s) => s.authUser?.id);
  const [mode, setMode] = useState<'files' | 'knowledge'>('files');
  const totalPages = useMemo(() => Math.max(1, Math.ceil(total / size)), [size, total]);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  // Existing topbar search remains filename search, and always exposes its results.
  useEffect(() => {
    setMode('files');
  }, [keyword]);

  return (
    <div className="mx-auto max-w-[1600px] p-margin-mobile md:p-margin-desktop">
      <header className="mb-lg flex flex-wrap items-center justify-between gap-md">
        <div>
          <span className="eyebrow">ALL FILES</span>
          <h1 className="text-headline-lg mt-sm text-on-surface">全部文件</h1>
        </div>
        <button
          type="button"
          onClick={() => setView('upload')}
          className="inline-flex items-center gap-sm rounded-xl bg-primary px-lg py-sm text-label-md text-on-primary shadow-sm"
        >
          <MaterialIcon>add</MaterialIcon>上传文件
        </button>
      </header>
      <div className="mb-lg flex flex-wrap items-center justify-between gap-md border-b border-outline-variant pb-md">
        <div className="inline-flex gap-xs rounded-xl bg-surface-container p-1" aria-label="工作空间视图">
          <button
            type="button"
            aria-pressed={mode === 'files'}
            onClick={() => setMode('files')}
            className={cn(
              'inline-flex items-center gap-sm rounded-lg px-md py-sm text-label-md',
              mode === 'files'
                ? 'bg-surface-container-lowest text-on-surface shadow-sm'
                : 'text-on-surface-variant',
            )}
          >
            <MaterialIcon className="text-[19px]">folder_open</MaterialIcon>文件浏览
          </button>
          <button
            type="button"
            aria-pressed={mode === 'knowledge'}
            onClick={() => setMode('knowledge')}
            className={cn(
              'inline-flex items-center gap-sm rounded-lg px-md py-sm text-label-md',
              mode === 'knowledge'
                ? 'bg-surface-container-lowest text-on-surface shadow-sm'
                : 'text-on-surface-variant',
            )}
          >
            <MaterialIcon className="text-[19px]">auto_stories</MaterialIcon>资料工作台
          </button>
        </div>
        <label className="drive-search">
          <MaterialIcon>search</MaterialIcon>
          <input
            aria-label="搜索全部文件"
            placeholder="搜索文件名…"
            value={keyword}
            onChange={(event) => setParams({ keyword: event.target.value, page: 1 })}
          />
        </label>
      </div>
      {mode === 'knowledge' ? (
        <KnowledgeWorkspace key={userId} />
      ) : (
        <>
          <div className="mb-lg flex flex-wrap items-center justify-between gap-md">
            <p className="text-body-md text-on-surface-variant">
              {keyword ? `文件名包含“${keyword}”` : '全部文件'}
              <span className="ml-sm font-semibold text-on-surface">{total}</span>
            </p>
            <div className="flex flex-wrap gap-xs" aria-label="文件类型筛选">
              {filters.map((filter) => (
                <button
                  key={filter.label}
                  type="button"
                  aria-pressed={mime === filter.mime}
                  onClick={() => setParams({ mime: filter.mime, page: 1 })}
                  className={cn(
                    'rounded-full px-md py-1.5 text-label-md transition-colors',
                    mime === filter.mime
                      ? 'bg-primary text-on-primary'
                      : 'border border-outline-variant bg-surface-container-lowest text-on-surface hover:bg-surface-container-low',
                  )}
                >
                  {filter.label}
                </button>
              ))}
            </div>
          </div>
          {loading && files.length === 0 ? (
            <div
              className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
              role="status"
              aria-label="正在加载文件"
            >
              {Array.from({ length: 8 }, (_, index) => (
                <div
                  key={index}
                  className="h-[242px] animate-pulse rounded-xl border border-outline-variant bg-surface-container-lowest"
                >
                  <div className="h-40 bg-surface-container" />
                </div>
              ))}
            </div>
          ) : files.length === 0 ? (
            <section className="flex min-h-[320px] flex-col items-center justify-center rounded-xl border border-dashed border-outline-variant bg-surface-container-lowest p-lg text-center text-on-surface-variant">
              <MaterialIcon className="mb-md text-[52px] text-primary">folder_open</MaterialIcon>
              <h2 className="text-headline-md text-on-surface">
                {keyword || mime ? '没有匹配的文件' : '从第一份文件开始'}
              </h2>
              <p className="mt-xs text-body-md">
                {keyword || mime ? '试试其他名称或文件类型' : '上传你的第一份文件'}
              </p>
              <button
                type="button"
                onClick={() =>
                  keyword || mime ? setParams({ keyword: '', mime: '', page: 1 }) : setView('upload')
                }
                className="mt-lg rounded-full bg-primary px-lg py-sm text-label-md text-on-primary"
              >
                {keyword || mime ? '清除筛选' : '上传文件'}
              </button>
            </section>
          ) : (
            <div className="grid grid-cols-1 gap-md sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {files.map((file) => (
                <FileCard key={file.fileId} file={file} />
              ))}
            </div>
          )}
          {total > 0 && (
            <nav className="mt-xl flex flex-wrap items-center justify-center gap-sm" aria-label="文件分页">
              <PageButton
                label="上一页"
                disabled={page <= 1 || loading}
                onClick={() => setParams({ page: page - 1 })}
                icon="chevron_left"
              />
              {pagination(page, totalPages).map((item, index) =>
                item === 'ellipsis' ? (
                  <span key={`ellipsis-${index}`} className="text-on-surface-variant">
                    …
                  </span>
                ) : (
                  <button
                    key={item}
                    type="button"
                    disabled={loading}
                    aria-current={page === item ? 'page' : undefined}
                    aria-label={`第 ${item} 页`}
                    onClick={() => setParams({ page: item })}
                    className={cn(
                      'flex h-9 min-w-9 items-center justify-center rounded-lg px-xs text-label-md',
                      page === item
                        ? 'bg-primary text-on-primary'
                        : 'border border-outline-variant text-on-surface-variant hover:bg-surface-container-low',
                    )}
                  >
                    {item}
                  </button>
                ),
              )}
              <PageButton
                label="下一页"
                disabled={page >= totalPages || loading}
                onClick={() => setParams({ page: page + 1 })}
                icon="chevron_right"
              />
            </nav>
          )}
        </>
      )}
    </div>
  );
}

function PageButton({
  disabled,
  onClick,
  icon,
  label,
}: {
  disabled: boolean;
  onClick: () => void;
  icon: string;
  label: string;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="flex h-9 w-9 items-center justify-center rounded-lg border border-outline-variant text-on-surface-variant hover:bg-surface-container-low disabled:opacity-40"
    >
      <MaterialIcon className="text-[18px]">{icon}</MaterialIcon>
    </button>
  );
}
function pagination(page: number, totalPages: number): Array<number | 'ellipsis'> {
  const pages: Array<number | 'ellipsis'> = [];
  for (let n = 1; n <= totalPages; n++) {
    if (n === 1 || n === totalPages || Math.abs(n - page) <= 1) pages.push(n);
    else if (pages[pages.length - 1] !== 'ellipsis') pages.push('ellipsis');
  }
  return pages;
}
