import { useEffect, useState, type FormEvent } from 'react';
import { ArrowRight, Eye, EyeOff, Folder, Layers3, ShieldCheck, Sparkles } from 'lucide-react';
import { useAppStore } from '../store';
import { api } from '../lib/api';
import { Brand } from './ui/Brand';

type Mode = 'login' | 'register' | 'forgot';
export function LoginPage() {
  const login = useAppStore((s) => s.login);
  const register = useAppStore((s) => s.register);
  const showToast = useAppStore((s) => s.showToast);
  const [mode, setMode] = useState<Mode>('login');
  const [account, setAccount] = useState('');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [sending, setSending] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!cooldown) return;
    const timer = window.setTimeout(() => setCooldown((value) => value - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [cooldown]);
  const choose = (next: Mode) => {
    setMode(next);
    setError('');
    setPassword('');
    setVisible(false);
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setError('');
    setBusy(true);
    try {
      if (mode === 'register')
        await register({ username: username.trim(), email: email.trim() || undefined, password });
      else if (mode === 'forgot') {
        await api.resetPassword(email.trim(), code.trim(), password);
        setAccount(email.trim());
        choose('login');
        showToast({ kind: 'success', title: '密码已重置' });
      } else await login({ account: account.trim(), password });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  };
  const sendCode = async () => {
    if (!email.trim()) {
      setError('请先输入邮箱');
      return;
    }
    setSending(true);
    setError('');
    try {
      await api.sendCode(email.trim(), 'reset');
      setCooldown(60);
      showToast({ kind: 'success', title: '验证码已发送' });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '发送失败');
    } finally {
      setSending(false);
    }
  };
  return (
    <div className="auth-page">
      <aside className="auth-story">
        <Brand />
        <div className="auth-story-content">
          <span className="auth-kicker">YOUR PERSONAL CLOUD</span>
          <h1>
            每一份灵感，
            <br />
            都有归处。
          </h1>
          <p>你的文件、知识与下一次创造。</p>
          <div className="auth-art" aria-hidden="true">
            <span className="art-orbit" />
            <span className="art-orbit second" />
            <div className="art-card back">
              <Layers3 />
            </div>
            <div className="art-card front">
              <Folder size={70} strokeWidth={1} />
              <span>Everything, in its place.</span>
            </div>
            <span className="art-spark">
              <Sparkles size={24} />
            </span>
          </div>
        </div>
        <span className="auth-story-footer">
          <ShieldCheck size={16} />
          只属于你的文件空间
        </span>
      </aside>
      <main className="auth-main">
        <div className="auth-mobile-brand">
          <Brand />
        </div>
        <div className="auth-form-wrap">
          <span className="eyebrow">
            {mode === 'register' ? 'GET STARTED' : mode === 'forgot' ? 'RESET PASSWORD' : 'WELCOME BACK'}
          </span>
          <h2>{mode === 'register' ? '创建你的空间' : mode === 'forgot' ? '找回密码' : '欢迎回来'}</h2>
          <p className="auth-subtitle">
            {mode === 'register'
              ? '从第一份文件开始。'
              : mode === 'forgot'
                ? '使用绑定邮箱重置密码。'
                : '登录，继续你的创造。'}
          </p>
          {mode !== 'forgot' && (
            <div className="auth-tabs">
              <button type="button" aria-pressed={mode === 'login'} onClick={() => choose('login')}>
                登录
              </button>
              <button type="button" aria-pressed={mode === 'register'} onClick={() => choose('register')}>
                注册
              </button>
            </div>
          )}
          <form onSubmit={submit} className="auth-form">
            {mode === 'login' && (
              <Field
                id="login-account"
                label="邮箱或用户名"
                value={account}
                onChange={setAccount}
                autoComplete="username"
                placeholder="输入邮箱或用户名"
              />
            )}
            {mode === 'register' && (
              <>
                <Field
                  id="register-username"
                  label="用户名"
                  value={username}
                  onChange={setUsername}
                  autoComplete="username"
                  placeholder="3–32 位字母、数字或下划线"
                  pattern="[a-zA-Z0-9_]{3,32}"
                />
                <Field
                  id="register-email"
                  label="邮箱（可选）"
                  value={email}
                  onChange={setEmail}
                  autoComplete="email"
                  placeholder="用于找回密码"
                  type="email"
                  required={false}
                />
              </>
            )}
            {mode === 'forgot' && (
              <>
                <Field
                  id="reset-email"
                  label="邮箱"
                  value={email}
                  onChange={setEmail}
                  autoComplete="email"
                  placeholder="输入绑定邮箱"
                  type="email"
                />
                <div className="auth-code-row">
                  <Field
                    id="reset-code"
                    label="验证码"
                    value={code}
                    onChange={setCode}
                    placeholder="邮箱验证码"
                  />
                  <button type="button" disabled={sending || cooldown > 0} onClick={() => void sendCode()}>
                    {cooldown ? `${cooldown}s 后重发` : sending ? '发送中…' : '发送验证码'}
                  </button>
                </div>
              </>
            )}
            <div className="auth-field">
              <div className="auth-field-label">
                <label htmlFor="auth-password">{mode === 'forgot' ? '新密码' : '密码'}</label>
                {mode === 'login' && (
                  <button type="button" onClick={() => choose('forgot')}>
                    忘记密码？
                  </button>
                )}
              </div>
              <div className="auth-password">
                <input
                  id="auth-password"
                  required
                  minLength={mode === 'login' ? undefined : 8}
                  maxLength={72}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  type={visible ? 'text' : 'password'}
                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                  placeholder={mode === 'login' ? '输入密码' : '至少 8 位密码'}
                />
                <button
                  type="button"
                  aria-label={visible ? '隐藏密码' : '显示密码'}
                  onClick={() => setVisible((v) => !v)}
                >
                  {visible ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
            </div>
            {error && (
              <p className="inline-error" role="alert">
                {error}
              </p>
            )}
            <button className="auth-submit" type="submit" disabled={busy}>
              {busy ? '请稍候…' : mode === 'register' ? '创建账户' : mode === 'forgot' ? '重置密码' : '登录'}
              <ArrowRight size={18} />
            </button>
            {mode === 'forgot' && (
              <button type="button" className="auth-back" onClick={() => choose('login')}>
                返回登录
              </button>
            )}
          </form>
          <p className="auth-footnote">CloudForge · 为你的下一次创造留一点空间。</p>
        </div>
      </main>
    </div>
  );
}
function Field({
  id,
  label,
  value,
  onChange,
  placeholder,
  autoComplete,
  type = 'text',
  required = true,
  pattern,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  autoComplete?: string;
  type?: string;
  required?: boolean;
  pattern?: string;
}) {
  return (
    <div className="auth-field">
      <label htmlFor={id}>{label}</label>
      <input
        id={id}
        required={required}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        autoComplete={autoComplete}
        type={type}
        pattern={pattern}
      />
    </div>
  );
}
