import { UploadCloud, Zap } from 'lucide-react';
import { useRef, useState, type DragEvent } from 'react';
import { cn } from '../lib/utils';
import { useAppStore } from '../store';

export function UploadDropZone() {
  const enqueue = useAppStore((s) => s.enqueueFiles);
  const inputRef = useRef<HTMLInputElement>(null);
  const [active, setActive] = useState(false);

  const onDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setActive(false);
    if (e.dataTransfer?.files?.length) {
      enqueue(e.dataTransfer.files);
    }
  };

  return (
    <div
      onClick={() => inputRef.current?.click()}
      onDragOver={(e) => {
        e.preventDefault();
        if (!active) setActive(true);
      }}
      onDragLeave={() => setActive(false)}
      onDrop={onDrop}
      className={cn(
        'group relative flex cursor-pointer flex-col items-center justify-center gap-3 overflow-hidden rounded-2xl border-2 border-dashed p-10 text-center transition-all',
        active
          ? 'border-brand-500 bg-brand-50/60 shadow-inner shadow-brand-600/10'
          : 'border-slate-300 bg-white/60 hover:border-brand-400 hover:bg-brand-50/30'
      )}
    >
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top,theme(colors.brand.50),transparent_60%)] opacity-0 transition-opacity group-hover:opacity-100"
      />
      <div
        className={cn(
          'relative flex h-14 w-14 items-center justify-center rounded-full transition-transform',
          active
            ? 'bg-brand-600 text-white scale-110'
            : 'bg-brand-50 text-brand-600 group-hover:scale-105'
        )}
      >
        <UploadCloud className="h-7 w-7" />
      </div>

      <div className="relative">
        <div className="text-base font-semibold text-slate-900">
          {active ? '松开以上传' : '点击或拖拽文件到此处'}
        </div>
        <div className="mt-1 text-xs text-slate-500">
          支持分片、秒传、断点续传；单文件可达 GB 级别
        </div>
      </div>

      <div className="relative mt-2 flex flex-wrap justify-center gap-2 text-[11px] text-slate-500">
        <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5">
          <Zap className="h-3 w-3 text-amber-500" /> MD5 命中即秒传
        </span>
        <span className="rounded-full bg-slate-100 px-2 py-0.5">自动分片</span>
        <span className="rounded-full bg-slate-100 px-2 py-0.5">并发上传</span>
      </div>

      <input
        ref={inputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => {
          if (e.target.files?.length) enqueue(e.target.files);
          e.target.value = '';
        }}
      />
    </div>
  );
}
