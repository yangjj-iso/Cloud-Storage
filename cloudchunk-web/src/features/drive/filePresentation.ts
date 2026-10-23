export function fileKind(name: string, isDir = false) {
  if (isDir) return { category: 'folder', label: '文件夹', icon: 'folder', color: 'amber' };
  const extension = name.split('.').pop()?.toLowerCase() ?? '';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'heic', 'avif'].includes(extension))
    return { category: 'image', label: '图片', icon: 'image', color: 'purple' };
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(extension))
    return { category: 'video', label: '视频', icon: 'movie', color: 'rose' };
  if (['pdf', 'doc', 'docx', 'txt', 'md', 'ppt', 'pptx', 'xls', 'xlsx', 'csv'].includes(extension))
    return {
      category: 'document',
      label: '文档',
      icon: ['xls', 'xlsx', 'csv'].includes(extension) ? 'table_chart' : 'description',
      color: 'blue',
    };
  return {
    category: 'other',
    label: '其他',
    icon: ['zip', 'rar', '7z', 'tar', 'gz'].includes(extension)
      ? 'folder_zip'
      : ['mp3', 'wav', 'flac'].includes(extension)
        ? 'audio_file'
        : 'draft',
    color: 'mint',
  };
}

export function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
