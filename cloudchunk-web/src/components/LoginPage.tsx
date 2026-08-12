import { FormEvent, useState } from 'react';
import { useAppStore } from '../store';
import { ApiError, api } from '../lib/api';
import { MaterialIcon } from './ui/MaterialIcon';

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
  const [password, setPassword] = useState('');
  const [resetCode, setResetCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [sendingCode, setSendingCode] = useState(false);
  const [codeSent, setCodeSent] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    try {
      if (mode === 'register') {
        await register({ username, email: email.trim() || undefined, password });
      } else if (mode === 'forgot') {
        await api.resetPassword(email, resetCode, newPassword);
        showToast({ kind: 'success', title: '密码已重置', description: '请使用新密码登录。' });
        setAccount(email);
        setMode('login');
      } else {
        await login({ account, password });
      }
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : (reason as Error).message);
    }
  };

  const sendCode = async () => {
    if (!email) { setError('请先输入邮箱地址。'); return; }
    setSendingCode(true);
    setError('');
    try {
      await api.sendCode(email, 'reset');
      setCodeSent(true);
      showToast({ kind: 'success', title: '验证码已发送' });
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : (reason as Error).message);
    } finally {
      setSendingCode(false);
    }
  };

  const chooseMode = (next: Mode) => { setMode(next); setError(''); };
  const unavailable = (feature: string) => showToast({ kind: 'info', title: `${feature}暂未开放`, description: '当前请使用用户名或邮箱登录。' });

  return (
    <div className="relative flex min-h-full items-center justify-center overflow-x-hidden overflow-y-auto bg-surface-bright p-margin-mobile text-on-surface md:p-margin-desktop">
      <div className="pointer-events-none fixed inset-0 z-0 overflow-hidden">
        <div className="absolute -right-1/4 -top-1/4 h-1/2 w-1/2 rounded-full bg-primary-fixed-dim/20 blur-3xl" />
        <div className="absolute -bottom-1/4 -left-1/4 h-1/2 w-1/2 rounded-full bg-secondary-container/20 blur-3xl" />
      </div>

      <main className="relative z-10 w-full max-w-md overflow-hidden rounded-xl border border-outline-variant bg-surface-container-lowest shadow-sm">
        <div className="p-lg md:p-xl">
          <div className="mb-xl flex items-center justify-center gap-sm">
            <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary text-on-primary">
              <MaterialIcon filled>cloud</MaterialIcon>
            </span>
            <h1 className="text-display text-primary">CloudChunk</h1>
          </div>

          {mode !== 'forgot' ? (
            <div className="mb-lg flex border-b border-outline-variant">
              <button type="button" onClick={() => chooseMode('login')} className={`flex-1 border-b-2 pb-sm text-label-md transition-colors ${mode === 'login' ? 'border-primary text-primary' : 'border-transparent text-on-surface-variant hover:text-on-surface'}`}>登录</button>
              <button type="button" onClick={() => chooseMode('register')} className={`flex-1 border-b-2 pb-sm text-label-md transition-colors ${mode === 'register' ? 'border-primary text-primary' : 'border-transparent text-on-surface-variant hover:text-on-surface'}`}>注册</button>
            </div>
          ) : (
            <div className="mb-lg flex items-center border-b border-outline-variant pb-sm">
              <button type="button" onClick={() => chooseMode('login')} className="inline-flex items-center gap-xs text-label-md text-primary"><MaterialIcon className="text-[17px]">arrow_back</MaterialIcon>返回</button>
              <span className="mx-auto pr-12 text-label-md text-on-surface">重置密码</span>
            </div>
          )}

          <form className="space-y-md" onSubmit={submit}>
            {mode === 'login' && (
              <>
                <AuthField id="login-account" label="邮箱或用户名" value={account} onChange={setAccount} placeholder="请输入邮箱或用户名" autoComplete="username" />
                <div>
                  <div className="mb-xs flex items-center justify-between">
                    <label className="text-label-sm text-on-surface-variant" htmlFor="login-password">密码</label>
                    <button type="button" onClick={() => chooseMode('forgot')} className="text-label-sm text-primary transition-colors hover:text-primary-container">忘记密码？</button>
                  </div>
                  <input id="login-password" required value={password} onChange={(event) => setPassword(event.target.value)} className="stitch-focus h-10 w-full rounded border border-outline-variant bg-surface-bright px-sm text-body-md text-on-surface transition-all placeholder:text-outline-variant" placeholder="••••••••" type="password" autoComplete="current-password" />
                </div>
              </>
            )}

            {mode === 'register' && (
              <>
                <AuthField id="register-username" label="用户名" value={username} onChange={setUsername} placeholder="3–32 位字母、数字或下划线" autoComplete="username" />
                <AuthField id="register-email" label="邮箱" value={email} onChange={setEmail} placeholder="可选，用于找回密码" type="email" autoComplete="email" required={false} />
                <AuthField id="register-password" label="密码" value={password} onChange={setPassword} placeholder="请输入 8–72 位密码" type="password" autoComplete="new-password" />
              </>
            )}

            {mode === 'forgot' && (
              <>
                <AuthField id="reset-email" label="邮箱" value={email} onChange={setEmail} placeholder="请输入绑定邮箱" type="email" autoComplete="email" />
                <div className="grid grid-cols-[1fr_auto] items-end gap-sm">
                  <AuthField id="reset-code" label="验证码" value={resetCode} onChange={setResetCode} placeholder="请输入验证码" />
                  <button type="button" onClick={sendCode} disabled={sendingCode || codeSent} className="h-10 rounded border border-outline-variant px-md text-label-md text-primary hover:bg-surface-container-low disabled:opacity-50">{codeSent ? '已发送' : sendingCode ? '发送中…' : '发送验证码'}</button>
                </div>
                <AuthField id="reset-new-password" label="新密码" value={newPassword} onChange={setNewPassword} placeholder="请输入 8–72 位新密码" type="password" autoComplete="new-password" />
              </>
            )}

            {error && <div role="alert" className="rounded-lg border border-error/30 bg-error-container px-sm py-sm text-body-md text-on-error-container">{error}</div>}

            <div className="pt-sm">
              <button type="submit" disabled={authLoading} className="flex h-10 w-full items-center justify-center rounded bg-primary text-label-md text-on-primary shadow-sm transition-colors hover:bg-primary-container active:scale-[0.99] disabled:cursor-wait disabled:opacity-70">
                {authLoading && <MaterialIcon className="mr-xs animate-spin text-[18px]">progress_activity</MaterialIcon>}
                {mode === 'login' ? '登录' : mode === 'register' ? '创建账户' : '重置密码'}
              </button>
            </div>
          </form>

          {mode === 'login' && (
            <>
              <div className="my-lg flex items-center gap-sm text-label-sm text-on-surface-variant">
                <span className="h-px flex-1 bg-outline-variant" />
                <span>或使用以下方式</span>
                <span className="h-px flex-1 bg-outline-variant" />
              </div>
              <div className="grid grid-cols-2 gap-sm">
                <button type="button" onClick={() => unavailable('Google 登录')} className="flex h-10 items-center justify-center gap-xs rounded border border-outline-variant bg-surface-container-lowest text-label-md text-on-surface transition-colors hover:bg-surface-container-low"><MaterialIcon className="text-[18px]">account_circle</MaterialIcon>Google 登录</button>
                <button type="button" onClick={() => unavailable('企业 SSO')} className="flex h-10 items-center justify-center gap-xs rounded border border-outline-variant bg-surface-container-lowest text-label-md text-on-surface transition-colors hover:bg-surface-container-low"><MaterialIcon className="text-[18px]">business_center</MaterialIcon>企业 SSO</button>
              </div>
              <p className="mt-lg text-center text-[13px] leading-5 text-on-surface-variant">登录即表示你同意 CloudChunk 的 <button type="button" onClick={() => unavailable('服务条款')} className="text-primary hover:underline">服务条款</button> 与 <button type="button" onClick={() => unavailable('隐私政策')} className="text-primary hover:underline">隐私政策</button>。</p>
            </>
          )}
        </div>
      </main>
    </div>
  );
}

function AuthField({ id, label, value, onChange, placeholder, type = 'text', autoComplete, required = true }: { id: string; label: string; value: string; onChange: (value: string) => void; placeholder: string; type?: string; autoComplete?: string; required?: boolean }) {
  return (
    <div>
      <label className="mb-xs block text-label-sm text-on-surface-variant" htmlFor={id}>{label}</label>
      <input id={id} required={required} value={value} onChange={(event) => onChange(event.target.value)} className="stitch-focus h-10 w-full rounded border border-outline-variant bg-surface-bright px-sm text-body-md text-on-surface transition-all placeholder:text-outline-variant" placeholder={placeholder} type={type} autoComplete={autoComplete} />
    </div>
  );
}
