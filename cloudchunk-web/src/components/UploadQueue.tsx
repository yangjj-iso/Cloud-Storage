import {
  CheckCircle2,
  FileText,
  Hash,
  Image as ImageIcon,
  Music,
  Package,
  RotateCcw,
  Trash2,
  Video,
  XCircle,
} from 'lucide-react';
import { useAppStore } from '../store';
import { Button } from './ui/Button';
import { Badge } from './ui/Badge';
import { Progress } from './ui/Progress';
import { Empty } from './ui/Empty';
import { cn, formatBytes, formatSpeed, mimeCategory } from '../lib/utils';
import type { UploadTask, UploadTaskStatus } from '../types';

function statusMeta(status: UploadTaskStatus) {
  switch (status) {
    case 'PENDING':
      return { label: '排队中', tone: 'neutral' as const };
    case 'HASHING':
      return { label: '计算哈希', tone: 'info' as const };
    case 'INITIATING':
      return { label: '初始化', tone: 'info' as const };
    case 'UPLOADING':
      return { label: '上传中', tone: 'brand' as const };
    case 'MERGING':
      return { label: '合并中', tone: 'brand' as const };
    case 'DONE':
      return { label: '已完成', tone: 'success' as const };
    case 'FAILED':
      return { label: '失败', tone: 'danger' as const };
    case 'CANCELLED':
      return { label: '已取消', tone: 'neutral' as const };
  }
}

function iconForMime(mime: string | undefined) {
  const cat = mimeCategory(mime);
  const Icon =
    cat === 'image'
      ? ImageIcon
      : cat === 'video'
      ? Video
      : cat === 'audio'
      ? Music
      : cat === 'archive'
      ? Package
      : FileText;
  const color =
    cat === 'image'
      ? 'text-fuchsia-500 bg-fuchsia-50'
      : cat === 'video'
      ? 'text-sky-600 bg-sky-50'
      : cat === 'audio'
      ? 'text-amber-600 bg-amber-50'
      : cat === 'archive'
      ? 'text-orange-600 bg-orange-50'
      : 'text-slate-500 bg-slate-100';
  return (
    <div className={cn('flex h-10 w-10 items-center justify-center rounded-lg', color)}>
      <Icon className="h-5 w-5" />
    </div>
  );
}

function TaskRow({ task }: { task: UploadTask }) {
  const cancel = useAppStore((s) => s.cancelUpload);
  const retry = useAppStore((s) => s.retryUpload);
  const remove = useAppStore((s) => s.removeUpload);
  const meta = statusMeta(task.status);
  const pct =
    task.file.size > 0
      ? Math.min(100, Math.round((task.bytesTransferred / task.file.size) * 100))
      : 0;
  const isActive = ['HASHING', 'INITIATING', 'UPLOADING', 'MERGING'].includes(task.status);
  const variant =
    task.status === 'DONE'
      ? 'success'
      : task.status === 'FAILED'
      ? 'danger'
      : task.status === 'CANCELLED'
      ? 'neutral'
      : 'brand';

  return (
    <div className="group rounded-xl border border-slate-200 bg-white p-4 shadow-sm transition-shadow hover:shadow-md">
      <div className="flex items-start gap-3">
        {iconForMime(task.file.type)}
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-medium text-slate-900" title={task.file.name}>
                {task.file.name}
              </div>
              <div className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-slate-500">
                <span>{formatBytes(task.file.size)}</span>
                {task.chunkTotal > 0 && (
                  <span>
                    分片 {task.uploadedChunks}/{task.chunkTotal}
                  </span>
                )}
                {task.md5 && (
                  <span className="inline-flex items-center gap-1">
                    <Hash className="h-3 w-3" />
                    {task.md5.slice(0, 8)}…
                  </span>
                )}
                {task.status === 'UPLOADING' && task.speed > 0 && (
                  <span>{formatSpeed(task.speed)}</span>
                )}
                {task.mode === 'INSTANT' && (
                  <Badge tone="success" className="!text-[10px]">
                    秒传
                  </Badge>
                )}
                {task.mode === 'RESUME' && (
                  <Badge tone="info" className="!text-[10px]">
                    续传
                  </Badge>
                )}
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-1">
              <Badge tone={meta.tone}>{meta.label}</Badge>
              {isActive && (
                <button
                  onClick={() => cancel(task.id)}
                  className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-rose-500"
                  aria-label="取消"
                >
                  <XCircle className="h-4 w-4" />
                </button>
              )}
              {(task.status === 'FAILED' || task.status === 'CANCELLED') && (
                <button
                  onClick={() => retry(task.id)}
                  className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-brand-600"
                  aria-label="重试"
                >
                  <RotateCcw className="h-4 w-4" />
                </button>
              )}
              {(task.status === 'DONE' ||
                task.status === 'FAILED' ||
                task.status === 'CANCELLED') && (
                <button
                  onClick={() => remove(task.id)}
                  className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-rose-500"
                  aria-label="移除"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              )}
            </div>
          </div>

          <div className="mt-3">
            <Progress
              value={pct}
              variant={variant as 'brand' | 'success' | 'danger' | 'neutral'}
              indeterminate={task.status === 'MERGING' || task.status === 'INITIATING'}
            />
            <div className="mt-1 flex justify-between text-[11px] text-slate-500">
              <span>
                {task.status === 'HASHING'
                  ? '正在计算文件 MD5…'
                  : task.status === 'INITIATING'
                  ? '正在与服务器协商…'
                  : task.status === 'MERGING'
                  ? '服务器正在合并分片…'
                  : task.status === 'DONE'
                  ? '上传完成'
                  : task.status === 'FAILED'
                  ? task.error || '上传失败'
                  : `${formatBytes(task.bytesTransferred)} / ${formatBytes(task.file.size)}`}
              </span>
              <span>{pct}%</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export function UploadQueue() {
  const uploads = useAppStore((s) => s.uploads);
  const clear = useAppStore((s) => s.clearFinished);

  const finishedCount = uploads.filter(
    (t) => t.status === 'DONE' || t.status === 'CANCELLED'
  ).length;

  if (uploads.length === 0) {
    return (
      <Empty
        icon={<CheckCircle2 className="h-10 w-10" />}
        title="暂无上传任务"
        description="拖拽文件到上方区域或点击选择文件开始上传"
      />
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between text-xs text-slate-500">
        <div>
          共 {uploads.length} 个任务 · {finishedCount} 已完成
        </div>
        {finishedCount > 0 && (
          <Button size="sm" variant="ghost" onClick={clear} leftIcon={<Trash2 className="h-3.5 w-3.5" />}>
            清除已完成
          </Button>
        )}
      </div>
      <div className="flex flex-col gap-2">
        {uploads.map((t) => (
          <TaskRow key={t.id} task={t} />
        ))}
      </div>
    </div>
  );
}
