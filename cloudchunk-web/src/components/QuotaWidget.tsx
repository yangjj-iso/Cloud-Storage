import { HardDrive, TrendingUp } from 'lucide-react';
import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { Progress } from './ui/Progress';

export function QuotaWidget() {
  const quota = useAppStore((s) => s.quota);
  if (!quota) {
    return (
      <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="h-4 w-24 animate-pulse rounded bg-slate-100" />
        <div className="mt-4 h-2 animate-pulse rounded bg-slate-100" />
      </div>
    );
  }

  const pct = quota.totalBytes > 0 ? (quota.usedBytes / quota.totalBytes) * 100 : 0;
  const tone = pct > 85 ? 'danger' : pct > 60 ? 'brand' : 'brand';

  return (
    <div className="relative overflow-hidden rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <div
        aria-hidden
        className="absolute -right-8 -top-8 h-32 w-32 rounded-full bg-brand-50 blur-2xl"
      />
      <div className="relative flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-medium text-slate-600">
          <HardDrive className="h-4 w-4 text-brand-600" />
          存储空间
        </div>
        <span className="inline-flex items-center gap-1 text-xs font-medium text-slate-500">
          <TrendingUp className="h-3 w-3" />
          {quota.fileCount} 个文件
        </span>
      </div>

      <div className="relative mt-3 flex items-baseline gap-2">
        <div className="text-2xl font-semibold tracking-tight text-slate-900">
          {formatBytes(quota.usedBytes)}
        </div>
        <div className="text-xs text-slate-400">/ {formatBytes(quota.totalBytes)}</div>
      </div>

      <div className="relative mt-3">
        <Progress value={pct} variant={tone} />
      </div>
      <div className="relative mt-1.5 text-[11px] text-slate-500">
        已用 {pct.toFixed(1)}%
      </div>
    </div>
  );
}
