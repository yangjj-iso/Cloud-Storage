import { create } from 'zustand';
import { createAdminSlice } from './store/adminSlice';
import { createAuthSlice } from './store/authSlice';
import { createDriveSlice } from './store/driveSlice';
import { createFilesSlice } from './store/filesSlice';
import { createShareSlice } from './store/shareSlice';
import { createUiSlice } from './store/uiSlice';
import { createUploadSlice } from './store/uploadSlice';
import type { AppState } from './store/types';

export type { AdminTab, Toast, ToastKind, View } from './store/types';

export const useAppStore = create<AppState>()((...args) => ({
  ...createAuthSlice(...args),
  ...createUiSlice(...args),
  ...createUploadSlice(...args),
  ...createFilesSlice(...args),
  ...createDriveSlice(...args),
  ...createShareSlice(...args),
  ...createAdminSlice(...args),
}));
