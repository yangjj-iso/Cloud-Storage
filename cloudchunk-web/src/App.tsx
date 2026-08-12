import { useEffect, useState } from 'react';
import { Sidebar } from './components/Sidebar';
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

  if (!authChecked) {
    return <div className="flex h-full items-center justify-center bg-background text-body-md text-on-surface-variant"><MaterialIcon className="mr-sm animate-pulse text-primary">cloud_sync</MaterialIcon>正在连接 CloudChunk…</div>;
  }

  if (!authUser) return <><LoginPage /><Toaster /></>;

  return (
    <div className="h-screen overflow-hidden bg-background text-on-background">
      <Sidebar />
      <div className="h-screen md:ml-[260px]">
        <Topbar />
        <main className="h-screen overflow-y-auto pt-0 md:h-[calc(100vh-64px)] md:pt-0 md:mt-16">
          {view === 'upload' && (
            <div className="p-lg lg:p-xl">
              <div className="mb-lg">
                <h1 className="text-headline-lg text-on-surface">上传中心</h1>
                <p className="mt-xs text-body-md text-on-surface-variant">拖放文件并实时管理上传任务。</p>
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
