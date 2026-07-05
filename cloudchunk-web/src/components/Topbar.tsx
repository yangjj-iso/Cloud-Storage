import { CloudUpload, FolderOpen, LogOut, UserRound } from 'lucide-react';
import { useAppStore, type View } from '../store';
import { cn, formatBytes } from '../lib/utils';

export function Topbar() {
  const view = useAppStore((s) => s.view);
  const setView = useAppStore((s) => s.setView);
  const quota = useAppStore((s) => s.quota);
  const uploads = useAppStore((s) => s.uploads);
  const authUser = useAppStore((s) => s.authUser);
  const logout = useAppStore((s) => s.logout);

  const active = uploads.filter((u) =>
    ['HASHING', 'INITIATING', 'UPLOADING', 'MERGING'].includes(u.status)
  ).length;

  const items: Array<{ id: View; label: string; icon: typeof CloudUpload }> = [
    { id: 'upload', label: '上传', icon: CloudUpload },
    { id: 'files', label: '文件', icon: FolderOpen },
  ];

  return (
    <header className="flex items-center gap-4 border-b border-slate-200 bg-white/80 px-5 py-3 backdrop-blur">
      <div className="flex gap-1 md:hidden">
        {items.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setView(id)}
            className={cn(
              'flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium',
              view === id
                ? 'bg-brand-50 text-brand-700'
                : 'text-slate-600 hover:bg-slate-100'
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      <div className="hidden text-lg font-semibold tracking-tight text-slate-900 md:block">
        {view === 'upload' ? '上传中心'
          : view === 'drive' ? '我的网盘'
          : view === 'files' ? '文件列表'
          : view === 'shares' ? '我的分享'
          : view === 'recycle' ? '回收站'
          : view === 'admin' ? '管理后台'
          : 'CloudChunk'}
      </div>

      <div className="ml-auto flex items-center gap-3 text-xs">
        {active > 0 && (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-brand-50 px-2.5 py-1 font-medium text-brand-700">
            <span className="relative flex h-1.5 w-1.5">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-brand-400 opacity-75" />
              <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-brand-500" />
            </span>
            上传中 {active}
          </span>
        )}
        {quota && (
          <span className="hidden text-slate-500 sm:inline">
            配额：
            <span className="font-medium text-slate-700">
              {formatBytes(quota.usedBytes)}
            </span>
            <span className="text-slate-400"> / {formatBytes(quota.totalBytes)}</span>
          </span>
        )}
        {authUser && (
          <span className="hidden items-center gap-1.5 rounded-full bg-slate-100 px-2.5 py-1 font-medium text-slate-600 sm:inline-flex">
            <UserRound className="h-3.5 w-3.5" />
            {authUser.nickname || authUser.username}
          </span>
        )}
        <button
          onClick={() => logout()}
          className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-500 hover:bg-slate-100 hover:text-slate-800"
          aria-label="退出登录"
          title="退出登录"
        >
          <LogOut className="h-4 w-4" />
        </button>
      </div>
    </header>
  );
}
