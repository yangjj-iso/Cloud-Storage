import {
  CheckCircle2,
  FileText,
  Hash,
  Image as ImageIcon,
  Music,
  Package,
  Pause,
  Play,
  RotateCcw,
  Trash2,
  Video,
  X,
} from 'lucide-react';
import type { ReactNode } from 'react';
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
    case 'PAUSED':
      return { label: '已暂停', tone: 'warning' as const };
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
      ? 'border-fuchsia-100 bg-fuchsia-50 text-fuchsia-600'
      : cat === 'video'
      ? 'border-sky-100 bg-sky-50 text-sky-600'
      : cat === 'audio'
      ? 'border-amber-100 bg-amber-50 text-amber-600'
      : cat === 'archive'
      ? 'border-orange-100 bg-orange-50 text-orange-600'
      : 'border-slate-200 bg-slate-50 text-slate-500';
  return (
    <div className={cn('flex h-11 w-11 shrink-0 items-center justify-center rounded-lg border', color)}>
      <Icon className="h-5 w-5" />
    </div>
  );
}

function stageText(task: UploadTask, pct: number) {
  if (task.status === 'HASHING') return '正在计算文件指纹';
  if (task.status === 'INITIATING') return '正在创建上传会话';
  if (task.status === 'UPLOADING') return `${formatBytes(task.bytesTransferred)} / ${formatBytes(task.file.size)}`;
  if (task.status === 'MERGING') return '服务器正在合并分片';
  if (task.status === 'PAUSED') return `已暂停在 ${pct}%`;
  if (task.status === 'DONE') return task.mode === 'INSTANT' ? '秒传完成' : '上传完成';
  if (task.status === 'FAILED') return task.error || '上传失败';
  if (task.status === 'CANCELLED') return '已取消上传';
  return '等待开始';
}

function progressVariant(status: UploadTaskStatus): 'brand' | 'success' | 'warning' | 'danger' | 'neutral' {
  if (status === 'DONE') return 'success';
  if (status === 'FAILED') return 'danger';
  if (status === 'PAUSED') return 'warning';
  if (status === 'CANCELLED') return 'neutral';
  return 'brand';
}

function IconButton({
  label,
  onClick,
  children,
  tone = 'neutral',
}: {
  label: string;
  onClick: () => void;
  children: ReactNode;
  tone?: 'neutral' | 'brand' | 'danger' | 'warning';
}) {
  const toneClass =
    tone === 'brand'
      ? 'text-brand-600 hover:bg-brand-50 hover:text-brand-700'
      : tone === 'danger'
      ? 'text-slate-500 hover:bg-rose-50 hover:text-rose-600'
      : tone === 'warning'
      ? 'text-amber-600 hover:bg-amber-50 hover:text-amber-700'
      : 'text-slate-500 hover:bg-slate-100 hover:text-slate-900';

  return (
    <button
      type="button"
      onClick={onClick}
      title={label}
      aria-label={label}
      className={cn(
        'inline-flex h-8 w-8 items-center justify-center rounded-md transition-colors',
        toneClass
      )}
    >
      {children}
    </button>
  );
}

