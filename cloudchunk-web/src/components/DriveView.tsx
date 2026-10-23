import { useEffect, useMemo, useState } from 'react';
import { LayoutGrid, List, Search, X } from 'lucide-react';
import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { api, downloadUrl } from '../lib/api';
import { downloadFile } from '../lib/download';
import { MaterialIcon } from './ui/MaterialIcon';
import { Dialog } from './ui/Dialog';
import { formatZhDate } from '../lib/locale';
import { useDriveBrowser } from '../features/drive/useDriveBrowser';
import { FolderPicker } from '../features/drive/FolderPicker';
import { fileKind, saveBlob } from '../features/drive/filePresentation';
import type { UserFileItem } from '../types';

type Action = { kind: 'mkdir' } | { kind: 'rename' | 'delete' | 'move'; file: UserFileItem } | null;
type Share = { shareId: string; shareCode: string };
const categories = [
  { id: 'document', name: '文档', icon: 'description', color: 'blue' },
  { id: 'image', name: '图片', icon: 'image', color: 'purple' },
  { id: 'video', name: '视频', icon: 'movie', color: 'rose' },
  { id: 'other', name: '其他文件', icon: 'folder_zip', color: 'mint' },
];
export function DriveView() {
  const browser = useDriveBrowser();
  const { files, path, parentId, loading, error, refresh } = browser;
  const setView = useAppStore((s) => s.setView);
  const setActiveFile = useAppStore((s) => s.setActiveFile);
  const showToast = useAppStore((s) => s.showToast);
  const createShare = useAppStore((s) => s.createShare);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('all');
  const [layout, setLayout] = useState<'list' | 'grid'>('list');
  const [sort, setSort] = useState('newest');
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [menuId, setMenuId] = useState<number | null>(null);
  const [action, setAction] = useState<Action>(null);
  const [name, setName] = useState('');
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false);
  const [share, setShare] = useState<Share | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [audits, setAudits] = useState<Awaited<ReturnType<typeof api.driveAudit>>>([]);
  const [historyError, setHistoryError] = useState('');
  const [historyLoading, setHistoryLoading] = useState(false);
  useEffect(() => {
    setSelected(new Set());
    setSearch('');
    setCategory('all');
    setMenuId(null);
  }, [parentId]);
  useEffect(() => {
    if (menuId === null) return;
    const close = (event: MouseEvent) => {
      if (!(event.target as Element).closest('.file-actions')) setMenuId(null);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuId(null);
    };
    document.addEventListener('click', close);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('click', close);
      document.removeEventListener('keydown', escape);
    };
  }, [menuId]);
  const visible = useMemo(
    () =>
      files
        .filter(
          (file) =>
            file.fileName.toLocaleLowerCase().includes(search.toLocaleLowerCase()) &&
            (category === 'all' || fileKind(file.fileName, file.isDir).category === category),
        )
        .sort(
          (a, b) =>
            Number(b.isDir) - Number(a.isDir) ||
            (sort === 'name'
              ? a.fileName.localeCompare(b.fileName, 'zh-CN')
              : sort === 'size'
                ? b.fileSize - a.fileSize
                : (b.updatedAt || b.createdAt).localeCompare(a.updatedAt || a.createdAt)),
        ),
    [files, search, category, sort],
  );
  const chosen = files.filter((file) => selected.has(file.id));
  const toggle = (id: number) =>
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const startAction = (next: Exclude<Action, null>) => {
    setAction(next);
    setName(next.kind === 'rename' ? next.file.fileName : '');
    setActionError('');
    setMenuId(null);
  };
  const finishAction = async () => {
    if (!action || busy || action.kind === 'move') return;
    if (action.kind !== 'delete' && !name.trim()) return;
    setBusy(true);
    setActionError('');
    try {
      if (action.kind === 'mkdir') await api.driveMkdir(parentId, name.trim());
      else if (action.kind === 'rename') await api.driveRename(action.file.id, name.trim());
      else await api.driveDelete(action.file.id);
      showToast({
        kind: 'success',
        title:
          action.kind === 'mkdir' ? '文件夹已创建' : action.kind === 'rename' ? '已重命名' : '已移入回收站',
      });
      setAction(null);
      setSelected(new Set());
      refresh();
      void useAppStore.getState().refreshQuota();
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : '操作失败');
    } finally {
      setBusy(false);
    }
  };
  const zip = async (items: UserFileItem[]) => {
    if (!items.length || busy) return;
    setBusy(true);
    try {
      saveBlob(
        await api.driveDownloadZip(items.map((file) => file.id)),
        `${items.length === 1 ? items[0].fileName : 'CloudForge-文件'}.zip`,
      );
    } catch (reason) {
      showToast({
        kind: 'error',
        title: '打包失败',
        description: reason instanceof Error ? reason.message : '',
      });
    } finally {
      setBusy(false);
    }
  };
  const download = async (file: UserFileItem) => {
    setMenuId(null);
    try {
      await downloadFile({ url: downloadUrl(file.fileId), fileName: file.fileName, fileSize: file.fileSize });
    } catch (reason) {
      showToast({
        kind: 'error',
        title: '下载失败',
        description: reason instanceof Error ? reason.message : '',
      });
    }
  };
  const shareFile = async (file: UserFileItem) => {
    setMenuId(null);
    if (busy) return;
    setBusy(true);
    try {
      const result = await createShare(file.id, 7);
      if (result) setShare(result);
    } finally {
      setBusy(false);
    }
  };
  const loadAudit = async () => {
    setHistoryLoading(true);
    setHistoryError('');
    try {
      setAudits(await api.driveAudit());
    } catch (reason) {
      setHistoryError(reason instanceof Error ? reason.message : '读取失败');
    } finally {
      setHistoryLoading(false);
    }
  };
  return (
    <div className="workspace-page drive-page">
      <header className="page-heading">
        <div>
          <span className="eyebrow">MY WORKSPACE</span>
          <h1>
            我的云盘<span className="title-dot">.</span>
          </h1>
          <p>让每一份文件，都井然有序。</p>
        </div>
        <div className="heading-actions">
          <button className="forge-button" onClick={() => startAction({ kind: 'mkdir' })}>
            <MaterialIcon>create_new_folder</MaterialIcon>新建文件夹
          </button>
          <button className="forge-button primary" onClick={() => setView('upload')}>
            <MaterialIcon>add</MaterialIcon>上传文件
          </button>
        </div>
      </header>
      <section className="drive-categories" aria-label="当前目录分类">
        {categories.map((item) => (
          <button
            key={item.id}
            onClick={() => setCategory(category === item.id ? 'all' : item.id)}
            aria-pressed={category === item.id}
            className={`category-card ${item.color}`}
          >
            <span className="category-icon">
              <MaterialIcon>{item.icon}</MaterialIcon>
            </span>
            <strong>{item.name}</strong>
            <span>
              {loading
                ? '—'
                : files.filter((file) => fileKind(file.fileName, file.isDir).category === item.id).length}
              <small> 个文件</small>
            </span>
            <MaterialIcon>north_east</MaterialIcon>
          </button>
        ))}
      </section>
      <div className="drive-section-heading">
        <nav className="drive-breadcrumb" aria-label="文件夹路径">
          {path.map((crumb, index) => (
            <span key={crumb.id}>
              {index > 0 && <MaterialIcon>chevron_right</MaterialIcon>}
              <button
                onClick={() => browser.backTo(index)}
                aria-current={index === path.length - 1 ? 'location' : undefined}
              >
                {crumb.name}
              </button>
            </span>
          ))}
        </nav>
        <span className="directory-count">当前目录 · {files.length} 项</span>
      </div>
      <section className="drive-panel" aria-label="文件管理">
        <div className="drive-toolbar">
          <label className="drive-search">
            <Search size={16} />
            <input
              aria-label="搜索当前目录"
              placeholder="搜索当前目录…"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            {search && (
              <button type="button" aria-label="清除搜索" onClick={() => setSearch('')}>
                <X size={14} />
              </button>
            )}
          </label>
          <div className="drive-toolbar-actions">
            <select aria-label="排序方式" value={sort} onChange={(event) => setSort(event.target.value)}>
              <option value="newest">最近修改</option>
              <option value="name">名称排序</option>
              <option value="size">文件大小</option>
            </select>
            <button
              className="stitch-icon-button"
              type="button"
              aria-label="刷新目录"
              disabled={loading}
              onClick={refresh}
            >
              <MaterialIcon className={loading ? 'animate-spin' : ''}>refresh</MaterialIcon>
            </button>
            <span className="layout-switch">
              <button
                aria-label="列表视图"
                aria-pressed={layout === 'list'}
                onClick={() => setLayout('list')}
              >
                <List size={17} />
              </button>
              <button
                aria-label="网格视图"
                aria-pressed={layout === 'grid'}
                onClick={() => setLayout('grid')}
              >
                <LayoutGrid size={16} />
              </button>
            </span>
          </div>
        </div>
        {category !== 'all' && (
          <div className="filter-strip">
            <span>{categories.find((item) => item.id === category)?.name}</span>
            <button onClick={() => setCategory('all')} aria-label="清除类型筛选">
              <X size={13} />
            </button>
          </div>
        )}
        {chosen.length > 0 && (
          <div className="selection-toolbar">
            <span>已选择 {chosen.length} 项</span>
            <button disabled={busy} onClick={() => void zip(chosen)}>
              <MaterialIcon>folder_zip</MaterialIcon>
              {busy ? '正在打包…' : '打包下载'}
            </button>
            <button onClick={() => setSelected(new Set())}>取消选择</button>
          </div>
        )}
        {loading ? (
          <div className="drive-skeleton" aria-label="正在加载文件" role="status">
            {[1, 2, 3, 4].map((i) => (
              <div key={i}>
                <span />
                <span />
                <span />
              </div>
            ))}
          </div>
        ) : error ? (
          <div className="drive-empty" role="alert">
            <MaterialIcon>cloud</MaterialIcon>
            <h2>暂时无法读取文件</h2>
            <p>{error}</p>
            <button className="forge-button" onClick={refresh}>
              重试
            </button>
          </div>
        ) : visible.length === 0 ? (
          <div className="drive-empty">
            <span className="empty-folder">
              <MaterialIcon>folder_open</MaterialIcon>
            </span>
            <h2>{search || category !== 'all' ? '没有匹配的文件' : '留一点空间，给新的灵感'}</h2>
            <p>{search || category !== 'all' ? '试试其他名称或文件类型' : '上传文件，开始整理你的云盘'}</p>
            <button
              className="forge-button primary"
              onClick={() => {
                if (search || category !== 'all') {
                  setSearch('');
                  setCategory('all');
                } else setView('upload');
              }}
            >
              {search || category !== 'all' ? '清除筛选' : '上传第一份文件'}
            </button>
          </div>
        ) : (
          <div className={`drive-items ${layout}`}>
            {layout === 'list' && (
              <div className="file-table-heading">
                <label>
                  <input
                    aria-label="全选"
                    type="checkbox"
                    checked={visible.length > 0 && visible.every((file) => selected.has(file.id))}
                    onChange={(event) =>
                      setSelected(event.target.checked ? new Set(visible.map((file) => file.id)) : new Set())
                    }
                  />
                  <span>文件名称</span>
                </label>
                <span>大小</span>
                <span>修改时间</span>
                <span />
              </div>
            )}
            {visible.map((file) => {
              const kind = fileKind(file.fileName, file.isDir);
              return (
                <article key={file.id} className={`drive-file ${selected.has(file.id) ? 'selected' : ''}`}>
                  <div className="file-name-cell">
                    <input
                      aria-label={`选择 ${file.fileName}`}
                      type="checkbox"
                      checked={selected.has(file.id)}
                      onChange={() => toggle(file.id)}
                    />
                    <span className={`file-type-icon ${kind.color}`}>
                      <MaterialIcon>{kind.icon}</MaterialIcon>
                    </span>
                    <button
                      type="button"
                      onClick={() => (file.isDir ? browser.open(file) : setActiveFile(file.fileId))}
                      title={file.fileName}
                    >
                      <strong>{file.fileName}</strong>
                      <small>{kind.label}</small>
                    </button>
                  </div>
                  <span className="file-size">{file.isDir ? '—' : formatBytes(file.fileSize)}</span>
                  <time className="file-date">{formatZhDate(file.updatedAt || file.createdAt, true)}</time>
                  <div className="file-actions">
                    <button
                      type="button"
                      className="stitch-icon-button"
                      aria-label={`操作 ${file.fileName}`}
                      aria-expanded={menuId === file.id}
                      onClick={() => setMenuId(menuId === file.id ? null : file.id)}
                    >
                      <MaterialIcon>more_vert</MaterialIcon>
                    </button>
                    {menuId === file.id && (
                      <div className="file-menu">
                        {!file.isDir && (
                          <MenuItem icon="download" label="下载" onClick={() => void download(file)} />
                        )}
                        <MenuItem
                          icon="folder_zip"
                          label="打包下载"
                          onClick={() => {
                            setMenuId(null);
                            void zip([file]);
                          }}
                        />
                        <MenuItem icon="share" label="分享" onClick={() => void shareFile(file)} />
                        <MenuItem
                          icon="edit"
                          label="重命名"
                          onClick={() => startAction({ kind: 'rename', file })}
                        />
                        <MenuItem
                          icon="drive_file_move"
                          label="移动"
                          onClick={() => startAction({ kind: 'move', file })}
                        />
                        <MenuItem
                          icon="delete"
                          label="移入回收站"
                          danger
                          onClick={() => startAction({ kind: 'delete', file })}
                        />
                      </div>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        )}
        <footer className="drive-panel-footer">
          <span>{loading ? '正在同步…' : `${visible.length} 个项目`}</span>
          <button
            onClick={() => {
              setHistoryOpen(true);
              void loadAudit();
            }}
          >
            <MaterialIcon>history</MaterialIcon>操作记录
          </button>
        </footer>
      </section>
      {action && action.kind !== 'move' && (
        <Dialog
          title={action.kind === 'mkdir' ? '新建文件夹' : action.kind === 'rename' ? '重命名' : '移入回收站'}
          onClose={() => {
            if (!busy) setAction(null);
          }}
        >
          {action.kind === 'delete' ? (
            <p className="dialog-note">“{action.file.fileName}”将移入回收站，可随时还原。</p>
          ) : (
            <form
              id="file-action-form"
              onSubmit={(event) => {
                event.preventDefault();
                void finishAction();
              }}
            >
              <label>
                {action.kind === 'mkdir' ? '文件夹名称' : '文件名称'}
                <input
                  className="forge-input"
                  autoFocus
                  value={name}
                  maxLength={255}
                  onChange={(event) => setName(event.target.value)}
                  required
                />
              </label>
            </form>
          )}
          {actionError && (
            <p role="alert" className="inline-error">
              {actionError}
            </p>
          )}
          <div className="dialog-actions">
            <button className="forge-button" disabled={busy} onClick={() => setAction(null)}>
              取消
            </button>
            <button
              className={`forge-button ${action.kind === 'delete' ? 'danger' : 'primary'}`}
              disabled={busy || (action.kind !== 'delete' && !name.trim())}
              onClick={() => void finishAction()}
            >
              {busy
                ? '请稍候…'
                : action.kind === 'mkdir'
                  ? '创建'
                  : action.kind === 'rename'
                    ? '保存'
                    : '移入回收站'}
            </button>
          </div>
        </Dialog>
      )}
      {action?.kind === 'move' && (
        <FolderPicker
          source={action.file}
          onClose={() => setAction(null)}
          onMoved={() => {
            setAction(null);
            setSelected(new Set());
            refresh();
            showToast({ kind: 'success', title: '已移动' });
          }}
        />
      )}
      {share && (
        <Dialog title="分享已创建" onClose={() => setShare(null)}>
          <p className="dialog-note">有效期 7 天</p>
          <div className="share-copy-box">
            <input
              className="forge-input"
              aria-label="分享链接"
              readOnly
              value={`${window.location.origin}/#/s/${share.shareId}?code=${encodeURIComponent(share.shareCode)}`}
              onFocus={(event) => event.target.select()}
            />
            <span>
              提取码 <strong>{share.shareCode}</strong>
            </span>
          </div>
          <div className="dialog-actions">
            <button
              className="forge-button primary"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(
                    `${window.location.origin}/#/s/${share.shareId}?code=${encodeURIComponent(share.shareCode)}`,
                  );
                  showToast({ kind: 'success', title: '链接已复制' });
                } catch {
                  showToast({ kind: 'info', title: '请选中上方链接复制' });
                }
              }}
            >
              复制链接
            </button>
          </div>
        </Dialog>
      )}
      {historyOpen && (
        <Dialog title="操作记录" onClose={() => setHistoryOpen(false)}>
          {historyLoading ? (
            <p className="dialog-note" role="status">
              正在读取…
            </p>
          ) : historyError ? (
            <p className="inline-error" role="alert">
              {historyError}
            </p>
          ) : audits.length === 0 ? (
            <p className="dialog-note">暂无操作记录</p>
          ) : (
            <div className="drive-audit-list">
              {audits.map((entry, index) => (
                <div key={index}>
                  <span>
                    {(
                      {
                        DRIVE_MKDIR: '新建文件夹',
                        DRIVE_RENAME: '重命名',
                        DRIVE_MOVE: '移动',
                        DRIVE_RECYCLE: '移入回收站',
                        DRIVE_RESTORE: '还原',
                        DRIVE_PURGE: '彻底删除',
                      } as Record<string, string>
                    )[entry.operation] || '文件操作'}
                    <small>{entry.success ? '成功' : '失败'}</small>
                  </span>
                  <time>{formatZhDate(entry.at, true)}</time>
                </div>
              ))}
            </div>
          )}
          <div className="dialog-actions">
            <button className="forge-button" disabled={historyLoading} onClick={() => void loadAudit()}>
              刷新
            </button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
function MenuItem({
  icon,
  label,
  onClick,
  danger = false,
}: {
  icon: string;
  label: string;
  onClick: () => void;
  danger?: boolean;
}) {
  return (
    <button type="button" onClick={onClick} className={danger ? 'danger' : ''}>
      <MaterialIcon>{icon}</MaterialIcon>
      {label}
    </button>
  );
}
