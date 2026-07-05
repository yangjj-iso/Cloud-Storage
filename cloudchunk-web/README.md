# CloudChunk Web

CloudChunk 分布式切片存储服务的前端，React 18 + Vite + TypeScript + TailwindCSS。

## 特性

- 拖拽 / 点选上传；并发分片；客户端流式 MD5。
- 服务端自动秒传 / 断点续传识别，前端可视化进度。
- 图片 / 视频 / 音频内嵌预览，文件详情抽屉。
- 配额卡片、MIME 筛选、关键字搜索、分页。
- Toast 通知、取消 / 重试 / 清理 已完成任务。

## 开发

```bash
npm install
npm run dev    # http://localhost:5173  （已配置 /api 代理到 :8080）
```

## 构建

```bash
npm run build
npm run preview
```

## 配置

- 前端会在登录后把 Bearer token 放入 `Authorization` 请求头；本地开发也需要先登录。
- 后端地址改 `vite.config.ts` 中 `server.proxy['/api'].target`。
