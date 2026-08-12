# CloudChunk Web

CloudChunk 的 React 前端，基于 React 18、TypeScript、Vite、Tailwind CSS 和 Zustand。它通过 `/api/v1` 调用 Spring Boot 后端，并使用浏览器 Worker 与 IndexedDB 支持大文件上传体验。

## 当前页面能力

| 页面/能力 | 状态 | 说明 |
| --- | --- | --- |
| 登录、注册、找回密码 | 已接入 | 登录 token 保存于浏览器本地存储，并作为 Bearer Token 发送 |
| 上传队列 | 已接入 | 哈希、秒传、续传、预签名直传、代理降级、重试、取消与进度显示 |
| 文件列表与详情 | 已接入 | 搜索、MIME 筛选、分页、预览、下载与缩略图 |
| 配额 | 已接入 | 查询当前用户配额 |
| 网盘 | 已接入 | 列表、创建目录、重命名、移动和删除 |
| 回收站 | 已接入 | 列表、还原和彻底删除 |
| 我的分享 | 已接入 | 创建、列表和取消分享 |
| 管理端 | 已接入 | 用户搜索、分页、启停、配额、角色、重置密码、文件和设置管理 |
| 公开分享浏览/下载/转存 | 已接入 | 支持提取码、目录浏览、单文件/目录下载与转存 |
| 目录 ZIP 下载、邮箱换绑 | 已接入 | 网盘支持批量 ZIP 下载，账户设置支持绑定及换绑邮箱 |
| 上传 WebSocket 进度 | 已接入 | 上传任务订阅服务端实时进度，并保留本地上传进度兜底 |

## 上传行为

`src/lib/upload.ts` 的实际流程是：

1. 在 Web Worker 中单次读取文件，计算整文件与分片 MD5。
2. 从 IndexedDB 读取或写入 MD5 缓存。
3. 调用 `/upload/init` 获取 `INSTANT`、`RESUME` 或 `UPLOAD` 决策。
4. 对恢复会话尝试分片级去重。
5. 优先获取预签名 URL 并向 MinIO 直接 PUT；预签名或 PUT 失败后退回 `/upload/chunk` 后端代理。
6. 使用动态并发和指数退避上传剩余分片。
7. 调用幂等 `/upload/merge/{fileId}`；服务端可能已因 auto-merge 提前完成合并。

前端按文件大小动态选择 5 MB、10 MB、20 MB 或 40 MB 分片。上传快照会写入 IndexedDB，但当前没有在页面冷启动后自动读取快照并恢复任务；重新选择文件后由后端返回可信续传进度。

## 环境要求

- Node.js 20+
- npm
- 可用的 CloudChunk 后端

后端、MinIO、Redis 等依赖的启动见根目录 [README](../README.md)。

## 安装与运行

```powershell
npm ci
npm run dev
```

Vite 默认在 `http://localhost:5173` 运行。

### API 代理端口

当前 [vite.config.ts](./vite.config.ts) 将 `/api` 代理到 `http://localhost:8080`，与后端默认端口一致。

前端不包含独立的生产 API 地址环境变量；部署到非开发环境时，应由反向代理或构建配置把 `/api` 路径转发到后端。

## 构建与预览

```powershell
npm run build
npm run preview
```

`npm run build` 会先执行 TypeScript 项目构建，再执行 Vite 生产构建。`npm run preview` 默认使用端口 `5173` 提供已构建内容。

## 鉴权与安全边界

- 登录后，`Authorization: Bearer <token>` 会由 API 封装附加到需认证的请求。
- 公开分享接口由后端按提取码、分享状态和子树边界校验；前端通过 `#/s/{shareId}` 提供公开访问页面。
- 预签名 MinIO URL 是短期临时凭据，不应持久化、打印到日志或作为长期链接展示。
- `src/lib/ws.ts` 的上传 WebSocket 已接入上传任务；界面同时保留客户端分片进度，避免连接抖动时丢失反馈。

完整 API 和业务边界见 [docs 文档中心](../docs/README.md)。
