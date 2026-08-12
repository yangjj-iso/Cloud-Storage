import { api } from '../lib/api';
import type { AppSlice, ShareSlice } from './types';

export const createShareSlice: AppSlice<ShareSlice> = (set, get) => ({
  shares: [], sharesTotal: 0, sharesLoading: false,
  refreshShares: async () => {
    set({ sharesLoading: true });
    try { const page = await api.listShares(1, 50); set({ shares: page.records, sharesTotal: page.total, sharesLoading: false }) }
    catch { set({ sharesLoading: false }) }
  },
  createShare: async (userFileId, expireDays) => {
    try { const result = await api.createShare(userFileId, expireDays);
      get().showToast({ kind: 'success', title: '分享链接已创建' }); void get().refreshShares(); return result; }
    catch (error) { get().showToast({ kind: 'error', title: '分享失败', description: (error as Error).message }); return null }
  },
  cancelShare: async (shareId) => {
    try { await api.cancelShare(shareId); get().showToast({ kind: 'success', title: '已取消分享' }); void get().refreshShares() }
    catch (error) { get().showToast({ kind: 'error', title: '取消失败', description: (error as Error).message }) }
  },
});
