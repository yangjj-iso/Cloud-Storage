import { useEffect } from 'react';
import { useAppStore } from '../store';
import { Button } from './ui/Button';
import { RotateCcw, Trash2, Folder, File as FileIcon } from 'lucide-react';
import { formatBytes } from '../lib/utils';

export function RecycleBin() {
  const { recycleFiles, recycleLoading, refreshRecycle, recycleRestore, recycleHardDelete } = useAppStore();

  useEffect(() => { refreshRecycle(); }, [refreshRecycle]);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-slate-900">回收站</h2>
        <Button size="sm" variant="ghost" onClick={() => refreshRecycle()}>刷新</Button>
      </div>

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        {recycleLoading ? (
          <div className="py-12 text-center text-sm text-slate-400">加载中...</div>
        ) : recycleFiles.length === 0 ? (
          <div className="py-12 text-center text-sm text-slate-400">回收站为空</div>
        ) : (
          <div className="divide-y divide-slate-50">
            {recycleFiles.map((f) => (
              <div key={f.id} className="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50/50">
                {f.isDir ? (
                  <Folder className="h-5 w-5 shrink-0 text-amber-500" />
                ) : (
                  <FileIcon className="h-5 w-5 shrink-0 text-slate-400" />
                )}
                <div className="flex-1">
                  <div className="text-sm font-medium text-slate-700">{f.fileName}</div>
                  <div className="text-xs text-slate-400">
                    {!f.isDir && `${formatBytes(f.fileSize)} · `}
                    删除于 {new Date(f.createdAt).toLocaleString('zh-CN')}
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-green-600 hover:bg-green-50"
                    title="还原"
                    onClick={() => recycleRestore(f.id)}
                  >
                    <RotateCcw className="h-3.5 w-3.5" /> 还原
                  </button>
                  <button
                    className="flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-red-500 hover:bg-red-50"
                    title="彻底删除"
                    onClick={() => {
                      if (confirm(`彻底删除 "${f.fileName}"？此操作不可恢复！`)) {
                        recycleHardDelete(f.id);
                      }
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5" /> 彻底删除
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
