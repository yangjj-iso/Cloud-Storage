import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import type { UserFileItem } from '../../types';
import { MaterialIcon } from '../../components/ui/MaterialIcon';
import { Dialog } from '../../components/ui/Dialog';

type Crumb = { id: number; name: string };
export function FolderPicker({
  source,
  onClose,
  onMoved,
}: {
  source: UserFileItem;
  onClose: () => void;
  onMoved: () => void;
}) {
  const [path, setPath] = useState<Crumb[]>([{ id: 0, name: '我的云盘' }]);
  const [folders, setFolders] = useState<UserFileItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const parentId = path[path.length - 1].id;
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    api
      .driveList(parentId)
      .then((items) => {
        if (active) setFolders(items.filter((item) => item.isDir && item.id !== source.id));
      })
      .catch((reason) => {
        if (active) setError(reason instanceof Error ? reason.message : '读取文件夹失败');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [parentId, source.id, revision]);
  const move = async () => {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      await api.driveMove(source.id, parentId);
      onMoved();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '移动失败');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog
      title="移动文件"
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <p className="dialog-note">{source.fileName}</p>
      <nav aria-label="目标文件夹路径" className="drive-breadcrumb picker-breadcrumb">
        {path.map((crumb, index) => (
          <span key={crumb.id}>
            {index > 0 && <MaterialIcon>chevron_right</MaterialIcon>}
            <button disabled={busy} onClick={() => setPath(path.slice(0, index + 1))}>
              {crumb.name}
            </button>
          </span>
        ))}
      </nav>
      <div className="folder-picker-list" aria-busy={loading}>
        {loading ? (
          <p role="status">正在读取…</p>
        ) : error ? (
          <p role="alert">
            {error}{' '}
            <button type="button" onClick={() => setRevision((v) => v + 1)}>
              重试
            </button>
          </p>
        ) : folders.length === 0 ? (
          <p>没有子文件夹</p>
        ) : (
          folders.map((folder) => (
            <button
              type="button"
              disabled={busy}
              key={folder.id}
              onClick={() => setPath([...path, { id: folder.id, name: folder.fileName }])}
            >
              <MaterialIcon>folder</MaterialIcon>
              <span>{folder.fileName}</span>
              <MaterialIcon>chevron_right</MaterialIcon>
            </button>
          ))
        )}
      </div>
      <div className="dialog-actions">
        <button type="button" className="forge-button" disabled={busy} onClick={onClose}>
          取消
        </button>
        <button
          type="button"
          className="forge-button primary"
          disabled={busy || loading || Boolean(error) || parentId === source.parentId}
          onClick={() => void move()}
        >
          {busy ? '移动中…' : '移动到此处'}
        </button>
      </div>
    </Dialog>
  );
}
