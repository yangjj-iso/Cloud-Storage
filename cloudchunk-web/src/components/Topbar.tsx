import { useEffect, useMemo, useRef, useState } from 'react';
import { useAppStore } from '../store';
import { cn } from '../lib/utils';
import { MaterialIcon } from './ui/MaterialIcon';
import { AccountSettingsDialog } from './AccountSettingsDialog';
import { roleLabel } from '../lib/locale';

export function Topbar() {
  const view = useAppStore((s) => s.view);
  const quota = useAppStore((s) => s.quota);
  const uploads = useAppStore((s) => s.uploads);
  const authUser = useAppStore((s) => s.authUser);
  const logout = useAppStore((s) => s.logout);
  const showToast = useAppStore((s) => s.showToast);
  const filesKeyword = useAppStore((s) => s.filesKeyword);
  const setFilesParams = useAppStore((s) => s.setFilesParams);
  const adminTab = useAppStore((s) => s.adminTab);
  const adminUsersKeyword = useAppStore((s) => s.adminUsersKeyword);
  const setAdminUsersParams = useAppStore((s) => s.setAdminUsersParams);
  const [search, setSearch] = useState(filesKeyword);
  const [menuOpen, setMenuOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (view === 'files') setSearch(filesKeyword);
    else if (view === 'admin' && adminTab === 'users') setSearch(adminUsersKeyword);
    else setSearch('');
  }, [adminTab, adminUsersKeyword, filesKeyword, view]);

  useEffect(() => {
    const searchingFiles = view === 'files';
    const searchingUsers = view === 'admin' && adminTab === 'users';
    if (!searchingFiles && !searchingUsers) return;
    const current = searchingFiles ? filesKeyword : adminUsersKeyword;
    if (search === current) return;
    const timer = window.setTimeout(() => {
      if (searchingFiles) setFilesParams({ keyword: search, page: 1 });
      else setAdminUsersParams({ keyword: search, page: 1 });
    }, 300);
    return () => window.clearTimeout(timer);
  }, [adminTab, adminUsersKeyword, filesKeyword, search, setAdminUsersParams, setFilesParams, view]);

  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const activeUploads = uploads.filter((item) => ['HASHING', 'INITIATING', 'UPLOADING', 'MERGING', 'PAUSED'].includes(item.status)).length;
  const storage = quota && quota.totalBytes > 0 ? Math.min(100, Math.round((quota.usedBytes / quota.totalBytes) * 100)) : 0;
  const initials = useMemo(() => {
    const value = authUser?.nickname || authUser?.username || '用户';
    return value.split(/\s+/).map((part) => part[0]).join('').slice(0, 2).toUpperCase();
  }, [authUser]);

  const searchPlaceholder = view === 'admin' ? (adminTab === 'users' ? '搜索用户名或邮箱…' : '当前页面暂不支持搜索') : view === 'recycle' ? '回收站' : '搜索文件…';
  const searchEnabled = view === 'files' || (view === 'admin' && adminTab === 'users');
  const storageBar = view === 'recycle' ? 'bg-error' : 'bg-primary';

  return (
    <>
    <header className="fixed right-0 top-0 z-40 hidden h-16 w-[calc(100%-260px)] items-center justify-between border-b border-outline-variant bg-surface/90 px-lg shadow-sm backdrop-blur-md md:flex">
      {view === 'ai' ? (
        <button type="button" onClick={() => window.dispatchEvent(new Event('cloudchunk:focus-ai-search'))}
          className="flex h-10 w-[450px] max-w-full items-center gap-md rounded-xl border border-outline-variant bg-surface-container-lowest px-md text-left text-body-md text-on-surface-variant hover:border-primary shadow-sm">
          <MaterialIcon className="text-[21px]">search</MaterialIcon>
          <span className="flex-1">搜索我的知识资料…</span>
          <kbd className="rounded border border-outline-variant bg-surface-container px-xs py-1 text-label-sm">⌘ / Ctrl K</kbd>
        </button>
      ) : view === 'drive' ? (
        <div className="flex items-center gap-sm text-body-md font-semibold text-on-surface-variant">
          <MaterialIcon className="text-[19px]">home</MaterialIcon><span>根目录</span>
        </div>
      ) : (
        <label className={cn('relative block', view === 'recycle' ? 'w-[256px]' : view === 'admin' ? 'w-[380px]' : 'w-[450px]')}>
          <MaterialIcon className="pointer-events-none absolute left-md top-1/2 -translate-y-1/2 text-[22px] text-outline">search</MaterialIcon>
          <input
            value={searchEnabled ? search : ''}
            onChange={(event) => searchEnabled && setSearch(event.target.value)}
            readOnly={!searchEnabled}
            placeholder={searchPlaceholder}
            className="h-10 w-full rounded-xl border border-outline-variant bg-surface-container-lowest pl-12 pr-md text-body-lg text-on-surface shadow-sm outline-none placeholder:text-outline focus:border-primary focus:ring-2 focus:ring-primary-fixed"
          />
        </label>
      )}

      <div className="flex h-full items-center gap-md text-body-md text-on-surface-variant">
        {view === 'upload' ? (
          <>
            <span className="rounded-full bg-surface-container px-md py-xs font-semibold">{activeUploads} 个上传任务</span>
            <span className="rounded-full bg-primary-fixed px-md py-xs font-semibold text-primary">存储：{storage}%</span>
          </>
        ) : view === 'admin' ? (
          <div className="min-w-[130px] text-right text-label-md leading-4 text-on-surface-variant">
            <div className="font-bold text-on-surface">存储：{storage}%</div>
            <div>{activeUploads} 个上传任务</div>
          </div>
        ) : (
          <>
            <div className="flex min-w-[136px] flex-col gap-xs text-center font-semibold">
              <span>存储：{storage}%</span>
              <span className="h-1 w-full overflow-hidden rounded-full bg-surface-container-high"><span className={cn('block h-full rounded-full', storageBar)} style={{ width: `${storage}%` }} /></span>
            </div>
            <span className="h-5 w-px bg-outline-variant" />
            <span className={cn('font-medium', view === 'recycle' && 'text-primary')}>
              {view === 'recycle' && <MaterialIcon className="mr-xs align-[-3px] text-[17px]">cloud_sync</MaterialIcon>}
              {activeUploads} 个上传任务
            </span>
          </>
        )}

        <span className="h-5 w-px bg-outline-variant" />
        <button onClick={() => showToast({ kind: 'info', title: '暂无新通知' })} className="stitch-icon-button relative" type="button" aria-label="通知">
          <MaterialIcon>notifications</MaterialIcon>
          {view === 'admin' && <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-error" />}
        </button>
        <button onClick={() => setSettingsOpen(true)} className="stitch-icon-button" type="button" aria-label="账户设置"><MaterialIcon>settings</MaterialIcon></button>
        <div className="relative" ref={menuRef}>
          <button
            type="button"
            aria-label="账户菜单"
            onClick={() => setMenuOpen((open) => !open)}
            className="flex h-9 w-9 items-center justify-center rounded-xl border border-outline-variant bg-primary-container text-label-md font-bold text-white"
          >
            {initials}
          </button>
          {menuOpen && (
            <div className="absolute right-0 top-11 w-48 rounded-xl border border-outline-variant bg-white p-xs shadow-ambient">
              <div className="border-b border-surface-container px-sm py-sm">
                <div className="truncate text-body-md font-semibold text-on-surface">{authUser?.nickname || authUser?.username}</div>
                <div className="truncate text-label-sm text-on-surface-variant">{authUser?.email || roleLabel(authUser?.role)}</div>
              </div>
              <button type="button" onClick={() => logout()} className="mt-xs flex w-full items-center gap-sm rounded-lg px-sm py-sm text-left text-body-md text-on-surface-variant hover:bg-surface-container">
                <MaterialIcon className="text-[18px]">logout</MaterialIcon> 退出登录
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
    {settingsOpen && <AccountSettingsDialog onClose={() => setSettingsOpen(false)} />}
    </>
  );
}
