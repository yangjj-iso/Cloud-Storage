import { api } from '../lib/api';
import type { AppSlice, FilesSlice } from './types';

export const createFilesSlice: AppSlice<FilesSlice> = (set, get) => ({
  files: [], filesTotal: 0, filesPage: 1, filesSize: 24,
  filesKeyword: '', filesMime: '', filesLoading: false, quota: null,
  refreshFiles: async () => {
    set({ filesLoading: true });
    try {
      const page = await api.files({ page: get().filesPage, size: get().filesSize,
        keyword: get().filesKeyword || undefined, mimePrefix: get().filesMime || undefined });
      set({ files: page.records, filesTotal: page.total, filesLoading: false });
    } catch (error) {
      set({ filesLoading: false });
      get().showToast({ kind: 'error', title: '文件列表加载失败', description: (error as Error).message });
    }
  },
  setFilesParams: (patch) => {
    set((state) => ({ filesPage: patch.page ?? state.filesPage, filesSize: patch.size ?? state.filesSize,
      filesKeyword: patch.keyword ?? state.filesKeyword, filesMime: patch.mime ?? state.filesMime }));
    void get().refreshFiles();
  },
  refreshQuota: async () => { try { set({ quota: await api.quota() }) } catch { /* ignore */ } },
  deleteFile: async (fileId) => {
    try { await api.deleteFile(fileId); get().showToast({ kind: 'success', title: '已删除' });
      void get().refreshFiles(); void get().refreshQuota(); }
    catch (error) { get().showToast({ kind: 'error', title: '删除失败', description: (error as Error).message }) }
  },
});
