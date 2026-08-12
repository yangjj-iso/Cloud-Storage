import { useState } from 'react';
import { api, saveStoredUser } from '../lib/api';
import { roleLabel } from '../lib/locale';
import { useAppStore } from '../store';
import { MaterialIcon } from './ui/MaterialIcon';

export function AccountSettingsDialog({ onClose }: { onClose: () => void }) {
  const user = useAppStore((state) => state.authUser);
  const showToast = useAppStore((state) => state.showToast);
  const [newEmail, setNewEmail] = useState(user?.email || '');
  const [code, setCode] = useState('');
  const [sending, setSending] = useState(false);
  const [saving, setSaving] = useState(false);

  const sendCode = async () => {
    if (!newEmail.trim()) return;
    setSending(true);
    try {
      await api.sendCode(newEmail.trim(), 'bind');
      showToast({ kind: 'success', title: '验证码已发送', description: `验证码已发送至 ${newEmail.trim()}` });
    } catch (reason) { showToast({ kind: 'error', title: '验证码发送失败', description: (reason as Error).message }); }
    finally { setSending(false); }
  };

  const save = async () => {
    if (!newEmail.trim() || !code.trim()) return;
    setSaving(true);
    try {
      await api.changeEmail(newEmail.trim(), code.trim());
      const nextUser = await api.me();
      saveStoredUser(nextUser);
      useAppStore.setState({ authUser: nextUser });
      showToast({ kind: 'success', title: '邮箱已更新' });
      onClose();
    } catch (reason) { showToast({ kind: 'error', title: '邮箱更新失败', description: (reason as Error).message }); }
    finally { setSaving(false); }
  };

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-inverse-surface/30 p-md" onClick={onClose}>
      <section className="w-full max-w-md rounded-xl border border-outline-variant bg-white p-lg shadow-ambient" onClick={(event) => event.stopPropagation()}>
        <div className="flex items-center justify-between"><h2 className="text-headline-md">账户设置</h2><button type="button" onClick={onClose} className="stitch-icon-button" aria-label="关闭"><MaterialIcon>close</MaterialIcon></button></div>
        <div className="mt-lg rounded-xl bg-surface-container-low p-md text-body-md"><div className="font-semibold">{user?.username}</div><div className="mt-xs text-on-surface-variant">{roleLabel(user?.role)}</div></div>
        <div className="mt-lg space-y-md">
          <label className="block text-label-sm text-on-surface-variant">新邮箱<input type="email" value={newEmail} onChange={(event) => setNewEmail(event.target.value)} className="stitch-focus mt-xs h-10 w-full rounded border border-outline-variant px-sm text-body-md" placeholder="请输入新的邮箱地址" /></label>
          <div className="grid grid-cols-[1fr_auto] items-end gap-sm"><label className="block text-label-sm text-on-surface-variant">邮箱验证码<input value={code} onChange={(event) => setCode(event.target.value)} className="stitch-focus mt-xs h-10 w-full rounded border border-outline-variant px-sm text-body-md" placeholder="请输入验证码" /></label><button type="button" onClick={sendCode} disabled={sending || !newEmail.trim()} className="h-10 rounded border border-outline-variant px-md text-label-md text-primary disabled:opacity-50">{sending ? '发送中…' : '发送验证码'}</button></div>
        </div>
        <div className="mt-lg flex justify-end gap-sm"><button type="button" onClick={onClose} className="h-10 rounded px-md text-label-md text-on-surface-variant hover:bg-surface-container">取消</button><button type="button" onClick={save} disabled={saving || !code.trim()} className="h-10 rounded bg-primary px-md text-label-md text-white disabled:opacity-50">{saving ? '保存中…' : '保存更改'}</button></div>
      </section>
    </div>
  );
}
