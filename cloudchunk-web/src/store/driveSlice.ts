import { api } from '../lib/api';
import type { AppSlice, DriveSlice } from './types';

export const createDriveSlice: AppSlice<DriveSlice> = (set, get) => ({
  driveFiles: [], driveParentId: 0, driveLoading: false, recycleFiles: [], recycleLoading: false,
  refreshDrive: async (parentId) => {
    const pid = parentId ?? get().driveParentId; set({ driveLoading: true });
    try { set({ driveFiles: await api.driveList(pid), driveParentId: pid, driveLoading: false }) }
    catch (error) { set({ driveLoading: false }); get().showToast({ kind: 'error', title: '加载失败', description: (error as Error).message }) }
  },
  driveMkdir: async (parentId, name) => runDriveAction(get, () => api.driveMkdir(parentId, name), '目录已创建', '创建失败'),
  driveRename: async (id, name) => runDriveAction(get, () => api.driveRename(id, name), '已重命名', '重命名失败'),
  driveMove: async (id, parentId) => runDriveAction(get, () => api.driveMove(id, parentId), '已移动', '移动失败'),
  driveDelete: async (id) => {
    await runDriveAction(get, () => api.driveDelete(id), '已移到回收站', '删除失败');
    void get().refreshQuota();
  },
  refreshRecycle: async () => {
    set({ recycleLoading: true });
    try { set({ recycleFiles: await api.recycleList(), recycleLoading: false }) }
    catch { set({ recycleLoading: false }) }
  },
  recycleRestore: async (id) => {
    try { await api.recycleRestore(id); get().showToast({ kind: 'success', title: '已还原' });
      void get().refreshRecycle(); void get().refreshQuota(); }
    catch (error) { get().showToast({ kind: 'error', title: '还原失败', description: (error as Error).message }) }
  },
  recycleHardDelete: async (id) => {
    try { await api.recycleHardDelete(id); get().showToast({ kind: 'success', title: '已彻底删除' }); void get().refreshRecycle(); }
    catch (error) { get().showToast({ kind: 'error', title: '删除失败', description: (error as Error).message }) }
  },
});

async function runDriveAction(get: () => import('./types').AppState, action: () => Promise<unknown>, success: string, failure: string) {
  try { await action(); get().showToast({ kind: 'success', title: success }); void get().refreshDrive() }
  catch (error) { get().showToast({ kind: 'error', title: failure, description: (error as Error).message }) }
}
