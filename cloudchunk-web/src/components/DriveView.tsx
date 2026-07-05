import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { Button } from './ui/Button';
import { Folder, File as FileIcon, ChevronLeft, FolderPlus, Trash2, Edit3, Share2, Download, Eye } from 'lucide-react';
import { formatBytes } from '../lib/utils';
import { downloadUrl } from '../lib/api';
import { downloadFile } from '../lib/download';

export function DriveView() {
  const {
    driveFiles, driveParentId, driveLoading,
    refreshDrive, driveMkdir, driveRename, driveDelete, createShare,
    showToast,
  } = useAppStore();
  const [showMkdir, setShowMkdir] = useState(false);
  const [newDirName, setNewDirName] = useState('');
  const [renameId, setRenameId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [shareResult, setShareResult] = useState<{ shareId: string; shareCode: string } | null>(null);

  useEffect(() => {
    refreshDrive(0);
  }, [refreshDrive]);

  const breadcrumbs = [{ id: 0, name: '全部文件' }];

  const onDownloadFile = async (fileId: string, fileName: string, fileSize: number) => {
    try {
      await downloadFile({
        url: downloadUrl(fileId),
        fileName,
        fileSize,
      });
    } catch (e) {
      showToast({ kind: 'error', title: '下载失败', description: (e as Error).message });
    }
  };

  return (
    <div className="flex flex-col gap-3">
      {/* Toolbar */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm text-slate-600">
          {driveParentId !== 0 && (
            <button
              onClick={() => refreshDrive(0)}
              className="flex items-center gap-1 rounded-lg px-2 py-1 text-slate-500 hover:bg-slate-100"
            >
              <ChevronLeft className="h-4 w-4" /> 返回根目录
            </button>
          )}
          <span className="font-medium text-slate-800">我的网盘</span>
        </div>
        <Button size="sm" variant="ghost" onClick={() => setShowMkdir(true)}>
          <FolderPlus className="mr-1 h-4 w-4" /> 新建文件夹
        </Button>
      </div>

      {/* Mkdir input */}
      {showMkdir && (
        <div className="flex items-center gap-2 rounded-lg border border-slate-200 bg-white p-3">
          <input
            value={newDirName}
            onChange={(e) => setNewDirName(e.target.value)}
            placeholder="文件夹名称"
            className="flex-1 rounded border border-slate-200 px-3 py-1.5 text-sm focus:border-brand-400 focus:outline-none"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && newDirName.trim()) {
                driveMkdir(driveParentId, newDirName.trim());
                setNewDirName('');
                setShowMkdir(false);
              }
            }}
          />
          <Button size="sm" onClick={() => {
            if (newDirName.trim()) {
              driveMkdir(driveParentId, newDirName.trim());
              setNewDirName('');
              setShowMkdir(false);
            }
          }}>创建</Button>
          <Button size="sm" variant="ghost" onClick={() => setShowMkdir(false)}>取消</Button>
        </div>
      )}

      {/* File list */}
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        {driveLoading ? (
          <div className="py-12 text-center text-sm text-slate-400">加载中...</div>
        ) : driveFiles.length === 0 ? (
          <div className="py-12 text-center text-sm text-slate-400">空文件夹</div>
        ) : (
          <div className="divide-y divide-slate-50">
            {driveFiles.map((f) => (
              <div key={f.id} className="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50/50">
                {f.isDir ? (
                  <Folder className="h-5 w-5 shrink-0 text-amber-500" />
                ) : (
                  <FileIcon className="h-5 w-5 shrink-0 text-slate-400" />
                )}

                {renameId === f.id ? (
                  <input
                    value={renameValue}
                    onChange={(e) => setRenameValue(e.target.value)}
                    className="flex-1 rounded border border-slate-200 px-2 py-0.5 text-sm"
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && renameValue.trim()) {
                        driveRename(f.id, renameValue.trim());
                        setRenameId(null);
                      }
                    }}
                    onBlur={() => setRenameId(null)}
                    autoFocus
                  />
                ) : (
                  <button
                    className="flex-1 text-left text-sm font-medium text-slate-700"
                    onClick={() => f.isDir && refreshDrive(f.id)}
                  >
                    {f.fileName}
                  </button>
                )}

                {!f.isDir && (
                  <span className="text-xs text-slate-400">{formatBytes(f.fileSize)}</span>
                )}

                <div className="flex items-center gap-1">
                  {!f.isDir && f.fileId && (
                    <>
                      <button
                        type="button"
                        className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                        title="下载"
                        onClick={() => onDownloadFile(f.fileId!, f.fileName, f.fileSize)}
                      >
                        <Download className="h-3.5 w-3.5" />
                      </button>
                      <button
                        className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                        title="分享"
                        onClick={async () => {
                          const r = await createShare(f.id, 7);
                          if (r) setShareResult(r);
                        }}
                      >
                        <Share2 className="h-3.5 w-3.5" />
                      </button>
                    </>
                  )}
                  <button
                    className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                    title="重命名"
                    onClick={() => { setRenameId(f.id); setRenameValue(f.fileName); }}
                  >
                    <Edit3 className="h-3.5 w-3.5" />
                  </button>
                  <button
                    className="rounded p-1 text-red-400 hover:bg-red-50"
                    title="删除"
                    onClick={() => {
                      if (confirm(`确定删除 "${f.fileName}" 吗？`)) driveDelete(f.id);
                    }}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Share result modal */}
      {shareResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30" onClick={() => setShareResult(null)}>
          <div className="w-96 rounded-xl bg-white p-5 shadow-xl" onClick={(e) => e.stopPropagation()}>
            <div className="mb-3 text-sm font-semibold text-slate-800">分享链接已创建</div>
            <div className="space-y-2 text-sm">
              <div>
                <span className="text-slate-500">分享ID：</span>
                <code className="rounded bg-slate-100 px-1.5 py-0.5 text-xs">{shareResult.shareId}</code>
              </div>
              <div>
                <span className="text-slate-500">提取码：</span>
                <code className="rounded bg-brand-50 px-1.5 py-0.5 text-xs font-bold text-brand-700">{shareResult.shareCode}</code>
              </div>
              <div>
                <span className="text-slate-500">链接：</span>
                <code className="break-all text-xs text-brand-600">{window.location.origin}/#/s/{shareResult.shareId}</code>
              </div>
            </div>
            <div className="mt-4 flex justify-end">
              <Button size="sm" onClick={() => setShareResult(null)}>关闭</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
