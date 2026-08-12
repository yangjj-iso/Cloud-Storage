import { useEffect } from 'react';
import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { formatZhDate } from '../lib/locale';
import { MaterialIcon } from './ui/MaterialIcon';

export function ShareList() {
  const { shares, sharesLoading, refreshShares, cancelShare, showToast } = useAppStore();
  useEffect(() => { void refreshShares(); }, [refreshShares]);
  return (
    <div className="p-margin-mobile md:p-margin-desktop">
      <div className="mb-xl flex items-start justify-between"><div><h1 className="text-headline-lg">我的分享</h1><p className="mt-xs text-body-md text-on-surface-variant">管理已经创建的文件与文件夹分享链接。</p></div><button type="button" onClick={() => refreshShares()} className="flex h-10 items-center gap-sm rounded border border-outline-variant bg-white px-md text-label-md"><MaterialIcon className={sharesLoading ? 'animate-spin text-[18px]' : 'text-[18px]'}>refresh</MaterialIcon>刷新</button></div>
      <section className="overflow-hidden rounded-xl border border-outline-variant bg-white shadow-sm">
        <div className="grid grid-cols-[2fr_110px_100px_160px_170px] gap-md border-b border-outline-variant bg-surface-container-low px-md py-sm text-label-md tracking-wider text-on-surface-variant"><span>名称</span><span>大小</span><span>访问次数</span><span>有效期</span><span className="text-right">操作</span></div>
        {sharesLoading ? <div className="flex h-32 items-center justify-center text-body-md text-outline"><MaterialIcon className="mr-sm animate-spin">progress_activity</MaterialIcon>正在加载分享…</div> : shares.length === 0 ? <div className="flex h-40 flex-col items-center justify-center text-body-md text-outline"><MaterialIcon className="mb-sm text-[38px] text-primary">share</MaterialIcon>暂无分享链接</div> : shares.map((share) => <div key={share.shareId} className="grid min-h-[64px] grid-cols-[2fr_110px_100px_160px_170px] items-center gap-md border-b border-surface-container px-md text-body-md last:border-0 hover:bg-surface-bright"><span className="flex min-w-0 items-center gap-sm"><span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary-fixed text-primary"><MaterialIcon className="text-[19px]">share</MaterialIcon></span><span className="truncate font-medium">{share.fileName}</span></span><span className="text-on-surface-variant">{formatBytes(share.fileSize)}</span><span className="text-on-surface-variant">{share.viewCount}</span><span className="text-on-surface-variant">{share.expireAt ? formatZhDate(share.expireAt) : '永久有效'}</span><span className="flex justify-end gap-xs"><button type="button" onClick={async () => { await navigator.clipboard.writeText(`${window.location.origin}/#/s/${share.shareId}`); showToast({ kind: 'success', title: '访问地址已复制', description: '请将创建分享时显示的提取码一并发送给接收方。' }); }} className="stitch-icon-button" title="复制访问地址"><MaterialIcon className="text-[18px]">link</MaterialIcon></button><button type="button" onClick={() => { if (confirm('确定取消此分享链接吗？')) cancelShare(share.shareId); }} className="stitch-icon-button text-error hover:bg-error-container" title="取消分享"><MaterialIcon className="text-[18px]">link_off</MaterialIcon></button></span></div>)}
      </section>
    </div>
  );
}
