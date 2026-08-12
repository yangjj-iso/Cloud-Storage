import { useRef, useState, type DragEvent } from 'react';
import { cn } from '../lib/utils';
import { useAppStore } from '../store';
import { MaterialIcon } from './ui/MaterialIcon';

export function UploadDropZone() {
  const enqueue = useAppStore((s) => s.enqueueFiles);
  const inputRef = useRef<HTMLInputElement>(null);
  const [active, setActive] = useState(false);

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setActive(false);
    if (event.dataTransfer.files.length) enqueue(event.dataTransfer.files);
  };

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => inputRef.current?.click()}
      onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') inputRef.current?.click(); }}
      onDragOver={(event) => { event.preventDefault(); setActive(true); }}
      onDragLeave={() => setActive(false)}
      onDrop={onDrop}
      className={cn(
        'group relative flex min-h-[280px] cursor-pointer flex-col items-center justify-center overflow-hidden rounded-xl border-2 border-dashed p-8 text-center transition-all duration-300',
        active ? 'border-primary bg-surface-container-low' : 'border-outline-variant bg-surface-container-lowest hover:border-primary hover:bg-surface-container-low'
      )}
    >
      <div className="absolute inset-0 bg-gradient-to-br from-primary-fixed/20 to-transparent opacity-0 transition-opacity duration-500 group-hover:opacity-100" />
      <span className="z-10 mb-lg flex h-20 w-20 items-center justify-center rounded-full bg-surface-container transition-all duration-300 group-hover:scale-110 group-hover:bg-primary-fixed">
        <MaterialIcon className="text-4xl text-primary">cloud_upload</MaterialIcon>
      </span>
      <h2 className="z-10 mb-sm text-headline-md text-on-background">{active ? '松开鼠标即可上传' : '将文件拖放到这里'}</h2>
      <p className="z-10 mb-lg text-body-md text-on-surface-variant">也可以点击从电脑中选择文件</p>
      <span className="z-10 rounded-full bg-primary px-lg py-2.5 text-label-md text-on-primary transition-colors group-hover:bg-primary-container">选择文件</span>
      <div className="z-10 mt-xl flex flex-wrap justify-center gap-sm">
        <Capability icon="verified">MD5 完整性校验</Capability>
        <Capability icon="dynamic_feed">自动分片</Capability>
        <Capability icon="fast_forward">并发上传</Capability>
        <Capability icon="play_pause">断点续传</Capability>
      </div>
      <input ref={inputRef} className="hidden" type="file" multiple onChange={(event) => { if (event.target.files?.length) enqueue(event.target.files); event.target.value = ''; }} />
    </div>
  );
}

function Capability({ icon, children }: { icon: string; children: string }) {
  return <span className="inline-flex items-center rounded-md border border-outline-variant/30 bg-surface-variant px-2.5 py-1 text-xs font-medium text-on-surface-variant"><MaterialIcon className="mr-xs text-[14px]">{icon}</MaterialIcon>{children}</span>;
}
