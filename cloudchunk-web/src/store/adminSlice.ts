import { api } from '../lib/api';
import type { AdminSlice, AppSlice, AppState } from './types';

export const createAdminSlice: AppSlice<AdminSlice> = (set, get) => ({
  adminUsers: [], adminUsersTotal: 0, adminUsersPage: 1, adminUsersSize: 20, adminUsersKeyword: '',
  adminFiles: [], adminFilesTotal: 0, adminSettings: [], adminLoading: false,
  refreshAdminUsers: async () => {
    set({ adminLoading: true });
    try {
      const page = await api.adminListUsers(get().adminUsersPage, get().adminUsersSize, get().adminUsersKeyword);
      set({ adminUsers: page.records, adminUsersTotal: page.total, adminLoading: false });
    } catch (error) {
      set({ adminLoading: false });
      get().showToast({ kind: 'error', title: '用户列表加载失败', description: (error as Error).message });
    }
  },
  setAdminUsersParams: (patch) => {
    set((state) => ({
      adminUsersPage: patch.page ?? state.adminUsersPage,
      adminUsersSize: patch.size ?? state.adminUsersSize,
      adminUsersKeyword: patch.keyword ?? state.adminUsersKeyword,
    }));
    void get().refreshAdminUsers();
  },
  refreshAdminFiles: async () => {
    set({ adminLoading: true });
    try { const page = await api.adminListFiles(1, 50); set({ adminFiles: page.records, adminFilesTotal: page.total, adminLoading: false }) }
    catch { set({ adminLoading: false }) }
  },
  refreshAdminSettings: async () => { try { set({ adminSettings: await api.adminGetSettings() }) } catch { /* ignore */ } },
  adminDisableUser: async (id) => runAdmin(get, () => api.adminDisableUser(id), '已禁用用户', '操作失败', 'users'),
  adminEnableUser: async (id) => runAdmin(get, () => api.adminEnableUser(id), '已启用用户', '操作失败', 'users'),
  adminAllocateSpace: async (id, bytes) => runAdmin(get, () => api.adminAllocateSpace(id, bytes), '空间已分配', '操作失败', 'users'),
  adminResetPassword: async (id, password) => runAdmin(get, () => api.adminResetPassword(id, password), '密码已重置', '重置失败', 'users'),
  adminSetRole: async (id, role) => runAdmin(get, () => api.adminSetRole(id, role), '角色已更新', '角色更新失败', 'users'),
  adminDeleteFile: async (id) => runAdmin(get, () => api.adminDeleteFile(id), '文件已删除', '删除失败', 'files'),
  adminSetSetting: async (key, value) => runAdmin(get, () => api.adminSetSetting(key, value), '设置已保存', '保存失败', 'settings'),
});

async function runAdmin(get: () => AppState, action: () => Promise<unknown>, success: string, failure: string,
                        refresh: 'users' | 'files' | 'settings') {
  try {
    await action(); get().showToast({ kind: 'success', title: success });
    if (refresh === 'users') void get().refreshAdminUsers();
    if (refresh === 'files') void get().refreshAdminFiles();
    if (refresh === 'settings') void get().refreshAdminSettings();
  } catch (error) { get().showToast({ kind: 'error', title: failure, description: (error as Error).message }) }
}
