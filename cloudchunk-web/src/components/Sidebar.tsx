import { useEffect, useState } from 'react';
import { useAppStore, type AdminTab, type View } from '../store';
import { cn } from '../lib/utils';
import { api } from '../lib/api';
import { MaterialIcon } from './ui/MaterialIcon';

const mainItems: Array<{ id: View; label: string; icon: string }> = [
  { id: 'upload', label: '上传中心', icon: 'cloud_upload' },
  { id: 'drive', label: '我的云盘', icon: 'folder_shared' },
  { id: 'files', label: '文件列表', icon: 'list_alt' },
  { id: 'shares', label: '我的分享', icon: 'share' },
  { id: 'recycle', label: '回收站', icon: 'delete' },
];

const adminItems: Array<{ id: AdminTab; label: string; icon: string }> = [
  { id: 'users', label: '用户管理', icon: 'group' },
  { id: 'files', label: '文件管理', icon: 'folder_managed' },
  { id: 'settings', label: '系统设置', icon: 'settings_applications' },
  { id: 'health', label: '依赖状态', icon: 'monitor_heart' },
];

export function Sidebar() {
  const view = useAppStore((s) => s.view);
  const setView = useAppStore((s) => s.setView);
  const adminTab = useAppStore((s) => s.adminTab);
  const setAdminTab = useAppStore((s) => s.setAdminTab);
  const authUser = useAppStore((s) => s.authUser);
  const isAdmin = authUser?.role === 'admin';
  const [serviceOnline, setServiceOnline] = useState<boolean | null>(null);

  useEffect(() => {
    if (!isAdmin || view !== 'admin') return;
    let cancelled = false;
    api.ping().then(() => { if (!cancelled) setServiceOnline(true); }).catch(() => { if (!cancelled) setServiceOnline(false); });
    return () => { cancelled = true; };
  }, [isAdmin, view]);

  return (
    <aside className="fixed inset-y-0 left-0 z-50 hidden w-[260px] flex-col border-r border-outline-variant bg-surface px-sm py-md md:flex">
      <button type="button" onClick={() => setView('upload')} className="mb-xl flex items-center gap-sm px-md text-left">
        <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary-container text-on-primary-container">
          <MaterialIcon className="text-[21px]" filled>cloud</MaterialIcon>
        </span>
        <span>
          <span className="block text-[24px] font-bold leading-7 tracking-[-0.025em] text-primary">CloudChunk</span>
          <span className="block text-label-sm text-on-surface-variant">个人云存储</span>
        </span>
      </button>

      <nav className="flex min-h-0 flex-1 flex-col">
        <div className="flex flex-col gap-xs">
          {mainItems.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setView(item.id)}
              className={cn(
                'group flex h-10 items-center gap-md rounded-lg px-md text-left text-body-md transition-colors duration-200',
                view === item.id
                  ? 'bg-surface-container-highest font-semibold text-on-secondary-container'
                  : 'text-on-surface-variant hover:bg-surface-container'
              )}
            >
              <MaterialIcon className={cn('text-[22px]', view === item.id ? 'text-primary' : 'text-outline group-hover:text-primary')} filled={view === item.id}>
                {item.icon}
              </MaterialIcon>
              <span>{item.label}</span>
            </button>
          ))}
        </div>

        {isAdmin && view === 'admin' && (
          <div className="mt-md border-t border-outline-variant pt-sm">
            <div className="px-md pb-xs text-label-md tracking-[0.08em] text-on-surface-variant">系统管理</div>
            <div className="flex flex-col gap-xs">
              {adminItems.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setAdminTab(item.id)}
                  className={cn(
                    'group flex h-10 items-center gap-md rounded-lg px-md text-left text-body-md transition-colors',
                    adminTab === item.id
                      ? 'bg-surface-container-highest font-semibold text-on-secondary-container'
                      : 'text-on-surface-variant hover:bg-surface-container'
                  )}
                >
                  <MaterialIcon className={cn('text-[21px]', adminTab === item.id ? 'text-primary' : 'text-outline')} filled={adminTab === item.id}>
                    {item.icon}
                  </MaterialIcon>
                  <span>{item.label}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        {isAdmin && view !== 'admin' && (
          <div className="mt-auto border-t border-outline-variant pt-sm">
            <button
              type="button"
              onClick={() => setAdminTab('users')}
              className="flex h-10 w-full items-center gap-md rounded-lg px-md text-left text-body-md text-on-surface-variant transition-colors hover:bg-surface-container"
            >
              <MaterialIcon className="text-[22px] text-outline">admin_panel_settings</MaterialIcon>
              <span>管理后台</span>
            </button>
          </div>
        )}

        {isAdmin && view === 'admin' && (
          <div className="mt-auto border-t border-outline-variant pt-md">
            <div className="rounded-xl bg-surface-container-low px-md py-sm">
              <div className="flex items-center justify-between text-label-md text-on-surface">
                <span>后端服务</span><span className={serviceOnline === false ? 'text-error' : 'text-primary'}>{serviceOnline === null ? '检测中' : serviceOnline ? '在线' : '离线'}</span>
              </div>
              <div className="mt-sm h-1 rounded-full bg-outline-variant"><div className={cn('h-full rounded-full', serviceOnline === false ? 'w-full bg-error' : serviceOnline === true ? 'w-full bg-primary' : 'w-1/2 animate-pulse bg-secondary')} /></div>
            </div>
          </div>
        )}
      </nav>
    </aside>
  );
}
