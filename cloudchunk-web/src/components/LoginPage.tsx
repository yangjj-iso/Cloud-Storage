import { FormEvent, type ReactNode, useState } from 'react';
import { LockKeyhole, Mail, Package2, UserRound, ArrowLeft } from 'lucide-react';
import { useAppStore } from '../store';
import { ApiError, api } from '../lib/api';
import { Button } from './ui/Button';

type Mode = 'login' | 'register' | 'forgot';

export function LoginPage() {
  const login = useAppStore((s) => s.login);
  const register = useAppStore((s) => s.register);
  const authLoading = useAppStore((s) => s.authLoading);
  const showToast = useAppStore((s) => s.showToast);

  const [mode, setMode] = useState<Mode>('login');
  const [account, setAccount] = useState('');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [nickname, setNickname] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  // Forgot password fields
  const [resetEmail, setResetEmail] = useState('');
  const [resetCode, setResetCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);

  const isRegister = mode === 'register';
  const isForgot = mode === 'forgot';

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    try {
      if (isRegister) {
        await register({ username, email: email.trim() || undefined, password, nickname: nickname.trim() || undefined });
      } else if (isForgot) {
        await api.resetPassword(resetEmail, resetCode, newPassword);
        showToast({ kind: 'success', title: '密码已重置', description: '请使用新密码登录' });
        setMode('login');
        setAccount(resetEmail);
      } else {
        await login({ account, password });
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : (err as Error).message);
    }
  }

  async function sendCode() {
    if (!resetEmail) { setError('请输入邮箱'); return; }
    setSendingCode(true);
    setError('');
    try {
      await api.sendCode(resetEmail, 'reset');
      setCodeSent(true);
      showToast({ kind: 'success', title: '验证码已发送', description: '请查收邮箱' });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : (err as Error).message);
    } finally {
      setSendingCode(false);
    }
  }

  return (
    <div className="flex min-h-full items-center justify-center bg-slate-50 px-4 py-10">
      <div className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-6 shadow-xl shadow-slate-200/70">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-600 text-white shadow-sm shadow-brand-600/30">
            <Package2 className="h-5 w-5" />
          </div>
          <div>
            <div className="text-lg font-semibold tracking-tight text-slate-900">CloudChunk</div>
            <div className="text-xs text-slate-500">
              {isRegister ? '创建账号' : isForgot ? '找回密码' : '登录账号'}
            </div>
          </div>
        </div>

        {!isForgot && (
          <div className="mb-5 grid grid-cols-2 rounded-lg bg-slate-100 p-1 text-sm font-medium">
            <button
              type="button"
              onClick={() => { setMode('login'); setError(''); }}
              className={`rounded-md px-3 py-1.5 ${!isRegister ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => { setMode('register'); setError(''); }}
              className={`rounded-md px-3 py-1.5 ${isRegister ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}
            >
              注册
            </button>
          </div>
        )}

        <form className="flex flex-col gap-4" onSubmit={submit}>
          {isRegister && (
            <>
              <Field icon={<UserRound className="h-4 w-4" />} label="用户名" value={username} onChange={setUsername} autoComplete="username" placeholder="cloudchunk" />
              <Field icon={<Mail className="h-4 w-4" />} label="邮箱" value={email} onChange={setEmail} autoComplete="email" placeholder="name@example.com" type="email" />
              <Field icon={<UserRound className="h-4 w-4" />} label="昵称" value={nickname} onChange={setNickname} placeholder="显示名称（可选）" />
            </>
          )}

          {isForgot && (
            <>
              <button type="button" onClick={() => { setMode('login'); setError(''); }} className="flex items-center gap-1 text-xs text-slate-400 hover:text-slate-600">
                <ArrowLeft className="h-3 w-3" /> 返回登录
              </button>
              <Field icon={<Mail className="h-4 w-4" />} label="邮箱" value={resetEmail} onChange={setResetEmail} placeholder="name@example.com" type="email" />
              <div className="flex items-end gap-2">
                <div className="flex-1">
                  <Field icon={<LockKeyhole className="h-4 w-4" />} label="验证码" value={resetCode} onChange={setResetCode} placeholder="6位验证码" />
                </div>
                <Button type="button" size="sm" variant="ghost" onClick={sendCode} loading={sendingCode} disabled={codeSent}>
                  {codeSent ? '已发送' : '发送验证码'}
                </Button>
              </div>
              <Field icon={<LockKeyhole className="h-4 w-4" />} label="新密码" value={newPassword} onChange={setNewPassword} placeholder="至少 8 位" type="password" />
            </>
          )}

          {!isRegister && !isForgot && (
            <>
              <Field icon={<UserRound className="h-4 w-4" />} label="账号" value={account} onChange={setAccount} autoComplete="username" placeholder="用户名或邮箱" />
              <Field icon={<LockKeyhole className="h-4 w-4" />} label="密码" value={password} onChange={setPassword} autoComplete="current-password" placeholder="至少 8 位" type="password" />
              <div className="flex justify-end">
                <button type="button" onClick={() => { setMode('forgot'); setError(''); setCodeSent(false); }} className="text-xs text-brand-600 hover:text-brand-700">
                  忘记密码？
                </button>
              </div>
            </>
          )}

          {isRegister && (
            <Field icon={<LockKeyhole className="h-4 w-4" />} label="密码" value={password} onChange={setPassword} autoComplete="new-password" placeholder="至少 8 位" type="password" />
          )}

          {error && (
            <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm text-rose-700">
              {error}
            </div>
          )}

          <Button type="submit" size="lg" loading={authLoading} className="mt-1 w-full">
            {isRegister ? '注册并进入' : isForgot ? '重置密码' : '登录'}
          </Button>
        </form>
      </div>
    </div>
  );
}

function Field({
  icon, label, value, onChange, placeholder, type = 'text', autoComplete,
}: {
  icon: ReactNode;
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  type?: string;
  autoComplete?: string;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium text-slate-600">{label}</span>
      <span className="flex h-10 items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 text-slate-500 focus-within:border-brand-400 focus-within:ring-2 focus-within:ring-brand-100">
        {icon}
        <input
          className="min-w-0 flex-1 bg-transparent text-sm text-slate-900 outline-none placeholder:text-slate-400"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          type={type}
          autoComplete={autoComplete}
          required={label !== '邮箱'}
        />
      </span>
    </label>
  );
}
