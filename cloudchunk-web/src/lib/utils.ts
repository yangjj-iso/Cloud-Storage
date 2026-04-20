import clsx, { type ClassValue } from 'clsx';

export function cn(...inputs: ClassValue[]): string {
  return clsx(inputs);
}

export function formatBytes(bytes: number, decimals = 2): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(k)), sizes.length - 1);
  const value = bytes / Math.pow(k, i);
  return `${value.toFixed(i === 0 ? 0 : decimals)} ${sizes[i]}`;
}

export function formatSpeed(bps: number): string {
  return `${formatBytes(bps)}/s`;
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  return `${m}m${rs}s`;
}

export function formatDate(iso?: string | null): string {
  if (!iso) return '—';
  try {
    const d = new Date(iso);
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  } catch {
    return iso;
  }
}

export function guessChunkSize(fileSize: number): number {
  // between 2MB and 20MB, aim for ~50-200 chunks
  if (fileSize < 50 * 1024 * 1024) return 2 * 1024 * 1024;
  if (fileSize < 500 * 1024 * 1024) return 5 * 1024 * 1024;
  if (fileSize < 2 * 1024 * 1024 * 1024) return 10 * 1024 * 1024;
  return 20 * 1024 * 1024;
}

export function mimeCategory(mime: string | undefined): 'image' | 'video' | 'audio' | 'doc' | 'archive' | 'other' {
  if (!mime) return 'other';
  if (mime.startsWith('image/')) return 'image';
  if (mime.startsWith('video/')) return 'video';
  if (mime.startsWith('audio/')) return 'audio';
  if (mime.includes('zip') || mime.includes('rar') || mime.includes('7z') || mime.includes('tar')) return 'archive';
  if (
    mime.startsWith('text/') ||
    mime.includes('pdf') ||
    mime.includes('officedocument') ||
    mime.includes('msword') ||
    mime.includes('excel') ||
    mime.includes('powerpoint')
  )
    return 'doc';
  return 'other';
}

export function uniqId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
