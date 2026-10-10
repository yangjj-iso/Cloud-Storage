import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { formatBytes } from '../lib/utils';
import { api, downloadUrl } from '../lib/api';
import { downloadFile } from '../lib/download';
import { MaterialIcon } from './ui/MaterialIcon';
import { formatZhDate } from '../lib/locale';

export function DriveView() {
  const { driveFiles, driveParentId, driveLoading, refreshDrive, driveMkdir, driveRename, driveMove, driveDelete, createShare, showToast, setView } = useAppStore();
  const [showMkdir, setShowMkdir] = useState(false);
  const [newDirName, setNewDirName] = useState('');
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [menuId, setMenuId] = useState<number | null>(null);
  const [renameId, setRenameId] = useState<number | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [shareResult, setShareResult] = useState<{ shareId: string; shareCode: string } | null>(null);
  const [movingId, setMovingId] = useState<number | null>(null);
  const [targetParentId, setTargetParentId] = useState('0');
  const [auditOpen,setAuditOpen]=useState(false);
  const [audits,setAudits]=useState<Array<{operation:string;success:boolean;costMs:number;at:string}>>([]);
  const [auditError,setAuditError]=useState('');
  const [renameError,setRenameError]=useState('');
  const [mkdirError,setMkdirError]=useState('');
  const [moveError,setMoveError]=useState('');
  const [busyAction,setBusyAction]=useState(false);
  const [auditBusy,setAuditBusy]=useState(false);
  const loadAudit=async()=>{
    setAuditBusy(true);setAuditError('');
    try{setAudits(await api.driveAudit());}
    catch(e){setAuditError(e instanceof Error?e.message:'操作记录读取失败');}
    finally{setAuditBusy(false);}
  };
  const isConflict=(error:unknown)=>error instanceof Error && 'code' in error &&
    (error as {code?:number}).code===300004;


  useEffect(() => { void refreshDrive(0); }, [refreshDrive]);

  const submitFolder = async () => {
    if (!newDirName.trim()) return;
    if(busyAction)return;
    setBusyAction(true);setMkdirError('');
    try{
      await api.driveMkdir(driveParentId,newDirName.trim());
      showToast({kind:'success',title:'目录已创建'});
      await refreshDrive();
      setNewDirName('');setShowMkdir(false);
    }catch(e){
      setMkdirError(isConflict(e)?'此目录已有同名文件或文件夹，请修改名称再试':
        e instanceof Error?e.message:'创建失败');
    }finally{setBusyAction(false);}
  };

  const onDownload = async (fileId: string, fileName: string, fileSize: number) => {
    try { await downloadFile({ url: downloadUrl(fileId), fileName, fileSize }); }
    catch (reason) { showToast({ kind: 'error', title: '下载失败', description: (reason as Error).message }); }
  };

  const downloadSelected = async () => {
    if (!selected.size) return;
    try {
      const blob = await api.driveDownloadZip([...selected]);
      const anchor = document.createElement('a');
      anchor.href = URL.createObjectURL(blob);
      anchor.download = `CloudChunk-打包下载-${new Date().toISOString().slice(0, 10)}.zip`;
      anchor.click();
      URL.revokeObjectURL(anchor.href);
    } catch (reason) { showToast({ kind: 'error', title: '打包下载失败', description: (reason as Error).message }); }
  };

  const toggle = (id: number) => setSelected((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return (
    <div className="w-full max-w-[1400px] p-margin-mobile md:p-margin-desktop">
      <div className="mb-lg flex items-center justify-between">
        <div className="flex items-center gap-md">
          {driveParentId !== 0 && <button type="button" onClick={() => refreshDrive(0)} className="stitch-icon-button"><MaterialIcon>arrow_back</MaterialIcon></button>}
          <h1 className="text-headline-lg text-on-surface">我的云盘</h1>
        </div>
        <div className="flex gap-md">
          {selected.size > 0 && <button type="button" onClick={downloadSelected} className="flex h-10 items-center gap-sm rounded-lg border border-primary-fixed-dim bg-primary-fixed px-md text-label-md text-primary shadow-sm"><MaterialIcon className="text-[18px]">folder_zip</MaterialIcon>打包下载（{selected.size}）</button>}
          <button type="button" onClick={() => {setMkdirError('');setShowMkdir(true);}} className="flex h-10 items-center gap-sm rounded-lg border border-outline-variant bg-surface-container-lowest px-md text-label-md text-on-surface shadow-sm transition-colors hover:bg-surface-container">
            <MaterialIcon className="text-[18px]">create_new_folder</MaterialIcon>新建文件夹
          </button>
          <button type="button" onClick={()=>{setAuditOpen(v=>!v);void loadAudit();}} className="flex h-10 items-center gap-sm rounded-lg border border-outline-variant px-md text-label-md"><MaterialIcon>history</MaterialIcon>操作记录</button>
          <button type="button" onClick={() => setView('upload')} className="flex h-10 items-center gap-sm rounded-lg bg-primary px-md text-label-md text-on-primary shadow-sm transition-colors hover:bg-primary-container">
            <MaterialIcon className="text-[18px]">upload</MaterialIcon>上传文件
          </button>
        </div>
      </div>

      {showMkdir && (
        <div className="mb-md flex items-center gap-sm rounded-xl border border-outline-variant bg-white p-md shadow-sm">
          <MaterialIcon className="text-secondary">create_new_folder</MaterialIcon>
          <input autoFocus value={newDirName} onChange={(event) => setNewDirName(event.target.value)} onKeyDown={(event) => event.key === 'Enter' && submitFolder()} placeholder="请输入文件夹名称" className="stitch-focus h-9 min-w-0 flex-1 rounded border border-outline-variant px-sm text-body-md" />
          <button type="button" disabled={busyAction} onClick={submitFolder} className="h-9 rounded bg-primary px-md text-label-md text-white">创建</button>
          <button type="button" onClick={() => setShowMkdir(false)} className="h-9 rounded px-md text-label-md text-on-surface-variant hover:bg-surface-container">取消</button>
          {mkdirError && <span role="alert" className="text-error text-label-sm">{mkdirError}</span>}
        </div>
      )}

      {auditOpen && <section className="mb-md rounded-xl border border-outline-variant bg-white p-md">
        <div className="mb-sm flex justify-between"><strong>最近 30 条网盘操作（仅当前账号）</strong>
          <button type="button" disabled={auditBusy} onClick={()=>void loadAudit()}>刷新</button></div>
        {auditError && <p role="alert" className="text-error">{auditError}</p>}
        {auditBusy && <p>正在加载审计记录…</p>}
        {!auditBusy && audits.length===0 && <p>暂无操作记录</p>}
        <div className="max-h-44 overflow-y-auto">
          {audits.map((a,i)=><div key={i} className="flex justify-between gap-sm border-b py-xs text-body-sm">
            <span>{({DRIVE_MKDIR:'新建目录',DRIVE_RENAME:'重命名',DRIVE_MOVE:'移动',
              DRIVE_RECYCLE:'移入回收站',DRIVE_RESTORE:'还原',DRIVE_PURGE:'彻底删除'} as Record<string,string>)[a.operation]||a.operation}
              {' · '}{a.success?'成功':'失败'}</span><span>{a.at}</span>
          </div>)}
        </div>
      </section>}
      <section className="overflow-visible rounded-xl border border-outline-variant bg-surface-container-lowest shadow-sm">
        <div className="grid grid-cols-12 gap-md rounded-t-xl border-b border-outline-variant bg-surface-container-low p-md text-label-md uppercase tracking-wider text-on-surface-variant">
          <div className="col-span-6 flex items-center gap-md"><input aria-label="全选" type="checkbox" checked={driveFiles.length > 0 && selected.size === driveFiles.length} onChange={() => setSelected(selected.size === driveFiles.length ? new Set() : new Set(driveFiles.map((item) => item.id)))} className="h-4 w-4 rounded accent-primary" /><span>名称</span></div>
          <div className="col-span-2 text-right">大小</div><div className="col-span-3">修改时间</div><div className="col-span-1 text-center">操作</div>
        </div>

        {driveLoading ? (
          <div className="flex h-28 items-center justify-center text-body-md text-outline"><MaterialIcon className="mr-sm animate-spin">progress_activity</MaterialIcon>正在加载文件…</div>
        ) : driveFiles.length === 0 ? (
          <div className="flex h-32 flex-col items-center justify-center text-body-md text-outline"><MaterialIcon className="mb-sm text-[32px]">folder_open</MaterialIcon>此文件夹为空</div>
        ) : (
          <div>{driveFiles.map((file, index) => {
            const isSelected = selected.has(file.id);
            const appearance = fileAppearance(file.fileName, file.isDir);
            return (
              <div key={file.id} className={`group grid min-h-[66px] grid-cols-12 items-center gap-md p-md transition-colors ${index < driveFiles.length - 1 ? 'border-b border-outline-variant' : ''} ${isSelected ? 'bg-secondary-container/30' : 'hover:bg-surface-container-lowest/50'}`}>
                <div className="col-span-6 flex min-w-0 items-center gap-md">
                  <input aria-label={`选择 ${file.fileName}`} type="checkbox" checked={isSelected} onChange={() => toggle(file.id)} className="h-4 w-4 shrink-0 rounded accent-primary" />
                  <MaterialIcon filled className={`shrink-0 ${appearance.color}`}>{appearance.icon}</MaterialIcon>
                  {renameId === file.id ? (
                    <input autoFocus value={renameValue} onChange={(event) => setRenameValue(event.target.value)} onBlur={() => setRenameId(null)} onKeyDown={async (event) => { if (event.key === 'Enter' && renameValue.trim()) {
                      setRenameError('');
                      try{await api.driveRename(file.id,renameValue.trim());setRenameId(null);void refreshDrive();}
                      catch(e){setRenameError(isConflict(e)?'存在同名条目，请修改后重试':e instanceof Error?e.message:'重命名失败');}
                    } }} className="stitch-focus h-8 min-w-0 flex-1 rounded border border-outline-variant px-sm text-body-md" />
                  ) : (
                    <button type="button" onDoubleClick={() => file.isDir && refreshDrive(file.id)} onClick={() => file.isDir && refreshDrive(file.id)} className="truncate text-left text-body-md font-medium text-on-surface hover:text-primary">{file.fileName}</button>
                  )}
                </div>
                {renameId===file.id && renameError && <p role="alert" className="col-span-6 text-error text-label-sm">{renameError}</p>}
                <div className="col-span-2 text-right text-body-md text-on-surface-variant">{file.isDir ? '--' : formatBytes(file.fileSize)}</div>
                <div className="col-span-3 text-body-md text-on-surface-variant">{formatDriveDate(file.updatedAt || file.createdAt)}</div>
                <div className="relative col-span-1 flex justify-center">
                  <button type="button" onClick={() => setMenuId(menuId === file.id ? null : file.id)} className={`rounded-full p-xs text-on-surface-variant transition-all hover:bg-surface-container hover:text-primary ${isSelected || menuId === file.id ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}><MaterialIcon className="text-[20px]">more_vert</MaterialIcon></button>
                  {menuId === file.id && (
                    <div className="absolute right-2 top-8 z-20 w-36 rounded-xl border border-outline-variant bg-white p-xs text-body-md shadow-ambient">
                      {!file.isDir && file.fileId && <MenuItem icon="download" label="下载" onClick={() => { setMenuId(null); onDownload(file.fileId!, file.fileName, file.fileSize); }} />}
                      <MenuItem icon="folder_zip" label="打包下载" onClick={() => { setMenuId(null); setSelected(new Set([file.id])); void api.driveDownloadZip([file.id]).then((blob) => { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = `${file.fileName}.zip`; a.click(); URL.revokeObjectURL(a.href); }).catch((reason) => showToast({ kind: 'error', title: '打包下载失败', description: (reason as Error).message })); }} />
                      <MenuItem icon="share" label="分享" onClick={async () => { setMenuId(null); const result = await createShare(file.id, 7); if (result) setShareResult(result); }} />
                      <MenuItem icon="edit" label="重命名" onClick={() => { setRenameId(file.id); setRenameError(''); setRenameValue(file.fileName); setMenuId(null); }} />
                      <MenuItem icon="drive_file_move" label="移动" onClick={() => { setMovingId(file.id); setTargetParentId('0');setMoveError(''); setMenuId(null); }} />
                      <MenuItem danger icon="delete" label="移入回收站" onClick={() => { setMenuId(null); if (confirm(`确定将“${file.fileName}”移入回收站吗？`)) driveDelete(file.id); }} />
                    </div>
                  )}
                </div>
              </div>
            );
          })}</div>
        )}
      </section>

      {shareResult && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center bg-inverse-surface/30" onClick={() => setShareResult(null)}>
          <div className="w-[420px] rounded-xl border border-outline-variant bg-white p-lg shadow-ambient" onClick={(event) => event.stopPropagation()}>
            <h2 className="text-headline-md">分享链接已创建</h2>
            <div className="mt-md space-y-sm text-body-md text-on-surface-variant"><p>分享编号：<code className="text-on-surface">{shareResult.shareId}</code></p><p>提取码：<code className="font-semibold text-primary">{shareResult.shareCode}</code></p><p className="break-all">{window.location.origin}/#/s/{shareResult.shareId}?code={encodeURIComponent(shareResult.shareCode)}</p></div>
            <button type="button" onClick={async () => { await navigator.clipboard.writeText(`${window.location.origin}/#/s/${shareResult.shareId}?code=${encodeURIComponent(shareResult.shareCode)}`); showToast({ kind: 'success', title: '分享链接已复制' }); }} className="mt-lg h-10 w-full rounded border border-primary text-label-md text-primary">复制分享链接</button>
            <button type="button" onClick={() => setShareResult(null)} className="mt-sm h-10 w-full rounded bg-primary text-label-md text-white">完成</button>
          </div>
        </div>
      )}

      {movingId !== null && (
        <div className="fixed inset-0 z-[70] flex items-center justify-center bg-inverse-surface/30" onClick={() => setMovingId(null)}>
          <div className="w-[380px] rounded-xl border border-outline-variant bg-white p-lg shadow-ambient" onClick={(event) => event.stopPropagation()}>
            <h2 className="text-headline-md">移动文件</h2><p className="mt-xs text-body-md text-on-surface-variant">请输入目标文件夹编号；输入 0 表示根目录。</p>
            <label className="mt-lg block text-label-sm text-on-surface-variant">目标文件夹编号<input autoFocus type="number" min="0" value={targetParentId} onChange={(event) => setTargetParentId(event.target.value)} className="stitch-focus mt-xs h-10 w-full rounded border border-outline-variant px-sm text-body-md" /></label>
            {moveError && <p role="alert" className="mt-sm text-error text-label-sm">{moveError}</p>}
            <div className="mt-lg flex justify-end gap-sm"><button type="button" onClick={() => setMovingId(null)} className="h-10 rounded px-md text-label-md text-on-surface-variant">取消</button><button type="button" onClick={async () => {try{await api.driveMove(movingId,Math.max(0,Number(targetParentId)||0));setMovingId(null);void refreshDrive();}
              catch(e){setMoveError(isConflict(e)?'目标目录存在同名文件或文件夹，请选择其他位置':e instanceof Error?e.message:'移动失败');}}} className="h-10 rounded bg-primary px-md text-label-md text-white">确认移动</button></div>
          </div>
        </div>
      )}
    </div>
  );
}

function MenuItem({ icon, label, onClick, danger = false }: { icon: string; label: string; onClick: () => void; danger?: boolean }) {
  return <button type="button" onClick={onClick} className={`flex w-full items-center gap-sm rounded-lg px-sm py-sm text-left hover:bg-surface-container ${danger ? 'text-error' : 'text-on-surface-variant'}`}><MaterialIcon className="text-[17px]">{icon}</MaterialIcon>{label}</button>;
}

function fileAppearance(name: string, isDir: boolean) {
  if (isDir) return { icon: 'folder', color: 'text-secondary' };
  const extension = name.split('.').pop()?.toLowerCase();
  if (extension === 'pdf') return { icon: 'picture_as_pdf', color: 'text-error' };
  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(extension || '')) return { icon: 'image', color: 'text-primary' };
  if (['csv', 'xls', 'xlsx'].includes(extension || '')) return { icon: 'table_chart', color: 'text-tertiary-container' };
  return { icon: 'draft', color: 'text-secondary' };
}

function formatDriveDate(value: string) {
  return formatZhDate(value, true);
}
