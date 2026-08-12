import { useAppStore } from '../store';
import type { UploadTask } from '../types';
import { cn, formatBytes, formatSpeed, mimeCategory } from '../lib/utils';
import { MaterialIcon } from './ui/MaterialIcon';
import { uploadStatusLabels } from '../lib/locale';

export function UploadQueue() {
  const uploads = useAppStore((s) => s.uploads);
  const clearFinished = useAppStore((s) => s.clearFinished);
  return (
    <section className="overflow-hidden rounded-xl border border-outline-variant bg-surface-container-lowest shadow-sm">
      <div className="flex h-[60px] items-center justify-between border-b border-outline-variant bg-surface-bright px-lg">
        <h2 className="text-[18px] font-semibold text-on-background">上传任务</h2>
        <button type="button" onClick={clearFinished} className="rounded px-sm py-xs text-body-md font-medium text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary">清除已完成</button>
      </div>
      {uploads.length === 0 ? (
        <div className="flex min-h-[116px] items-center justify-center gap-sm px-md text-body-md text-outline"><MaterialIcon>cloud_queue</MaterialIcon>暂无上传任务</div>
      ) : (
        <div className="divide-y divide-outline-variant">{uploads.map((task) => <TaskRow key={task.id} task={task} />)}</div>
      )}
    </section>
  );
}

function TaskRow({ task }: { task: UploadTask }) {
  const pause = useAppStore((s) => s.pauseUpload);
  const resume = useAppStore((s) => s.resumeUpload);
  const cancel = useAppStore((s) => s.cancelUpload);
  const retry = useAppStore((s) => s.retryUpload);
  const remove = useAppStore((s) => s.removeUpload);
  const percentage = task.file.size > 0 ? Math.min(100, Math.round((task.bytesTransferred / task.file.size) * 100)) : 0;
  const category = mimeCategory(task.file.type);
  const appearance = iconAppearance(category, task.file.name);
  const isWorking = ['HASHING', 'INITIATING', 'UPLOADING', 'MERGING'].includes(task.status);
  const isTerminal = ['DONE', 'FAILED', 'CANCELLED'].includes(task.status);
  const detail = task.status === 'UPLOADING'
    ? `${formatBytes(task.bytesTransferred)} / ${formatBytes(task.file.size)}${task.speed > 0 ? `  •  ${formatSpeed(task.speed)}` : ''}`
    : task.status === 'HASHING' ? formatBytes(task.file.size)
    : task.status === 'DONE' ? `${formatBytes(task.file.size)}  •  MD5 校验通过`
    : task.status === 'FAILED' ? task.error || formatBytes(task.file.size)
    : `${formatBytes(task.bytesTransferred)} / ${formatBytes(task.file.size)}`;

  return (
    <div className={cn('p-md transition-colors hover:bg-surface-bright', task.status === 'PAUSED' && 'bg-surface-container-low/50')}>
      <div className="mb-sm flex items-center justify-between">
        <div className={cn('flex min-w-0 items-center gap-3', task.status === 'PAUSED' && 'opacity-75')}>
          <span className={cn('flex h-10 w-10 shrink-0 items-center justify-center rounded', appearance.color)}><MaterialIcon>{appearance.icon}</MaterialIcon></span>
          <div className="min-w-0">
            <p className="truncate text-label-md text-on-background" title={task.file.name}>{task.file.name}</p>
            <div className="mt-0.5 flex items-center gap-sm text-label-sm">
              <span className={cn('inline-flex items-center', task.status === 'DONE' ? 'text-emerald-700' : task.status === 'FAILED' ? 'text-error' : task.status === 'UPLOADING' ? 'text-primary' : 'text-secondary')}>
                {(task.status === 'HASHING' || task.status === 'INITIATING' || task.status === 'MERGING') && <MaterialIcon className="mr-xs animate-spin text-[12px]">sync</MaterialIcon>}
                {task.status === 'DONE' && <MaterialIcon className="mr-xs text-[14px]">check_circle</MaterialIcon>}
                {uploadStatusLabels[task.status]}
              </span>
              <span className="text-outline">•</span><span className="truncate text-on-surface-variant">{detail}</span>
            </div>
          </div>
        </div>
        <div className="ml-md flex shrink-0 items-center gap-xs">
          {isWorking && task.status !== 'MERGING' && <Action title="暂停" icon="pause" onClick={() => pause(task.id)} />}
          {task.status === 'PAUSED' && <Action title="继续上传" icon="play_arrow" onClick={() => resume(task.id)} />}
          {task.status === 'FAILED' && <Action title="重试" icon="refresh" onClick={() => retry(task.id)} />}
          {!isTerminal && <Action title="取消" icon="close" onClick={() => cancel(task.id)} danger />}
          {isTerminal && <Action title="移除任务" icon="close" onClick={() => remove(task.id)} danger />}
        </div>
      </div>
      {task.status !== 'DONE' && (
        <div className={cn('mt-3 flex items-center gap-3', task.status === 'PAUSED' && 'opacity-75')}>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-surface-variant">
            <div className={cn('h-full rounded-full', task.status === 'FAILED' ? 'bg-error' : task.status === 'PAUSED' ? 'bg-outline' : task.status === 'HASHING' ? 'w-full animate-pulse bg-secondary' : 'bg-primary')} style={task.status === 'HASHING' ? undefined : { width: `${percentage}%` }} />
          </div>
          <span className="min-w-[32px] text-right text-label-sm font-medium text-on-background">{task.status === 'HASHING' ? '--' : `${percentage}%`}</span>
        </div>
      )}
    </div>
  );
}

function Action({ title, icon, onClick, danger = false }: { title: string; icon: string; onClick: () => void; danger?: boolean }) {
  return <button type="button" title={title} aria-label={title} onClick={onClick} className={cn('rounded-md p-1.5 text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary', danger && 'hover:bg-error-container hover:text-error')}><MaterialIcon className="text-[20px]">{icon}</MaterialIcon></button>;
}

function iconAppearance(category: ReturnType<typeof mimeCategory>, name: string) {
  if (name.toLowerCase().endsWith('.pdf')) return { icon: 'picture_as_pdf', color: 'bg-error-container text-on-error-container' };
  if (category === 'image') return { icon: 'image', color: 'bg-sky-100 text-sky-700' };
  if (category === 'video') return { icon: 'movie', color: 'bg-primary-fixed text-primary' };
  if (category === 'audio') return { icon: 'audio_file', color: 'bg-amber-100 text-amber-700' };
  if (category === 'archive') return { icon: 'folder_zip', color: 'bg-orange-100 text-orange-700' };
  if (category === 'doc') return { icon: 'description', color: 'bg-blue-100 text-blue-700' };
  return { icon: 'draft', color: 'bg-error-container text-on-error-container' };
}
