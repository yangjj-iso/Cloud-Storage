import { useEffect, useState } from 'react';
import { useAppStore } from '../store';
import { api } from '../lib/api';
import { Button } from './ui/Button';
import { Trash2, Download, Ban, CheckCircle, HardDrive, Settings, Users, FileText } from 'lucide-react';
import { formatBytes } from '../lib/utils';
import { downloadFile } from '../lib/download';

type Tab = 'users' | 'files' | 'settings';

export function AdminPanel() {
  const [tab, setTab] = useState<Tab>('users');
  const authUser = useAppStore((s) => s.authUser);

  if (authUser?.role !== 'admin') {
    return (
      <div className="flex items-center justify-center py-20 text-sm text-slate-500">
        仅管理员可访问此页面
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-2">
        <h2 className="text-lg font-semibold text-slate-900">管理后台</h2>
      </div>

      <div className="flex gap-2 border-b border-slate-200 pb-px">
        {([
          { id: 'users' as const, label: '用户管理', icon: Users },
          { id: 'files' as const, label: '文件管理', icon: FileText },
          { id: 'settings' as const, label: '系统设置', icon: Settings },
        ]).map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
              tab === id
                ? 'border-brand-600 text-brand-700'
                : 'border-transparent text-slate-500 hover:text-slate-700'
            }`}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </div>

      {tab === 'users' && <UsersTab />}
      {tab === 'files' && <FilesTab />}
      {tab === 'settings' && <SettingsTab />}
    </div>
  );
}

function UsersTab() {
  const {
    adminUsers, adminUsersTotal, adminLoading,
    refreshAdminUsers, adminDisableUser, adminEnableUser, adminAllocateSpace,
  } = useAppStore();
  const [allocUserId, setAllocUserId] = useState<number | null>(null);
  const [allocGB, setAllocGB] = useState('100');

  useEffect(() => { refreshAdminUsers(); }, [refreshAdminUsers]);

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <span className="text-sm font-medium text-slate-700">用户列表 ({adminUsersTotal})</span>
        <Button size="sm" variant="ghost" onClick={() => refreshAdminUsers()}>刷新</Button>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-xs text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">ID</th>
              <th className="px-4 py-2 text-left">用户名</th>
              <th className="px-4 py-2 text-left">昵称</th>
              <th className="px-4 py-2 text-left">邮箱</th>
              <th className="px-4 py-2 text-left">角色</th>
              <th className="px-4 py-2 text-left">空间</th>
              <th className="px-4 py-2 text-left">状态</th>
              <th className="px-4 py-2 text-left">操作</th>
            </tr>
          </thead>
          <tbody>
            {adminLoading ? (
              <tr><td colSpan={8} className="py-8 text-center text-slate-400">加载中...</td></tr>
            ) : adminUsers.length === 0 ? (
              <tr><td colSpan={8} className="py-8 text-center text-slate-400">暂无用户</td></tr>
            ) : adminUsers.map((u) => (
              <tr key={u.id} className="border-t border-slate-50 hover:bg-slate-50/50">
                <td className="px-4 py-2 text-slate-400">{u.id}</td>
                <td className="px-4 py-2 font-medium text-slate-800">{u.username}</td>
                <td className="px-4 py-2 text-slate-600">{u.nickname || '-'}</td>
                <td className="px-4 py-2 text-slate-600">{u.email || '-'}</td>
                <td className="px-4 py-2">
                  <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${
                    u.role === 'admin' ? 'bg-red-50 text-red-600' : 'bg-slate-100 text-slate-600'
                  }`}>
                    {u.role}
                  </span>
                </td>
                <td className="px-4 py-2 text-slate-600">
                  {formatBytes(u.usedBytes)} / {formatBytes(u.totalBytes)}
                  {allocUserId === u.id && (
                    <div className="mt-1 flex items-center gap-1">
                      <input
                        type="number"
                        value={allocGB}
                        onChange={(e) => setAllocGB(e.target.value)}
                        className="w-16 rounded border border-slate-200 px-1 py-0.5 text-xs"
                      />
                      <span className="text-xs text-slate-400">GB</span>
                      <button
                        className="rounded bg-brand-600 px-2 py-0.5 text-xs text-white"
                        onClick={async () => {
                          await adminAllocateSpace(u.id, Math.floor(parseFloat(allocGB) * 1073741824));
                          setAllocUserId(null);
                        }}
                      >确定</button>
                      <button className="text-xs text-slate-400" onClick={() => setAllocUserId(null)}>取消</button>
                    </div>
                  )}
                </td>
                <td className="px-4 py-2">
                  <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${
                    u.status === 1 ? 'bg-green-50 text-green-600' : 'bg-red-50 text-red-600'
                  }`}>
                    {u.status === 1 ? '正常' : '禁用'}
                  </span>
                </td>
                <td className="px-4 py-2">
                  <div className="flex items-center gap-1.5">
                    {u.role !== 'admin' && (
                      <>
                        <button
                          className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                          title="分配空间"
                          onClick={() => setAllocUserId(u.id)}
                        >
                          <HardDrive className="h-3.5 w-3.5" />
                        </button>
                        {u.status === 1 ? (
                          <button
                            className="rounded p-1 text-orange-400 hover:bg-orange-50"
                            title="禁用"
                            onClick={() => adminDisableUser(u.id)}
                          >
                            <Ban className="h-3.5 w-3.5" />
                          </button>
                        ) : (
                          <button
                            className="rounded p-1 text-green-500 hover:bg-green-50"
                            title="启用"
                            onClick={() => adminEnableUser(u.id)}
                          >
                            <CheckCircle className="h-3.5 w-3.5" />
                          </button>
                        )}
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function FilesTab() {
  const { adminFiles, adminFilesTotal, adminLoading, refreshAdminFiles, adminDeleteFile, showToast } = useAppStore();

  useEffect(() => { refreshAdminFiles(); }, [refreshAdminFiles]);

  return (
    <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <span className="text-sm font-medium text-slate-700">全部文件 ({adminFilesTotal})</span>
        <Button size="sm" variant="ghost" onClick={() => refreshAdminFiles()}>刷新</Button>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-xs text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">文件名</th>
              <th className="px-4 py-2 text-left">大小</th>
              <th className="px-4 py-2 text-left">所属用户</th>
              <th className="px-4 py-2 text-left">类型</th>
              <th className="px-4 py-2 text-left">上传时间</th>
              <th className="px-4 py-2 text-left">操作</th>
            </tr>
          </thead>
          <tbody>
            {adminLoading ? (
              <tr><td colSpan={6} className="py-8 text-center text-slate-400">加载中...</td></tr>
            ) : adminFiles.length === 0 ? (
              <tr><td colSpan={6} className="py-8 text-center text-slate-400">暂无文件</td></tr>
            ) : adminFiles.map((f) => (
              <tr key={f.id} className="border-t border-slate-50 hover:bg-slate-50/50">
                <td className="px-4 py-2 font-medium text-slate-800">{f.fileName}</td>
                <td className="px-4 py-2 text-slate-600">{f.isDir ? '-' : formatBytes(f.fileSize)}</td>
                <td className="px-4 py-2 text-slate-600">{f.username}</td>
                <td className="px-4 py-2 text-slate-600">{f.isDir ? '文件夹' : '文件'}</td>
                <td className="px-4 py-2 text-slate-400">{new Date(f.createdAt).toLocaleString('zh-CN')}</td>
                <td className="px-4 py-2">
                  <div className="flex items-center gap-1.5">
                    {!f.isDir && f.fileId && (
                      <button
                        type="button"
                        className="rounded p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                        title="下载"
                        onClick={async () => {
                          try {
                            await downloadFile({
                              url: api.adminDownloadUrl(f.fileId),
                              fileName: f.fileName,
                              fileSize: f.fileSize,
                            });
                          } catch (e) {
                            showToast({ kind: 'error', title: '下载失败', description: (e as Error).message });
                          }
                        }}
                      >
                        <Download className="h-3.5 w-3.5" />
                      </button>
                    )}
                    {!f.isDir && f.fileId && (
                      <button
                        className="rounded p-1 text-red-400 hover:bg-red-50"
                        title="删除"
                        onClick={() => {
                          if (confirm(`确定删除文件 "${f.fileName}" 吗？`)) {
                            adminDeleteFile(f.fileId);
                          }
                        }}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function SettingsTab() {
  const { adminSettings, refreshAdminSettings, adminSetSetting } = useAppStore();
  const [defaultSpace, setDefaultSpace] = useState('');
  const [emailTemplate, setEmailTemplate] = useState('');
  const [downloadLimit, setDownloadLimit] = useState('');

  useEffect(() => {
    refreshAdminSettings();
  }, [refreshAdminSettings]);

  useEffect(() => {
    for (const s of adminSettings) {
      if (s.key === 'default_space_bytes') setDefaultSpace(s.value);
      if (s.key === 'email_template_verify') setEmailTemplate(s.value);
      if (s.key === 'download_speed_limit') setDownloadLimit(s.value);
    }
  }, [adminSettings]);

  return (
    <div className="flex flex-col gap-4">
      <SettingCard
        title="用户注册初始空间"
        description="新用户注册后自动分配的存储空间大小（字节）"
        value={defaultSpace}
        placeholder="107374182400 (100GB)"
        onChange={setDefaultSpace}
        onSave={() => adminSetSetting('default_space_bytes', defaultSpace)}
      />
      <SettingCard
        title="邮箱验证码模板"
        description="发送验证码邮件时使用的模板内容，{code} 会被替换为验证码"
        value={emailTemplate}
        placeholder="您的验证码是 {code}，5分钟内有效。"
        onChange={setEmailTemplate}
        onSave={() => adminSetSetting('email_template_verify', emailTemplate)}
        textarea
      />
      <SettingCard
        title="下载限速"
        description="全局下载速度限制（字节/秒），0 表示不限速"
        value={downloadLimit}
        placeholder="0 (不限速)"
        onChange={setDownloadLimit}
        onSave={() => adminSetSetting('download_speed_limit', downloadLimit)}
      />
    </div>
  );
}

function SettingCard({
  title, description, value, placeholder, onChange, onSave, textarea,
}: {
  title: string;
  description: string;
  value: string;
  placeholder: string;
  onChange: (v: string) => void;
  onSave: () => void;
  textarea?: boolean;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="mb-1 text-sm font-semibold text-slate-800">{title}</div>
      <div className="mb-3 text-xs text-slate-500">{description}</div>
      {textarea ? (
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          rows={3}
          className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:border-brand-400 focus:outline-none"
        />
      ) : (
        <input
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:border-brand-400 focus:outline-none"
        />
      )}
      <div className="mt-2 flex justify-end">
        <Button size="sm" onClick={onSave}>保存</Button>
      </div>
    </div>
  );
}
