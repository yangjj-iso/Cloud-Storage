import { useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import { downloadFile } from '../lib/download';
import { formatZhDate, roleLabel } from '../lib/locale';
import { cn, formatBytes } from '../lib/utils';
import { useAppStore } from '../store';
import type { AdminUserItem } from '../types';
import { MaterialIcon } from './ui/MaterialIcon';
import { SystemHealth } from './SystemHealth';
import { AiFeedbackReviewAdminPanel } from './AiFeedbackReviewAdminPanel';

export function AdminPanel() {
  const authUser = useAppStore((state) => state.authUser);
  const adminTab = useAppStore((state) => state.adminTab);
  const setAdminTab = useAppStore((state) => state.setAdminTab);
  const keyword = useAppStore((state) => state.adminUsersKeyword);
  const setUsersParams = useAppStore((state) => state.setAdminUsersParams);
  if (authUser?.role !== 'admin') {
    return (
      <div className="flex min-h-[420px] items-center justify-center p-xl text-body-md text-on-surface-variant">
        <MaterialIcon className="mr-sm text-error">lock</MaterialIcon>仅管理员可以访问此页面
      </div>
    );
  }
  return (
    <>
      <nav className="admin-navigation" aria-label="管理导航">
        {(
          [
            ['users', '用户管理', 'group'],
            ['files', '文件管理', 'folder_managed'],
            ['settings', '系统设置', 'settings_applications'],
            ['health', '依赖状态', 'monitor_heart'],
          ] as const
        ).map(([id, label, icon]) => (
          <button
            key={id}
            type="button"
            aria-current={adminTab === id ? 'page' : undefined}
            onClick={() => setAdminTab(id)}
          >
            <MaterialIcon>{icon}</MaterialIcon>
            {label}
          </button>
        ))}
        {adminTab === 'users' && (
          <label className="drive-search">
            <MaterialIcon>search</MaterialIcon>
            <input
              aria-label="搜索用户"
              placeholder="搜索用户名或邮箱…"
              value={keyword}
              onChange={(event) => setUsersParams({ keyword: event.target.value, page: 1 })}
            />
          </label>
        )}
      </nav>
      {adminTab === 'health' ? (
        <>
          <SystemHealth />
          <AiFeedbackReviewAdminPanel />
        </>
      ) : adminTab === 'files' ? (
        <AdminFiles />
      ) : adminTab === 'settings' ? (
        <AdminSettings />
      ) : (
        <AdminUsers />
      )}
    </>
  );
}

type UserDialog = 'quota' | 'role' | 'password';

function AdminUsers() {
  const {
    adminUsers,
    adminUsersTotal,
    adminUsersPage,
    adminUsersSize,
    adminLoading,
    refreshAdminUsers,
    setAdminUsersParams,
    adminDisableUser,
    adminEnableUser,
    adminAllocateSpace,
    adminResetPassword,
    adminSetRole,
  } = useAppStore();
  const [dialog, setDialog] = useState<UserDialog | null>(null);
  const [selectedUser, setSelectedUser] = useState<AdminUserItem | null>(null);
  const [allocation, setAllocation] = useState('100');
  const [role, setRole] = useState<'user' | 'admin'>('user');
  const [password, setPassword] = useState('');

  useEffect(() => {
    void refreshAdminUsers();
  }, [refreshAdminUsers]);

  const pageCount = Math.max(1, Math.ceil(adminUsersTotal / adminUsersSize));
  const activeAdmins = adminUsers.filter((user) => user.role === 'admin' && user.status === 1).length;
  const allocated = adminUsers.reduce((sum, user) => sum + user.totalBytes, 0);
  const used = adminUsers.reduce((sum, user) => sum + user.usedBytes, 0);
  const allocationPct = allocated > 0 ? Math.min(100, (used / allocated) * 100) : 0;

  const openDialog = (next: UserDialog, user: AdminUserItem) => {
    setSelectedUser(user);
    setDialog(next);
    setAllocation(String(Math.max(1, Math.round(user.totalBytes / 1024 ** 3))));
    setRole(user.role === 'admin' ? 'admin' : 'user');
    setPassword('');
  };

  const submitDialog = async () => {
    if (!selectedUser || !dialog) return;
    if (dialog === 'quota')
      await adminAllocateSpace(selectedUser.id, Math.max(0, Math.floor(Number(allocation) * 1024 ** 3)));
    if (dialog === 'role') await adminSetRole(selectedUser.id, role);
    if (dialog === 'password') await adminResetPassword(selectedUser.id, password);
    setDialog(null);
    setSelectedUser(null);
  };

  return (
    <div className="p-margin-mobile md:p-margin-desktop">
      <AdminHeading
        title="用户管理"
        description="管理用户状态、角色、密码与存储配额。"
        onRefresh={refreshAdminUsers}
        loading={adminLoading}
      />

      <div className="mb-xl grid grid-cols-1 gap-gutter md:grid-cols-4">
        <StatCard
          title="用户总数"
          icon="group"
          value={adminUsersTotal.toLocaleString()}
          foot="来自后端用户目录"
        />
        <StatCard
          title="本页管理员"
          icon="admin_panel_settings"
          value={activeAdmins.toLocaleString()}
          foot={`当前第 ${adminUsersPage} 页`}
        />
        <section className="relative overflow-hidden rounded-xl border border-outline-variant bg-surface-container-lowest p-md shadow-sm md:col-span-2">
          <div className="flex items-start justify-between">
            <span className="text-label-md text-on-surface-variant">本页配额使用情况</span>
            <span className="flex h-8 w-8 items-center justify-center rounded-xl bg-primary-fixed text-primary">
              <MaterialIcon className="text-[18px]">storage</MaterialIcon>
            </span>
          </div>
          <div className="mt-lg flex items-end gap-sm">
            <strong className="text-[36px] leading-none tracking-tight text-on-surface">
              {formatBytes(used)}
            </strong>
            <span className="pb-1 text-body-lg text-on-surface-variant">/ {formatBytes(allocated)}</span>
          </div>
          <div className="mt-lg h-1.5 w-full rounded-full bg-outline-variant">
            <div className="relative h-full rounded-full bg-primary" style={{ width: `${allocationPct}%` }} />
          </div>
          <MaterialIcon className="pointer-events-none absolute -bottom-5 -right-1 text-[100px] text-surface-container">
            cloud
          </MaterialIcon>
        </section>
      </div>

      <section className="overflow-hidden rounded-xl border border-outline-variant bg-surface-container-lowest shadow-sm">
        <div className="flex h-[58px] items-center justify-between border-b border-outline-variant px-md text-label-md text-on-surface-variant">
          <span>第 {adminUsersPage} 页</span>
          <span>
            显示 {adminUsers.length} 条，共 {adminUsersTotal.toLocaleString()} 条
          </span>
        </div>
        <div className="overflow-x-auto">
          <div className="min-w-[900px]">
            <div className="grid grid-cols-[120px_2fr_100px_190px_110px_150px] items-center gap-sm border-b border-outline-variant bg-surface-container-low px-md py-sm text-label-md tracking-wider text-on-surface-variant">
              <span>用户编号</span>
              <span>用户信息</span>
              <span>角色</span>
              <span>存储使用</span>
              <span>状态</span>
              <span className="text-right">操作</span>
            </div>
            {adminLoading && adminUsers.length === 0 ? (
              <div className="flex h-32 items-center justify-center text-body-md text-outline">
                <MaterialIcon className="mr-sm animate-spin">progress_activity</MaterialIcon>正在加载用户…
              </div>
            ) : adminUsers.length === 0 ? (
              <div className="flex h-32 items-center justify-center text-body-md text-outline">
                没有符合条件的用户
              </div>
            ) : (
              adminUsers.map((user) => {
                const usage =
                  user.totalBytes > 0 ? Math.min(100, (user.usedBytes / user.totalBytes) * 100) : 0;
                const disabled = user.status !== 1;
                return (
                  <div
                    key={user.id}
                    className={cn(
                      'grid min-h-[66px] grid-cols-[120px_2fr_100px_190px_110px_150px] items-center gap-sm border-b border-surface-container px-md py-sm text-body-md last:border-b-0 hover:bg-surface-bright',
                      disabled && 'opacity-60',
                    )}
                  >
                    <span className="font-mono text-label-sm text-on-surface-variant">
                      US-{String(user.id).padStart(6, '0')}
                    </span>
                    <div className="flex min-w-0 items-center gap-sm">
                      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-secondary-container text-label-md font-bold text-on-secondary-container">
                        {initials(user.nickname || user.username)}
                      </span>
                      <span className="min-w-0">
                        <strong className="block truncate font-medium text-on-surface">
                          {user.nickname || user.username}
                        </strong>
                        <span className="block truncate text-on-surface-variant">
                          {user.email || '未绑定邮箱'}
                        </span>
                      </span>
                    </div>
                    <span
                      className={cn(
                        'inline-flex w-fit rounded border px-sm py-xs text-label-sm',
                        user.role === 'admin'
                          ? 'border-primary-fixed-dim bg-primary-fixed text-primary'
                          : 'border-outline-variant bg-surface-container-low text-on-surface-variant',
                      )}
                    >
                      {roleLabel(user.role)}
                    </span>
                    <div>
                      <div className="mb-xs flex justify-between text-label-sm text-on-surface-variant">
                        <span>{formatBytes(user.usedBytes)}</span>
                        <span>{formatBytes(user.totalBytes)}</span>
                      </div>
                      <div className="h-1 w-full rounded-full bg-outline-variant">
                        <div
                          className={cn('h-full rounded-full', usage > 85 ? 'bg-amber-500' : 'bg-primary')}
                          style={{ width: `${usage}%` }}
                        />
                      </div>
                    </div>
                    <span
                      className={cn(
                        'inline-flex items-center gap-xs',
                        disabled ? 'text-outline' : 'text-emerald-600',
                      )}
                    >
                      <span
                        className={cn(
                          'h-2 w-2 rounded-full',
                          disabled ? 'bg-outline-variant' : 'bg-emerald-500',
                        )}
                      />
                      {disabled ? '已禁用' : '正常'}
                    </span>
                    <div className="flex justify-end gap-xs">
                      <AdminAction
                        title="分配存储空间"
                        icon="data_usage"
                        onClick={() => openDialog('quota', user)}
                      />
                      <AdminAction
                        title="设置角色"
                        icon="manage_accounts"
                        onClick={() => openDialog('role', user)}
                      />
                      <AdminAction
                        title="重置密码"
                        icon="password"
                        onClick={() => openDialog('password', user)}
                      />
                      {disabled ? (
                        <AdminAction
                          title="启用用户"
                          icon="check_circle"
                          onClick={() => adminEnableUser(user.id)}
                        />
                      ) : (
                        <AdminAction
                          title="禁用用户"
                          icon="block"
                          onClick={() => adminDisableUser(user.id)}
                        />
                      )}
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
        <div className="flex h-12 items-center justify-between border-t border-outline-variant bg-surface-bright px-md text-label-md text-on-surface-variant">
          <span>
            第 {adminUsersPage} / {pageCount} 页
          </span>
          <div className="flex gap-sm">
            <button
              type="button"
              disabled={adminUsersPage <= 1}
              onClick={() => setAdminUsersParams({ page: adminUsersPage - 1 })}
              className="stitch-icon-button disabled:opacity-40"
              aria-label="上一页"
            >
              <MaterialIcon>chevron_left</MaterialIcon>
            </button>
            <button
              type="button"
              disabled={adminUsersPage >= pageCount}
              onClick={() => setAdminUsersParams({ page: adminUsersPage + 1 })}
              className="stitch-icon-button disabled:opacity-40"
              aria-label="下一页"
            >
              <MaterialIcon>chevron_right</MaterialIcon>
            </button>
          </div>
        </div>
      </section>

      {dialog && selectedUser && (
        <div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-inverse-surface/30 p-md"
          onClick={() => setDialog(null)}
        >
          <div
            className="w-full max-w-sm rounded-xl border border-outline-variant bg-white p-lg shadow-ambient"
            onClick={(event) => event.stopPropagation()}
          >
            <h2 className="text-headline-md">
              {dialog === 'quota' ? '分配存储空间' : dialog === 'role' ? '设置用户角色' : '重置用户密码'}
            </h2>
            <p className="mt-xs text-body-md text-on-surface-variant">用户：{selectedUser.username}</p>
            {dialog === 'quota' && (
              <label className="mt-lg block text-label-sm text-on-surface-variant">
                总配额（GB）
                <input
                  autoFocus
                  type="number"
                  min="0"
                  value={allocation}
                  onChange={(event) => setAllocation(event.target.value)}
                  className="stitch-focus mt-xs h-10 w-full rounded border border-outline-variant px-sm text-body-md"
                />
              </label>
            )}
            {dialog === 'role' && (
              <label className="mt-lg block text-label-sm text-on-surface-variant">
                角色
                <select
                  value={role}
                  onChange={(event) => setRole(event.target.value as 'user' | 'admin')}
                  className="stitch-focus mt-xs h-10 w-full rounded border border-outline-variant bg-white px-sm text-body-md"
                >
                  <option value="user">普通用户</option>
                  <option value="admin">管理员</option>
                </select>
              </label>
            )}
            {dialog === 'password' && (
              <label className="mt-lg block text-label-sm text-on-surface-variant">
                新密码
                <input
                  autoFocus
                  type="password"
                  minLength={8}
                  maxLength={72}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  className="stitch-focus mt-xs h-10 w-full rounded border border-outline-variant px-sm text-body-md"
                  placeholder="请输入 8–72 位新密码"
                />
              </label>
            )}
            <div className="mt-lg flex justify-end gap-sm">
              <button
                type="button"
                onClick={() => setDialog(null)}
                className="h-10 rounded px-md text-label-md text-on-surface-variant"
              >
                取消
              </button>
              <button
                type="button"
                disabled={dialog === 'password' && password.length < 8}
                onClick={submitDialog}
                className="h-10 rounded bg-primary px-md text-label-md text-white disabled:opacity-50"
              >
                确认保存
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function AdminFiles() {
  const { adminFiles, adminFilesTotal, adminLoading, refreshAdminFiles, adminDeleteFile, showToast } =
    useAppStore();
  useEffect(() => {
    void refreshAdminFiles();
  }, [refreshAdminFiles]);
  return (
    <div className="p-margin-mobile md:p-margin-desktop">
      <AdminHeading
        title="文件管理"
        description="查看并管理所有用户账户中的文件。"
        onRefresh={refreshAdminFiles}
        loading={adminLoading}
      />
      <section className="overflow-hidden rounded-xl border border-outline-variant bg-white shadow-sm">
        <div className="grid grid-cols-[2fr_110px_150px_110px_170px_90px] gap-md border-b border-outline-variant bg-surface-container-low px-md py-sm text-label-md tracking-wider text-on-surface-variant">
          <span>文件</span>
          <span>大小</span>
          <span>所有者</span>
          <span>类型</span>
          <span>创建时间</span>
          <span className="text-right">操作</span>
        </div>
        {adminFiles.length === 0 ? (
          <div className="flex h-32 items-center justify-center text-body-md text-outline">
            {adminLoading ? '正在加载文件…' : '暂无文件'}
          </div>
        ) : (
          adminFiles.map((file) => (
            <div
              key={file.id}
              className="grid min-h-[58px] grid-cols-[2fr_110px_150px_110px_170px_90px] items-center gap-md border-b border-surface-container px-md text-body-md last:border-0 hover:bg-surface-bright"
            >
              <span className="truncate font-medium">{file.fileName}</span>
              <span className="text-on-surface-variant">{file.isDir ? '—' : formatBytes(file.fileSize)}</span>
              <span className="truncate text-on-surface-variant">{file.username}</span>
              <span className="text-on-surface-variant">{file.isDir ? '文件夹' : '文件'}</span>
              <span className="text-on-surface-variant">{formatZhDate(file.createdAt, true)}</span>
              <span className="flex justify-end gap-xs">
                {!file.isDir && (
                  <>
                    <AdminAction
                      title="下载"
                      icon="download"
                      onClick={async () => {
                        try {
                          await downloadFile({
                            url: api.adminDownloadUrl(file.fileId),
                            fileName: file.fileName,
                            fileSize: file.fileSize,
                          });
                        } catch (reason) {
                          showToast({
                            kind: 'error',
                            title: '下载失败',
                            description: (reason as Error).message,
                          });
                        }
                      }}
                    />
                    <AdminAction
                      title="删除"
                      icon="delete"
                      onClick={() => {
                        if (confirm(`确定永久删除“${file.fileName}”吗？`)) adminDeleteFile(file.fileId);
                      }}
                    />
                  </>
                )}
              </span>
            </div>
          ))
        )}
        <div className="border-t border-outline-variant bg-surface-bright px-md py-sm text-label-md text-on-surface-variant">
          共 {adminFilesTotal.toLocaleString()} 个文件节点
        </div>
      </section>
    </div>
  );
}

function AdminSettings() {
  const { adminSettings, adminLoading, refreshAdminSettings, adminSetSetting } = useAppStore();
  const [values, setValues] = useState<Record<string, string>>({});
  useEffect(() => {
    void refreshAdminSettings();
  }, [refreshAdminSettings]);
  useEffect(() => {
    setValues(Object.fromEntries(adminSettings.map((setting) => [setting.key, setting.value])));
  }, [adminSettings]);
  const cards = useMemo(
    () => [
      {
        key: 'default_space_bytes',
        title: '默认用户存储空间',
        description: '新注册用户自动获得的存储配额，单位为字节。',
        placeholder: '107374182400',
      },
      {
        key: 'email_template_verify',
        title: '验证码邮件模板',
        description: '使用 {code} 表示验证码插入位置。',
        placeholder: '您的验证码是 {code}。',
        textarea: true,
      },
      {
        key: 'download_speed_limit',
        title: '全局下载限速',
        description: '最大下载速度，单位为字节/秒；0 表示不限速。',
        placeholder: '0',
      },
    ],
    [],
  );
  return (
    <div className="p-margin-mobile md:p-margin-desktop">
      <AdminHeading
        title="系统设置"
        description="配置默认存储空间、通知模板和传输策略。"
        onRefresh={refreshAdminSettings}
        loading={adminLoading}
      />
      <div className="grid gap-gutter lg:grid-cols-2">
        {cards.map((card) => (
          <section
            key={card.key}
            className="rounded-xl border border-outline-variant bg-white p-lg shadow-sm"
          >
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-headline-md">{card.title}</h2>
                <p className="mt-xs text-body-md text-on-surface-variant">{card.description}</p>
              </div>
              <MaterialIcon className="text-primary">settings</MaterialIcon>
            </div>
            {card.textarea ? (
              <textarea
                rows={4}
                value={values[card.key] || ''}
                onChange={(event) => setValues((current) => ({ ...current, [card.key]: event.target.value }))}
                placeholder={card.placeholder}
                className="stitch-focus mt-lg w-full rounded-xl border border-outline-variant p-sm text-body-md"
              />
            ) : (
              <input
                value={values[card.key] || ''}
                onChange={(event) => setValues((current) => ({ ...current, [card.key]: event.target.value }))}
                placeholder={card.placeholder}
                className="stitch-focus mt-lg h-10 w-full rounded border border-outline-variant px-sm text-body-md"
              />
            )}
            <div className="mt-md flex justify-end">
              <button
                type="button"
                onClick={() => adminSetSetting(card.key, values[card.key] || '')}
                className="h-10 rounded bg-primary px-md text-label-md text-white"
              >
                保存设置
              </button>
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}

function AdminHeading({
  title,
  description,
  onRefresh,
  loading,
}: {
  title: string;
  description: string;
  onRefresh: () => void;
  loading: boolean;
}) {
  return (
    <div className="mb-xl flex items-center justify-between">
      <div>
        <h1 className="text-headline-lg">{title}</h1>
        <p className="mt-xs text-body-md text-on-surface-variant">{description}</p>
      </div>
      <button
        type="button"
        onClick={onRefresh}
        className="flex h-10 items-center gap-sm rounded border border-outline-variant bg-white px-md text-label-md"
      >
        <MaterialIcon className={cn('text-[18px]', loading && 'animate-spin')}>refresh</MaterialIcon>刷新数据
      </button>
    </div>
  );
}

function StatCard({
  title,
  icon,
  value,
  foot,
}: {
  title: string;
  icon: string;
  value: string;
  foot: string;
}) {
  return (
    <section className="rounded-xl border border-outline-variant bg-surface-container-lowest p-md shadow-sm">
      <div className="flex items-start justify-between">
        <span className="text-label-md text-on-surface-variant">{title}</span>
        <span className="flex h-8 w-8 items-center justify-center rounded-xl bg-primary-fixed text-primary">
          <MaterialIcon className="text-[18px]">{icon}</MaterialIcon>
        </span>
      </div>
      <strong className="mt-lg block text-[36px] leading-none tracking-tight text-on-surface">{value}</strong>
      <div className="mt-sm text-label-sm text-on-surface-variant">{foot}</div>
    </section>
  );
}

function AdminAction({ title, icon, onClick }: { title: string; icon: string; onClick: () => void }) {
  return (
    <button
      type="button"
      title={title}
      aria-label={title}
      onClick={onClick}
      className="stitch-icon-button h-8 w-8"
    >
      <MaterialIcon className="text-[18px]">{icon}</MaterialIcon>
    </button>
  );
}

function initials(value: string) {
  return value
    .split(/\s+/)
    .map((part) => part[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();
}
