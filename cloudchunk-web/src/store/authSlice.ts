import { api, clearStoredAuth, getStoredAuth, saveStoredAuth } from '../lib/api';
import type { AppSlice, AuthSlice } from './types';

export const createAuthSlice: AppSlice<AuthSlice> = (set, get) => ({
  authChecked: false,
  authLoading: false,
  authUser: null,

  initAuth: async () => {
    const stored = getStoredAuth();
    if (!stored?.token) { set({ authChecked: true, authUser: null }); return }
    set({ authUser: stored.user, authChecked: true });
    try { set({ authUser: await api.me() }) }
    catch { clearStoredAuth(); set({ authUser: null, quota: null, files: [], uploads: [] }) }
  },

  login: async (request) => {
    set({ authLoading: true });
    try {
      const auth = await api.login(request); saveStoredAuth(auth);
      set({ authUser: auth.user, authChecked: true, authLoading: false });
      await Promise.all([get().refreshQuota(), get().refreshFiles()]);
    } catch (error) { set({ authLoading: false }); throw error }
  },

  register: async (request) => {
    set({ authLoading: true });
    try {
      const auth = await api.register(request); saveStoredAuth(auth);
      set({ authUser: auth.user, authChecked: true, authLoading: false });
      await Promise.all([get().refreshQuota(), get().refreshFiles()]);
    } catch (error) { set({ authLoading: false }); throw error }
  },

  logout: async () => {
    try { await api.logout() } catch { /* local logout still wins */ }
    clearStoredAuth();
    set({ authUser: null, quota: null, files: [], uploads: [], activeFileId: null, view: 'upload' });
  },
});
