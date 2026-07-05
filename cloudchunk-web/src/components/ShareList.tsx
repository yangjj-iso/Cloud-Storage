import { useEffect } from 'react';
import { useAppStore } from '../store';
import { Button } from './ui/Button';
import { Trash2, Share2, Eye, Save } from 'lucide-react';
import { formatBytes } from '../lib/utils';

export function ShareList() {
  const { shares, sharesLoading, refreshShares, cancelShare } = useAppStore();

  useEffect(() => { refreshShares(); }, [refreshShares]);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-slate-900">我的分享</h2>
        <Button size="sm" variant="ghost" onClick={() => refreshShares()}>刷新</Button>
      </div>

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        {sharesLoading ? (
          <div className="py-12 text-center text-sm text-slate-400">加载中...</div>
        ) : shares.length === 0 ? (
          <div className="py-12 text-center text-sm text-slate-400">
            <Share2 className="mx-auto mb-2 h-8 w-8 text-slate-300" />
            暂无分享
          </div>
        ) : (
          <div className="divide-y divide-slate-50">
            {shares.map((s) => (
              <div key={s.shareId} className="flex items-center gap-3 px-4 py-3 hover:bg-slate-50/50">
                <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-brand-50 text-brand-600">
                  <Share2 className="h-4 w-4" />
                </div>
                <div className="flex-1">
                  <div className="text-sm font-medium text-slate-800">{s.fileName}</div>
                  <div className="flex items-center gap-3 text-xs text-slate-400">
                    <span>{formatBytes(s.fileSize)}</span>
                    <span className="flex items-center gap-0.5"><Eye className="h-3 w-3" /> {s.viewCount}</span>
                    <span className="flex items-center gap-0.5"><Save className="h-3 w-3" /> {s.saveCount}</span>
                    {s.expireAt && (
                      <span className="text-orange-500">
                        到期: {new Date(s.expireAt).toLocaleDateString('zh-CN')}
                      </span>
                    )}
                    <span>创建: {new Date(s.createdAt).toLocaleString('zh-CN')}</span>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    className="rounded-lg px-2 py-1 text-xs text-slate-500 hover:bg-slate-100"
                    onClick={() => {
                      navigator.clipboard?.writeText(`${window.location.origin}/#/s/${s.shareId}`);
                      alert('分享链接已复制');
                    }}
                  >
                    复制链接
                  </button>
                  <button
                    className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-red-500 hover:bg-red-50"
                    onClick={() => {
                      if (confirm('取消分享后链接将失效，确定吗？')) {
                        cancelShare(s.shareId);
                      }
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5" /> 取消分享
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
