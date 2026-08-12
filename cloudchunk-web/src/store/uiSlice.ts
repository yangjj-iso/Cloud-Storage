import { uniqId } from '../lib/utils';
import type { AppSlice, UiSlice } from './types';

export const createUiSlice: AppSlice<UiSlice> = (set, get) => ({
  view: 'upload', adminTab: 'users', toasts: [], activeFileId: null,
  setView: (view) => set({ view }),
  setAdminTab: (adminTab) => set({ adminTab, view: 'admin' }),
  showToast: ({ kind, title, description, ttl = 4000 }) => {
    const id = uniqId();
    set((state) => ({ toasts: [...state.toasts, { id, kind, title, description, ttl }] }));
    if (ttl > 0) setTimeout(() => get().dismissToast(id), ttl);
  },
  dismissToast: (id) => set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) })),
  setActiveFile: (activeFileId) => set({ activeFileId }),
});
