import { useEffect, useState } from 'react';
import { Sidebar } from './components/Sidebar';
import { AiHub } from './components/AiHub';
import { Topbar } from './components/Topbar';
import { QuotaWidget } from './components/QuotaWidget';
import { UploadDropZone } from './components/UploadDropZone';
import { UploadQueue } from './components/UploadQueue';
import { FilesView } from './components/FilesView';
import { FileDrawer } from './components/FileDrawer';
import { DriveView } from './components/DriveView';
import { RecycleBin } from './components/RecycleBin';
import { ShareList } from './components/ShareList';
import { AdminPanel } from './components/AdminPanel';
import { Toaster } from './components/ui/Toaster';
import { MaterialIcon } from './components/ui/MaterialIcon';
import { useAppStore } from './store';
import { LoginPage } from './components/LoginPage';
import { getPublicShareId, PublicSharePage } from './components/PublicSharePage';

export default function App() {
  const [shareId, setShareId] = useState(getPublicShareId);

  useEffect(() => {
    const onHashChange = () => setShareId(getPublicShareId());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  if (shareId) return <PublicSharePage shareId={shareId} />;
  return <AuthenticatedApp />;
}

function AuthenticatedApp() {
  const authChecked = useAppStore((s) => s.authChecked);
  const authUser = useAppStore((s) => s.authUser);
  const initAuth = useAppStore((s) => s.initAuth);
  const view = useAppStore((s) => s.view);
  const refreshQuota = useAppStore((s) => s.refreshQuota);
  const setView = useAppStore((s) => s.setView);
  const [aiFocusTick, setAiFocusTick] = useState(0);
  const refreshFiles = useAppStore((s) => s.refreshFiles);
  const refreshShares = useAppStore((s) => s.refreshShares);

  useEffect(() => {
    void initAuth();
  }, [initAuth]);

  useEffect(() => {
    const onUnauthorized = () => useAppStore.setState({ authUser: null, quota: null, files: [], uploads: [], activeFileId: null });
    window.addEventListener('cloudchunk:unauthorized', onUnauthorized);
    return () => window.removeEventListener('cloudchunk:unauthorized', onUnauthorized);
  }, []);

  useEffect(() => {
    if (!authUser) return;
    void Promise.all([refreshQuota(), refreshFiles(), refreshShares()]);
    const timer = window.setInterval(refreshQuota, 30000);
    return () => window.clearInterval(timer);
  }, [authUser, refreshFiles, refreshQuota, refreshShares]);

  // Ctrl/Command+K opens the user's private AI knowledge search, without sending
  // a query to the model or making an external request.
  useEffect(() => {
    if (!authUser) return;
    const onShortcut = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setView('ai');
        setAiFocusTick(v => v + 1);
      }
    };
    window.addEventListener('keydown', onShortcut);
    return () => window.removeEventListener('keydown', onShortcut);
  }, [authUser, setView]);

  if (!authChecked) {
    return <div className="flex h-full items-center justify-center bg-background text-body-md text-on-surface-variant"><MaterialIcon className="mr-sm animate-pulse text-primary">cloud_sync</MaterialIcon>正在连接 CloudChunk…</div>;
  }

  if (!authUser) return <><LoginPage /><Toaster /></>;

  return (
    <div className="h-screen overflow-hidden bg-background text-on-background">
      <Sidebar />
      <div className="h-screen md:ml-[260px]">
        <div className="sticky top-0 z-30 flex gap-xs overflow-x-auto border-b border-outline-variant bg-surface p-sm md:hidden" aria-label="移动端页面导航">
          {([['ai','AI 工作台','auto_awesome'],['upload','上传','cloud_upload'],['drive','云盘','folder'],['files','文件','description'],['shares','分享','share'],['recycle','回收站','delete']] as const).map(([target,label,icon]) => (
            <button key={target} type="button" onClick={() => setView(target)}
              aria-current={view === target ? 'page' : undefined}
              className={`flex min-w-[76px] flex-none items-center justify-center gap-1 rounded-lg px-sm py-sm text-label-sm ${view === target ? 'bg-primary-container font-bold text-on-primary-container' : 'text-on-surface-variant'}`}>
              <MaterialIcon className="text-[18px]">{icon}</MaterialIcon>{label}
            </button>
          ))}
          {authUser.role === 'admin' && (
            <button type="button" onClick={() => setView('admin')}
              aria-current={view === 'admin' ? 'page' : undefined}
              className={`flex min-w-[76px] flex-none items-center justify-center gap-1 rounded-lg px-sm py-sm text-label-sm ${view === 'admin' ? 'bg-primary-container font-bold text-on-primary-container' : 'text-on-surface-variant'}`}>
              <MaterialIcon className="text-[18px]">admin_panel_settings</MaterialIcon>管理
            </button>
          )}
        </div>
        <Topbar />
        <main className="h-screen overflow-y-auto pt-0 md:h-[calc(100vh-64px)] md:pt-0 md:mt-16">
          {view === 'ai' && <AiHub focusTick={aiFocusTick} />}
          {view === 'upload' && (
            <div className="p-lg lg:p-xl">
              <div className="mb-lg">
                <h1 className="text-headline-lg text-on-surface">上传中心</h1>
                <p className="mt-xs text-body-md text-on-surface-variant">拖放文件并实时管理上传任务。完成完整性校验后，可在 AI 工作台建立可检索资料。</p>
                <button type="button" className="mt-md inline-flex items-center gap-sm rounded-lg border border-primary-fixed-dim bg-primary-fixed px-md py-sm text-label-md text-primary" onClick={() => setView('ai')}>
                  <MaterialIcon>auto_awesome</MaterialIcon>前往 AI 智能工作台 <MaterialIcon>arrow_forward</MaterialIcon>
                </button>
              </div>
              <div className="grid grid-cols-1 items-start gap-lg lg:grid-cols-12">
                <div className="space-y-lg lg:col-span-8">
                  <UploadDropZone />
                  <UploadQueue />
                </div>
                <aside className="space-y-lg lg:col-span-4">
                  <QuotaWidget />
                  <UploadTip />
                </aside>
              </div>
            </div>
          )}
          {view === 'files' && <FilesView />}
          {view === 'drive' && <DriveView />}
          {view === 'recycle' && <RecycleBin />}
          {view === 'shares' && <ShareList />}
          {view === 'admin' && <AdminPanel />}
        </main>
      </div>
      <FileDrawer />
      <Toaster />
    </div>
  );
}

function UploadTip() {
  return (
    <section className="rounded-xl border border-primary-fixed-dim bg-primary-fixed/45 p-lg text-on-surface">
      <div className="flex items-start gap-md">
        <MaterialIcon className="mt-xs text-primary">lightbulb</MaterialIcon>
        <div>
          <h3 className="text-body-md font-semibold">上传提示：智能并发</h3>
          <p className="mt-sm text-body-md leading-5 text-on-surface-variant">CloudChunk 会自动切分大文件并并发上传，充分利用网络带宽，无需提前压缩文件。</p>
          <span className="mt-md inline-flex items-center gap-xs text-label-md text-primary">支持秒传、断点续传与完整性校验 <MaterialIcon className="text-[17px]">verified</MaterialIcon></span>
        </div>
      </div>
    </section>
  );
}