function TaskRow({ task }: { task: UploadTask }) {
  const pause = useAppStore((s) => s.pauseUpload);
  const resume = useAppStore((s) => s.resumeUpload);
  const cancel = useAppStore((s) => s.cancelUpload);
  const retry = useAppStore((s) => s.retryUpload);
  const remove = useAppStore((s) => s.removeUpload);
  const meta = statusMeta(task.status);
  const pct =
    task.file.size > 0
      ? Math.min(100, Math.round((task.bytesTransferred / task.file.size) * 100))
      : 0;
  const isPausable = ['HASHING', 'INITIATING', 'UPLOADING'].includes(task.status);
  const canCancel = ['HASHING', 'INITIATING', 'UPLOADING', 'PAUSED'].includes(task.status);
  const isTerminal = ['DONE', 'FAILED', 'CANCELLED'].includes(task.status);

  return (
    <div
      className={cn(
        'rounded-lg border bg-white p-3 shadow-sm transition-colors',
        task.status === 'FAILED'
          ? 'border-rose-200'
          : task.status === 'PAUSED'
          ? 'border-amber-200 bg-amber-50/20'
          : 'border-slate-200 hover:border-slate-300'
      )}
    >
      <div className="flex items-start gap-3">
        {iconForMime(task.file.type)}

        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold leading-5 text-slate-950" title={task.file.name}>
                {task.file.name}
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500">
                <span>{formatBytes(task.file.size)}</span>
                {task.chunkTotal > 0 && (
                  <span>
                    分片 {task.uploadedChunks}/{task.chunkTotal}
                  </span>
                )}
                {task.md5 && (
                  <span className="inline-flex min-w-0 items-center gap-1 font-mono text-[11px] text-slate-500">
                    <Hash className="h-3.5 w-3.5 text-slate-400" />
                    <span className="max-w-[96px] truncate" title={task.md5}>
                      {task.md5.slice(0, 10)}
                    </span>
                  </span>
                )}
                {task.status === 'UPLOADING' && task.speed > 0 && (
                  <span className="font-medium text-slate-700">{formatSpeed(task.speed)}</span>
                )}
                {task.mode === 'INSTANT' && (
                  <Badge tone="success" className="!px-1.5 !py-0 !text-[10px]">
                    秒传
                  </Badge>
                )}
                {task.mode === 'RESUME' && (
                  <Badge tone="info" className="!px-1.5 !py-0 !text-[10px]">
                    续传
                  </Badge>
                )}
              </div>
            </div>

            <div className="flex shrink-0 items-center gap-2">
              <Badge tone={meta.tone}>{meta.label}</Badge>
              <div className="flex items-center rounded-lg border border-slate-200 bg-slate-50/80 p-0.5">
                {isPausable && (
                  <IconButton label="暂停上传" onClick={() => pause(task.id)} tone="warning">
                    <Pause className="h-4 w-4" />
                  </IconButton>
                )}
                {task.status === 'PAUSED' && (
                  <IconButton label="继续上传" onClick={() => resume(task.id)} tone="brand">
                    <Play className="h-4 w-4" />
                  </IconButton>
                )}
                {(task.status === 'FAILED' || task.status === 'CANCELLED') && (
                  <IconButton label="重试上传" onClick={() => retry(task.id)} tone="brand">
                    <RotateCcw className="h-4 w-4" />
                  </IconButton>
                )}
                {canCancel && (
                  <IconButton label="取消上传" onClick={() => cancel(task.id)} tone="danger">
                    <X className="h-4 w-4" />
                  </IconButton>
                )}
                {isTerminal && (
                  <IconButton label="移除任务" onClick={() => remove(task.id)} tone="danger">
                    <Trash2 className="h-4 w-4" />
                  </IconButton>
                )}
              </div>
            </div>
          </div>

          <div className="mt-3">
            <div className="mb-1.5 flex items-center justify-between gap-3 text-xs">
              <span
                className={cn(
                  'min-w-0 truncate',
                  task.status === 'FAILED' ? 'text-rose-600' : 'text-slate-500'
                )}
              >
                {stageText(task, pct)}
              </span>
              <span className="shrink-0 font-medium tabular-nums text-slate-600">{pct}%</span>
            </div>
            <Progress
              value={pct}
              variant={progressVariant(task.status)}
              indeterminate={task.status === 'MERGING' || task.status === 'INITIATING'}
              className="h-2"
            />
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
  const activeCount = uploads.filter((t) =>
    ['HASHING', 'INITIATING', 'UPLOADING', 'MERGING'].includes(t.status)
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
          共 {uploads.length} 个任务 · {activeCount} 进行中 · {finishedCount} 已完成
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
