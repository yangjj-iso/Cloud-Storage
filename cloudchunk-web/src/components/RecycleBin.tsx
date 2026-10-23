import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { MaterialIcon } from './ui/MaterialIcon';
import { formatZhDate } from '../lib/locale';
import { Dialog } from './ui/Dialog';

export function RecycleBin() {
  const { recycleFiles, recycleLoading, refreshRecycle, recycleRestore, recycleHardDelete } = useAppStore();
  const [purge, setPurge] = useState<number[] | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    void refreshRecycle();
  }, [refreshRecycle]);
  return (
    <div className="workspace-page">
      <header className="page-heading">
        <div>
          <span className="eyebrow">RECENTLY DELETED</span>
          <h1>
            回收站<span className="title-dot">.</span>
          </h1>
          <p>需要的文件，还可以找回来。</p>
        </div>
        <div className="heading-actions">
          <button className="forge-button" disabled={recycleLoading} onClick={() => void refreshRecycle()}>
            <MaterialIcon className={recycleLoading ? 'animate-spin' : ''}>refresh</MaterialIcon>刷新
          </button>
          <button
            className="forge-button danger"
            disabled={!recycleFiles.length || busy}
            onClick={() => setPurge(recycleFiles.map((file) => file.id))}
          >
            <MaterialIcon>delete_sweep</MaterialIcon>清空回收站
          </button>
        </div>
      </header>
      <section className="share-list-panel" aria-label="已删除文件">
        {recycleLoading ? (
          <div className="drive-empty" role="status">
            正在读取回收站…
          </div>
        ) : recycleFiles.length === 0 ? (
          <div className="drive-empty">
            <span className="empty-folder">
              <MaterialIcon>delete_outline</MaterialIcon>
            </span>
            <h2>回收站是空的</h2>
          </div>
        ) : (
          recycleFiles.map((file) => (
            <article key={file.id} className="share-row">
              <span className="file-type-icon purple">
                <MaterialIcon>{file.isDir ? 'folder' : 'draft'}</MaterialIcon>
              </span>
              <div className="share-row-name">
                <strong>{file.fileName}</strong>
                <span>{file.isDir ? '文件夹' : formatBytes(file.fileSize)}</span>
              </div>
              <time className="share-expiry">{formatZhDate(file.updatedAt || file.createdAt, true)}</time>
              <div className="share-row-actions">
                <button
                  className="stitch-icon-button"
                  disabled={busy}
                  title="还原"
                  aria-label={`还原 ${file.fileName}`}
                  onClick={async () => {
                    setBusy(true);
                    try {
                      await recycleRestore(file.id);
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  <MaterialIcon>restore</MaterialIcon>
                </button>
                <button
                  className="stitch-icon-button"
                  disabled={busy}
                  title="永久删除"
                  aria-label={`永久删除 ${file.fileName}`}
                  onClick={() => setPurge([file.id])}
                >
                  <MaterialIcon>delete_forever</MaterialIcon>
                </button>
              </div>
            </article>
          ))
        )}
      </section>
      {purge && (
        <Dialog
          title="永久删除"
          onClose={() => {
            if (!busy) setPurge(null);
          }}
        >
          <p className="dialog-note">将永久删除 {purge.length} 个项目，此操作无法撤销。</p>
          <div className="dialog-actions">
            <button className="forge-button" disabled={busy} onClick={() => setPurge(null)}>
              取消
            </button>
            <button
              className="forge-button danger"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  for (const id of purge) await recycleHardDelete(id);
                  setPurge(null);
                } finally {
                  setBusy(false);
                }
              }}
            >
              {busy ? '删除中…' : '永久删除'}
            </button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
