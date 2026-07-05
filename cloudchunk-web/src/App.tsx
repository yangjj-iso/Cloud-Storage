import { useEffect } from 'react';
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
import { useAppStore } from './store';
import { LoginPage } from './components/LoginPage';

export default function App() {
  const authChecked = useAppStore((s) => s.authChecked);
  const authUser = useAppStore((s) => s.authUser);
  const initAuth = useAppStore((s) => s.initAuth);
  const view = useAppStore((s) => s.view);
  const refreshQuota = useAppStore((s) => s.refreshQuota);
  const refreshFiles = useAppStore((s) => s.refreshFiles);

  useEffect(() => {
    initAuth();
  }, [initAuth]);

  useEffect(() => {
    if (!authUser) return;
    refreshQuota();
    refreshFiles();
    const t = setInterval(refreshQuota, 30000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authUser]);

  if (!authChecked) {
    return (
      <div className="flex h-full items-center justify-center bg-slate-50 text-sm text-slate-500">
        加载中...
      </div>
    );
  }

  if (!authUser) {
    return (
      <>
        <LoginPage />
        <Toaster />
      </>
    );
  }

  return (
    <div className="flex h-full min-h-0 bg-gradient-to-br from-slate-50 via-white to-brand-50/30">
      <Sidebar />
      <main className="flex min-w-0 flex-1 flex-col">
        <Topbar />
        <div className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-6xl px-4 py-6 sm:px-6 lg:px-8">
            {view === 'upload' && (
              <div className="grid gap-5 lg:grid-cols-[1fr_280px]">
                <div className="flex flex-col gap-5">
                  <UploadDropZone />
                  <UploadQueue />
                </div>
                <aside className="order-first flex flex-col gap-4 lg:order-last">
                  <QuotaWidget />
                  <HelpCard />
                </aside>
              </div>
            )}
            {view === 'files' && <FilesView />}
            {view === 'drive' && <DriveView />}
            {view === 'recycle' && <RecycleBin />}
            {view === 'shares' && <ShareList />}
            {view === 'admin' && <AdminPanel />}
          </div>
        </div>
      </main>
      <FileDrawer />
      <Toaster />
    </div>
  );
}

function HelpCard() {
  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-5 text-sm shadow-sm">
      <div className="mb-2 text-sm font-semibold text-slate-800">使用技巧</div>
      <ul className="flex flex-col gap-2 text-xs text-slate-600">
        <li className="flex gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
          文件 MD5 与已有内容匹配时自动走秒传通道。
        </li>
        <li className="flex gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
          大文件自动分片并发上传，网络异常可重试续传。
        </li>
        <li className="flex gap-2">
          <span className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-500" />
          图片 / 视频 / 文档将在后台异步转码生成缩略图或摘要。
        </li>
      </ul>
    </div>
  );
}
