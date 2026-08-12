import { FormEvent, useEffect, useState } from 'react';
import { api, getStoredAuth } from '../lib/api';
import { downloadFile } from '../lib/download';
import { formatBytes } from '../lib/utils';
import type { ShareDetail, UserFileItem } from '../types';
import { MaterialIcon } from './ui/MaterialIcon';

export function getPublicShareId(): string | null {
  const match = window.location.hash.match(/^#\/s\/([0-9a-fA-F]{16,32})(?:\?|$)/);
  return match?.[1] ?? null;
}

function getShareCodeFromHash(): string {
  const query = window.location.hash.split('?')[1] || '';
  return new URLSearchParams(query).get('code') || '';
}

export function PublicSharePage({ shareId }: { shareId: string }) {
  const [code, setCode] = useState(getShareCodeFromHash);
  const [detail, setDetail] = useState<ShareDetail | null>(null);
  const [children, setChildren] = useState<UserFileItem[]>([]);
  const [parentId, setParentId] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const load = async (nextCode = code, nextParentId = 0) => {
    if (!nextCode.trim()) { setError('请输入分享提取码'); return; }
    setLoading(true);
    setError('');
    try {
      const nextDetail = detail ?? await api.publicShare(shareId, nextCode.trim());
      setDetail(nextDetail);
      setParentId(nextParentId);
      setChildren(nextDetail.isDir ? await api.publicShareChildren(shareId, nextCode.trim(), nextParentId) : []);
    } catch (reason) {
      setError((reason as Error).message);
      if (!detail) setDetail(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const initialCode = getShareCodeFromHash();
    if (initialCode) void load(initialCode, 0);
    // 仅在分享 ID 变化时自动读取 URL 中的提取码。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shareId]);

  const submit = (event: FormEvent) => { event.preventDefault(); void load(code, 0); };

  const download = async () => {
    if (!detail) return;
    if (detail.isDir) {
      window.open(api.publicShareZipUrl(shareId, code.trim()), '_blank', 'noopener,noreferrer');
      return;
    }
    try {
      await downloadFile({
        url: api.publicShareDownloadUrl(shareId, code.trim()),
        fileName: detail.fileName,
        fileSize: detail.fileSize,
      });
    } catch (reason) { setError((reason as Error).message); }
  };

  const save = async () => {
    if (!detail) return;
    if (!getStoredAuth()) {
      window.location.hash = '';
      return;
    }
    setSaving(true);
    setError('');
    try { await api.saveToMyDrive(shareId, code.trim(), 0); }
    catch (reason) { setError((reason as Error).message); }
    finally { setSaving(false); }
  };

  return (
    <div className="min-h-full bg-background px-margin-mobile py-xl text-on-surface md:px-margin-desktop">
      <header className="mx-auto flex max-w-5xl items-center justify-between">
        <button type="button" onClick={() => { window.location.hash = ''; }} className="flex items-center gap-sm text-left">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary text-on-primary"><MaterialIcon filled>cloud</MaterialIcon></span>
          <span><strong className="block text-headline-md text-primary">CloudChunk</strong><span className="text-label-sm text-on-surface-variant">云端文件分享</span></span>
        </button>
        <button type="button" onClick={() => { window.location.hash = ''; }} className="rounded-lg border border-outline-variant bg-white px-md py-sm text-label-md hover:bg-surface-container-low">返回我的网盘</button>
      </header>

      <main className="mx-auto mt-xl max-w-5xl">
        {!detail ? (
          <section className="mx-auto max-w-md rounded-xl border border-outline-variant bg-white p-xl text-center shadow-ambient">
            <span className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-primary-fixed text-primary"><MaterialIcon className="text-[32px]">folder_shared</MaterialIcon></span>
            <h1 className="mt-lg text-headline-lg">访问分享文件</h1>
            <p className="mt-xs text-body-md text-on-surface-variant">请输入分享者提供的提取码</p>
            <form onSubmit={submit} className="mt-lg space-y-md text-left">
              <label className="block text-label-sm text-on-surface-variant">提取码<input autoFocus required value={code} onChange={(event) => setCode(event.target.value)} className="stitch-focus mt-xs h-11 w-full rounded-lg border border-outline-variant px-md text-body-lg" placeholder="请输入提取码" /></label>
              {error && <div className="rounded-lg bg-error-container px-md py-sm text-body-md text-on-error-container">{error}</div>}
              <button disabled={loading} className="flex h-11 w-full items-center justify-center rounded-lg bg-primary text-label-md text-white disabled:opacity-60">{loading ? '正在验证…' : '查看分享'}</button>
            </form>
          </section>
        ) : (
          <>
            <section className="flex flex-col justify-between gap-md rounded-xl border border-outline-variant bg-white p-lg shadow-sm sm:flex-row sm:items-center">
              <div className="flex min-w-0 items-center gap-md">
                <span className="flex h-14 w-14 shrink-0 items-center justify-center rounded-xl bg-primary-fixed text-primary"><MaterialIcon className="text-[30px]">{detail.isDir ? 'folder' : 'draft'}</MaterialIcon></span>
                <div className="min-w-0"><h1 className="truncate text-headline-md">{detail.fileName}</h1><p className="mt-xs text-body-md text-on-surface-variant">{detail.isDir ? '共享文件夹' : `${detail.mimeType || '文件'} · ${formatBytes(detail.fileSize)}`}</p></div>
              </div>
              <div className="flex gap-sm">
                <button type="button" onClick={save} disabled={saving} className="flex h-10 items-center gap-xs rounded-lg border border-outline-variant px-md text-label-md hover:bg-surface-container-low disabled:opacity-60"><MaterialIcon className="text-[18px]">add_to_drive</MaterialIcon>{getStoredAuth() ? (saving ? '正在转存…' : '转存到我的网盘') : '登录后转存'}</button>
                <button type="button" onClick={download} className="flex h-10 items-center gap-xs rounded-lg bg-primary px-md text-label-md text-white"><MaterialIcon className="text-[18px]">download</MaterialIcon>{detail.isDir ? '打包下载' : '下载文件'}</button>
              </div>
            </section>

            {error && <div className="mt-md rounded-lg bg-error-container px-md py-sm text-body-md text-on-error-container">{error}</div>}
            {detail.isDir && (
              <section className="mt-lg overflow-hidden rounded-xl border border-outline-variant bg-white shadow-sm">
                <div className="flex h-14 items-center gap-sm border-b border-outline-variant px-md">
                  {parentId !== 0 && <button type="button" onClick={() => void load(code, 0)} className="stitch-icon-button" aria-label="返回分享根目录"><MaterialIcon>arrow_back</MaterialIcon></button>}
                  <span className="text-body-md font-semibold">{parentId === 0 ? '分享根目录' : '子目录'}</span>
                </div>
                {loading ? <div className="flex h-32 items-center justify-center text-on-surface-variant"><MaterialIcon className="mr-sm animate-spin">progress_activity</MaterialIcon>正在加载…</div> : children.length === 0 ? <div className="flex h-32 items-center justify-center text-on-surface-variant">此文件夹为空</div> : children.map((item) => (
                  <button key={item.id} type="button" disabled={!item.isDir} onClick={() => item.isDir && void load(code, item.id)} className="grid min-h-[60px] w-full grid-cols-[1fr_120px_160px] items-center gap-md border-b border-surface-container px-md text-left text-body-md last:border-0 hover:bg-surface-bright disabled:cursor-default">
                    <span className="flex min-w-0 items-center gap-sm"><MaterialIcon className={item.isDir ? 'text-secondary' : 'text-primary'}>{item.isDir ? 'folder' : 'draft'}</MaterialIcon><span className="truncate font-medium">{item.fileName}</span></span>
                    <span className="text-on-surface-variant">{item.isDir ? '文件夹' : formatBytes(item.fileSize)}</span>
                    <span className="text-on-surface-variant">{new Date(item.updatedAt || item.createdAt).toLocaleDateString('zh-CN')}</span>
                  </button>
                ))}
              </section>
            )}
          </>
        )}
      </main>
    </div>
  );
}

