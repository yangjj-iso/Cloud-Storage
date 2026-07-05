import { getStoredAuth } from './api';

/**
 * 上传进度 WebSocket 客户端。
 *
 * 用法：
 *   const ws = connectUploadWs(fileId, (msg) => { ... });
 *   // 上传完成后
 *   ws.close();
 */

export interface WsProgressMessage {
  fileId: string;
  chunkIndex: number;
  etag: string;
  status: number;
  allReady: boolean;
}

export function connectUploadWs(
  fileId: string,
  onMessage: (msg: WsProgressMessage) => void,
): WebSocket | null {
  try {
    const token = getStoredAuth()?.token;
    if (!token) return null;
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/ws/upload/${fileId}`, [
      'cloudchunk-upload',
      token,
    ]);
    ws.onmessage = (e) => {
      try {
        const data: WsProgressMessage = JSON.parse(e.data);
        onMessage(data);
      } catch {
        // ignore malformed messages
      }
    };
    ws.onerror = () => {
      // WebSocket 不可用时静默降级，不影响上传
    };
    return ws;
  } catch {
    return null;
  }
}
