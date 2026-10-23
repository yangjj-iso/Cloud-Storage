import { useAppStore } from '../store';
import { navigation } from '../lib/navigation';
import { formatBytes } from '../lib/utils';
import { MaterialIcon } from './ui/MaterialIcon';
import { Brand } from './ui/Brand';

export function Sidebar() {
  const view = useAppStore((s) => s.view);
  const setView = useAppStore((s) => s.setView);
  const user = useAppStore((s) => s.authUser);
  const quota = useAppStore((s) => s.quota);
  const uploads = useAppStore((s) => s.uploads);
  const active = uploads.filter((item) =>
    ['HASHING', 'INITIATING', 'UPLOADING', 'MERGING'].includes(item.status),
  ).length;
  const percent =
    quota && quota.totalBytes > 0 ? Math.min(100, (quota.usedBytes / quota.totalBytes) * 100) : 0;
  return (
    <aside className="app-sidebar">
      <button
        type="button"
        className="sidebar-brand"
        onClick={() => setView('drive')}
        aria-label="CloudForge 首页"
      >
        <Brand />
      </button>
      <button type="button" className="sidebar-upload" onClick={() => setView('upload')}>
        <MaterialIcon>add</MaterialIcon>上传文件<MaterialIcon>arrow_upward</MaterialIcon>
      </button>
      <span className="sidebar-label">工作空间</span>
      <nav className="sidebar-nav" aria-label="主导航">
        {navigation.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => setView(item.id)}
            aria-current={view === item.id ? 'page' : undefined}
          >
            <MaterialIcon>{item.icon}</MaterialIcon>
            <span>{item.label}</span>
            {item.id === 'upload' && active > 0 && <small>{active}</small>}
            {item.id === 'ai' && <small className="ai-pill">AI</small>}
          </button>
        ))}
      </nav>
      <div className="sidebar-bottom">
        {user?.role === 'admin' && (
          <button
            type="button"
            className="sidebar-admin"
            onClick={() => setView('admin')}
            aria-current={view === 'admin' ? 'page' : undefined}
          >
            <MaterialIcon>admin_panel_settings</MaterialIcon>管理后台
          </button>
        )}
        <section className="sidebar-storage" aria-label="存储空间">
          <div>
            <MaterialIcon>cloud</MaterialIcon>
            <strong>存储空间</strong>
            <span>{quota ? `${percent.toFixed(0)}%` : '—'}</span>
          </div>
          <div
            className="storage-track"
            role="progressbar"
            aria-label="已用存储"
            aria-valuenow={Math.round(percent)}
            aria-valuemin={0}
            aria-valuemax={100}
          >
            <span style={{ width: `${percent}%` }} />
          </div>
          <p>{quota ? `${formatBytes(quota.usedBytes)} / ${formatBytes(quota.totalBytes)}` : '正在读取…'}</p>
        </section>
        <span className="sidebar-footer">A little space. Endless possibilities.</span>
      </div>
    </aside>
  );
}
