import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { MaterialIcon } from './ui/MaterialIcon';

export function QuotaWidget() {
  const quota = useAppStore((s) => s.quota);
  const sharesTotal = useAppStore((s) => s.sharesTotal);

  if (!quota) {
    return <section className="h-[232px] animate-pulse rounded-xl border border-outline-variant bg-surface-container-lowest p-lg"><div className="h-6 w-32 rounded bg-surface-container" /><div className="mt-lg h-10 w-48 rounded bg-surface-container" /></section>;
  }

  const percentage = quota.totalBytes > 0 ? Math.min(100, (quota.usedBytes / quota.totalBytes) * 100) : 0;
  const remaining = Math.max(0, quota.totalBytes - quota.usedBytes);
  const used = formatBytes(quota.usedBytes).split(' ');
  const totalLabel = formatBytes(quota.totalBytes);
  const remainingLabel = formatBytes(remaining);

  return (
    <section className="rounded-xl border border-outline-variant bg-surface-container-lowest p-lg shadow-sm">
      <div className="mb-md flex items-center justify-between">
        <h2 className="text-[18px] font-semibold leading-7 text-on-background">存储配额</h2>
        <MaterialIcon className="text-outline">data_usage</MaterialIcon>
      </div>
      <div className="mb-sm flex items-end gap-xs">
        <span className="text-[30px] font-bold leading-none text-primary">{used[0]}</span>
        <span className="pb-0.5 text-body-md text-on-surface-variant">{used[1] || ''} 已使用</span>
        <span className="px-xs pb-0.5 text-body-md text-outline">/</span>
        <span className="pb-0.5 text-body-md text-on-surface-variant">{totalLabel}</span>
      </div>
      <div className="mt-md">
        <div className="mb-1.5 flex justify-between text-label-sm"><span className="font-medium text-on-background">已使用 {percentage.toFixed(0)}%</span><span className="text-outline">剩余 {remainingLabel}</span></div>
        <div className="h-2 w-full rounded-full bg-surface-variant"><div className="h-2 rounded-full bg-primary" style={{ width: `${percentage}%` }} /></div>
      </div>
      <div className="mt-lg grid grid-cols-2 gap-md border-t border-outline-variant pt-md text-center">
        <div><p className="mb-xs text-label-sm text-outline">文件总数</p><p className="text-headline-md text-on-background">{quota.fileCount.toLocaleString()}</p></div>
        <div><p className="mb-xs text-label-sm text-outline">分享数量</p><p className="text-headline-md text-on-background">{sharesTotal.toLocaleString()}</p></div>
      </div>
    </section>
  );
}
