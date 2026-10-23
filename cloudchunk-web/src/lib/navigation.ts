import type { View } from '../store';

export const productName = 'CloudForge';
export const navigation: Array<{ id: View; label: string; icon: string }> = [
  { id: 'drive', label: '我的云盘', icon: 'folder' },
  { id: 'files', label: '全部文件', icon: 'list_alt' },
  { id: 'upload', label: '上传中心', icon: 'cloud_upload' },
  { id: 'ai', label: 'AI 工作台', icon: 'auto_awesome' },
  { id: 'shares', label: '我的分享', icon: 'share' },
  { id: 'recycle', label: '回收站', icon: 'delete' },
];

export function viewLabel(view: View) {
  return navigation.find((item) => item.id === view)?.label ?? '管理后台';
}
