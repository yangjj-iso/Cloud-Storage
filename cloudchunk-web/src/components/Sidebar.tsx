import { CloudUpload, FolderOpen, Github, Package2 } from 'lucide-react';
import { useAppStore, type View } from '../store';
import { cn } from '../lib/utils';

const items: Array<{ id: View; label: string; icon: typeof CloudUpload }> = [
  { id: 'upload', label: '上传中心', icon: CloudUpload },
  { id: 'files', label: '我的文件', icon: FolderOpen },
];

export function Sidebar() {
  const view = useAppStore((s) => s.view);
  const setView = useAppStore((s) => s.setView);
  const quota = useAppStore((s) => s.quota);

  return (
    <aside className="hidden w-60 shrink-0 flex-col border-r border-slate-200 bg-white/80 px-4 py-5 backdrop-blur md:flex">
      <div className="mb-8 flex items-center gap-2 px-2">
        <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-brand-500 to-brand-700 text-white shadow-sm shadow-brand-600/40">
          <Package2 className="h-5 w-5" />
        </div>
        <div>
          <div className="text-base font-semibold tracking-tight">CloudChunk</div>
          <div className="text-[10px] uppercase tracking-wider text-slate-400">
            分布式切片存储
          </div>
        </div>
      </div>

      <nav className="flex flex-col gap-1">
        {items.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setView(id)}
            className={cn(
              'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
              view === id
                ? 'bg-brand-50 text-brand-700'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
            {id === 'files' && quota && (
              <span className="ml-auto rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-500">
                {quota.fileCount}
              </span>
            )}
          </button>
        ))}
      </nav>

      <div className="mt-auto px-2 text-[11px] text-slate-400">
        <a
          href="https://github.com/"
          target="_blank"
          rel="noreferrer"
          className="inline-flex items-center gap-1 hover:text-slate-600"
        >
          <Github className="h-3 w-3" /> v0.1.0
        </a>
      </div>
    </aside>
  );
}
