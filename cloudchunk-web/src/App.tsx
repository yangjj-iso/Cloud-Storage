import { lazy, Suspense, useEffect, useState } from 'react';
import { PageBoundary } from './components/PageBoundary';
import { Sidebar } from './components/Sidebar';
const AiHub = lazy(() => import('./components/AiHub').then((module) => ({ default: module.AiHub })));
import { Topbar } from './components/Topbar';
import { QuotaWidget } from './components/QuotaWidget';
import { UploadDropZone } from './components/UploadDropZone';
import { UploadQueue } from './components/UploadQueue';
const FilesView = lazy(() =>
  import('./components/FilesView').then((module) => ({ default: module.FilesView })),
);
import { FileDrawer } from './components/FileDrawer';
const DriveView = lazy(() =>
  import('./components/DriveView').then((module) => ({ default: module.DriveView })),
);
const RecycleBin = lazy(() =>
  import('./components/RecycleBin').then((module) => ({ default: module.RecycleBin })),
);
const ShareList = lazy(() =>
  import('./components/ShareList').then((module) => ({ default: module.ShareList })),
);
const AdminPanel = lazy(() =>
  import('./components/AdminPanel').then((module) => ({ default: module.AdminPanel })),
);
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
    const onUnauthorized = () =>
      useAppStore.setState({ authUser: null, quota: null, files: [], uploads: [], activeFileId: null });
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
        setAiFocusTick((v) => v + 1);
      }
    };
    window.addEventListener('keydown', onShortcut);
    return () => window.removeEventListener('keydown', onShortcut);
  }, [authUser, setView]);

  if (!authChecked) {
    return (
      <div className="flex h-full items-center justify-center bg-background text-body-md text-on-surface-variant">
        <MaterialIcon className="mr-sm animate-pulse text-primary">cloud_sync</MaterialIcon>正在连接
        CloudForge…
      </div>
    );
  }

  if (!authUser)
    return (
      <>
        <LoginPage />
        <Toaster />
      </>
    );

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        跳到内容
      </a>
      <Sidebar />
      <div className="app-body">
        <Topbar
          onSearch={() => {
            setView('ai');
            setAiFocusTick((value) => value + 1);
          }}
        />
        <main id="main-content" className="app-main" tabIndex={-1}>
          <PageBoundary key={view}>
            <Suspense
              fallback={
                <div className="page-fallback" role="status">
                  <MaterialIcon className="animate-spin">progress_activity</MaterialIcon>正在加载…
                </div>
              }
            >
              {view === 'ai' && <AiHub focusTick={aiFocusTick} />}
              {view === 'upload' && (
                <div className="workspace-page upload-page">
                  <div className="page-heading">
                    <div>
                      <span className="eyebrow">TRANSFER</span>
                      <h1>上传中心</h1>
                      <p>把重要的文件，放在一起。</p>
                    </div>
                    <button type="button" className="forge-button" onClick={() => setView('drive')}>
                      <MaterialIcon>folder_open</MaterialIcon>打开云盘
                      <MaterialIcon>arrow_upward</MaterialIcon>
                    </button>
                  </div>
                  <div className="upload-layout">
                    <div className="upload-primary">
                      <UploadDropZone />
                      <UploadQueue />
                    </div>
                    <aside className="upload-aside">
                      <QuotaWidget />
                      <section className="upload-ai-card">
                        <span className="upload-ai-icon">
                          <MaterialIcon>auto_awesome</MaterialIcon>
                        </span>
                        <h2>让文件，成为知识。</h2>
                        <p>从你的文档中搜索、提问。</p>
                        <button type="button" onClick={() => setView('ai')} aria-label="前往 AI 智能工作台">
                          前往 AI 智能工作台 <MaterialIcon>arrow_forward</MaterialIcon>
                        </button>
                      </section>
                    </aside>
                  </div>
                </div>
              )}
              {view === 'files' && <FilesView />}
              {view === 'drive' && <DriveView />}
              {view === 'recycle' && <RecycleBin />}
              {view === 'shares' && <ShareList />}
              {view === 'admin' && <AdminPanel />}
            </Suspense>
          </PageBoundary>
        </main>
      </div>
      <FileDrawer />
      <Toaster />
    </div>
  );
}
