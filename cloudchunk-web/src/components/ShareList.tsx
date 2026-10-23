import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { formatZhDate } from '../lib/locale';
import { MaterialIcon } from './ui/MaterialIcon';
import { Dialog } from './ui/Dialog';
import type { ShareItem } from '../types';

export function ShareList() {
  const { shares, sharesLoading, refreshShares, cancelShare, showToast } = useAppStore();
  const [cancel, setCancel] = useState<ShareItem | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    void refreshShares();
  }, [refreshShares]);
  return (
    <div className="workspace-page">
      <header className="page-heading">
        <div>
          <span className="eyebrow">SHARED BY YOU</span>
          <h1>
            我的分享<span className="title-dot">.</span>
          </h1>
          <p>分享重要的文件，也随时掌控访问。</p>
        </div>
        <button className="forge-button" disabled={sharesLoading} onClick={() => void refreshShares()}>
          <MaterialIcon className={sharesLoading ? 'animate-spin' : ''}>refresh</MaterialIcon>刷新
        </button>
      </header>
      <section className="share-list-panel" aria-label="分享链接">
        {sharesLoading ? (
          <div className="drive-empty" role="status">
            正在读取分享…
          </div>
        ) : shares.length === 0 ? (
          <div className="drive-empty">
            <span className="empty-folder">
              <MaterialIcon>share</MaterialIcon>
            </span>
            <h2>还没有分享链接</h2>
            <p>在云盘中选择文件，即可创建分享</p>
          </div>
        ) : (
          shares.map((share) => (
            <article key={share.shareId} className="share-row">
              <span className="file-type-icon purple">
                <MaterialIcon>share</MaterialIcon>
              </span>
              <div className="share-row-name">
                <strong>{share.fileName}</strong>
                <span>
                  {formatBytes(share.fileSize)} · {share.viewCount} 次访问
                </span>
              </div>
              <span className="share-expiry">
                {share.expireAt ? `有效期至 ${formatZhDate(share.expireAt)}` : '永久有效'}
              </span>
              <div className="share-row-actions">
                <button
                  className="stitch-icon-button"
                  aria-label={`复制链接 ${share.fileName}`}
                  title="复制访问地址"
                  onClick={async () => {
                    try {
                      await navigator.clipboard.writeText(`${window.location.origin}/#/s/${share.shareId}`);
                      showToast({
                        kind: 'success',
                        title: '链接已复制',
                        description: '请一并发送创建时显示的提取码。',
                      });
                    } catch {
                      showToast({ kind: 'error', title: '无法复制，请允许剪贴板访问' });
                    }
                  }}
                >
                  <MaterialIcon>link</MaterialIcon>
                </button>
                <button
                  className="stitch-icon-button"
                  title="取消分享"
                  aria-label={`取消分享 ${share.fileName}`}
                  onClick={() => setCancel(share)}
                >
                  <MaterialIcon>link_off</MaterialIcon>
                </button>
              </div>
            </article>
          ))
        )}
      </section>
      {cancel && (
        <Dialog
          title="取消分享"
          onClose={() => {
            if (!busy) setCancel(null);
          }}
        >
          <p className="dialog-note">“{cancel.fileName}”的分享链接将停止访问。</p>
          <div className="dialog-actions">
            <button className="forge-button" disabled={busy} onClick={() => setCancel(null)}>
              保留分享
            </button>
            <button
              className="forge-button danger"
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                try {
                  await cancelShare(cancel.shareId);
                  setCancel(null);
                } finally {
                  setBusy(false);
                }
              }}
            >
              取消分享
            </button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
