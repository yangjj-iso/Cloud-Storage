# 03 · API 设计

> 本文档定义 CloudChunk 当前对外 REST API、统一响应、上传下载接口、错误码与 WebSocket 通知。

## 1. 通用规范

| 项 | 规范 |
|----|------|
| 基础路径 | `/api/v1` |
| 协议 | 开发环境 HTTP，生产环境建议 HTTPS |
| 编码 | UTF-8 |
| JSON 内容类型 | `application/json` |
| 分片上传内容类型 | 推荐 `application/octet-stream`，兼容 `multipart/form-data` |
| 开发用户 | 请求头 `X-User-Id: 1`，未传时后端使用默认开发用户 |
| Trace | 请求头 / 响应头 `X-Trace-Id` |
| 分页 | `?page=1&size=20`，`size` 最大 100 |

当前项目未实现登录系统，生产环境应接入统一身份认证，再由网关或认证 Filter 写入用户上下文。

## 2. 统一响应结构

成功：

```json
{
  "code": 0,
  "message": "ok",
  "data": {},
  "traceId": "optional-trace-id"
}
```

失败：

```json
{
  "code": 200003,
  "message": "chunk md5 mismatch",
  "traceId": "b1f2c3d4"
}
```

文件下载接口 `/file/{fileId}/download` 直接返回二进制流，不包裹 `R<T>`。

## 3. 接口清单

| 分组 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 健康 | GET | `/ping` | 检查应用与当前存储策略 |
| 上传 | POST | `/upload/init` | 初始化上传会话 / 秒传检查 / 续传检查 |
| 上传 | GET | `/upload/progress/{fileId}` | 查询分片进度 |
| 上传 | POST | `/upload/chunk` | 上传单个分片，支持裸流与 multipart |
| 上传 | GET | `/upload/presign/{fileId}` | 批量生成分片预签名 PUT URL |
| 上传 | POST | `/upload/confirm` | 前端直传 MinIO 后确认分片 |
| 上传 | POST | `/upload/dedup/{fileId}` | 分片级去重 |
| 上传 | POST | `/upload/merge/{fileId}` | 触发或重试服务端合并 |
| 上传 | DELETE | `/upload/{fileId}` | 取消上传会话 |
| 文件 | GET | `/file/{fileId}` | 获取文件元数据 |
| 文件 | GET | `/file/{fileId}/download` | 下载文件，支持 ETag 与 Range |
| 文件 | GET | `/file/{fileId}/url` | 获取预签名下载 URL |
| 文件 | DELETE | `/file/{fileId}` | 删除文件引用，引用数为 0 时标记删除 |
| 文件 | GET | `/file` | 分页查询当前用户文件 |
| 转码 | GET | `/transcode/{fileId}` | 查询转码状态 |
| 转码 | POST | `/transcode/{fileId}/retry` | 手动重试转码 |
| 配额 | GET | `/quota/me` | 获取当前用户配额 |

## 4. 上传接口

### 4.1 POST `/upload/init`

用于初始化上传会话，同时完成秒传和断点续传判断。

请求：

```json
{
  "fileName": "demo.mp4",
  "fileSize": 1073741824,
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "chunkSize": 10485760,
  "chunkTotal": 103,
  "mimeType": "video/mp4"
}
```

秒传命中响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "mode": "INSTANT",
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "url": "http://...",
    "status": 2
  }
}
```

新上传响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "mode": "UPLOAD",
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "chunkSize": 10485760,
    "chunkTotal": 103,
    "uploaded": [],
    "expireAt": "2026-05-18T12:00:00"
  }
}
```

续传响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "mode": "RESUME",
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "chunkSize": 10485760,
    "chunkTotal": 103,
    "uploaded": [0, 1, 2],
    "missing": [3, 4, 5],
    "expireAt": "2026-05-18T12:00:00"
  }
}
```

### 4.2 GET `/upload/progress/{fileId}`

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "chunkTotal": 103,
    "uploaded": [0, 1, 2],
    "missing": [3, 4, 5],
    "percent": 2.91
  }
}
```

### 4.3 POST `/upload/chunk` 裸流上传

推荐路径。请求体直接是分片二进制，元数据通过 query 参数传递。

```http
POST /api/v1/upload/chunk?fileId=4b7c...&chunkIndex=0&chunkMd5=9e107d9d372bb6826bd81d3542a419d6&chunkSize=10485760
Content-Type: application/octet-stream
X-User-Id: 1

<binary>
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "chunkIndex": 0,
    "etag": "\"abc123\"",
    "status": 1,
    "allReady": false
  }
}
```

`allReady=true` 且 `cloudchunk.upload.auto-merge=true` 时，后端会尝试自动合并。

### 4.4 POST `/upload/chunk` multipart 兼容上传

兼容旧客户端。相比裸流上传，multipart 可能产生额外临时文件 I/O。

| 表单字段 | 类型 | 说明 |
|----------|------|------|
| `fileId` | string | 上传会话 ID |
| `chunkIndex` | int | 分片序号，0-based |
| `chunkMd5` | string | 分片 MD5 |
| `chunkSize` | long | 分片大小 |
| `file` | binary | 分片二进制 |

### 4.5 GET `/upload/presign/{fileId}`

为前端直传 MinIO 批量生成分片 PUT URL。

请求：

```http
GET /api/v1/upload/presign/4b7c...?indices=0,1,2
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "0": "http://127.0.0.1:9002/cloudchunk/...",
    "1": "http://127.0.0.1:9002/cloudchunk/..."
  }
}
```

### 4.6 POST `/upload/confirm`

前端直传 MinIO 成功后调用，后端校验对象存在并记录进度。

```http
POST /api/v1/upload/confirm?fileId=4b7c...&chunkIndex=0&chunkMd5=9e107d9d372bb6826bd81d3542a419d6
```

响应结构同 `/upload/chunk`。

### 4.7 POST `/upload/dedup/{fileId}`

分片级去重。前端传入 `chunkIndex -> chunkMd5` 映射，后端查找可复用分片并返回已跳过上传的分片序号。

请求：

```json
{
  "0": "9e107d9d372bb6826bd81d3542a419d6",
  "1": "e4d909c290d0fb1ca068ffaddf22cbd0"
}
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": [0, 1]
}
```

### 4.8 POST `/upload/merge/{fileId}`

触发或重试服务端合并。

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "status": "MERGED",
    "objectKey": "files/2026/05/17/4b7c...",
    "etag": "\"abc123\""
  }
}
```

### 4.9 DELETE `/upload/{fileId}`

取消上传会话。

```json
{
  "code": 0,
  "message": "ok"
}
```

## 5. 文件接口

### 5.1 GET `/file/{fileId}`

响应为 `FileMeta` 实体，核心字段如下：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
    "fileName": "demo.mp4",
    "fileSize": 1073741824,
    "mimeType": "video/mp4",
    "storageType": "minio",
    "bucket": "cloudchunk",
    "objectKey": "files/...",
    "status": "AVAILABLE",
    "transcodeStatus": "SUCCESS",
    "thumbnailUrl": "http://...",
    "extra": "{}",
    "ownerId": 1,
    "refCount": 1
  }
}
```

### 5.2 GET `/file/{fileId}/download`

直接下载文件，支持普通下载、`If-None-Match` 和 `Range`。

Range 请求：

```http
GET /api/v1/file/4b7c.../download
Range: bytes=0-1048575
```

Range 命中响应头：

```http
HTTP/1.1 206 Partial Content
Accept-Ranges: bytes
Content-Range: bytes 0-1048575/1073741824
Content-Length: 1048576
ETag: "d41d8cd98f00b204e9800998ecf8427e"
Cache-Control: private, max-age=600
```

### 5.3 GET `/file/{fileId}/url`

获取预签名下载 URL。

请求：

```http
GET /api/v1/file/4b7c.../url?ttlSeconds=1800
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "url": "http://127.0.0.1:9002/cloudchunk/...",
    "expireInSeconds": 1800
  }
}
```

### 5.4 DELETE `/file/{fileId}`

减少文件引用计数。引用计数归零时标记删除。

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "deleted": true
  }
}
```

### 5.5 GET `/file`

分页查询当前用户文件。

| 参数 | 说明 |
|------|------|
| `page` | 页码，默认 1 |
| `size` | 每页数量，默认 20，最大 100 |
| `mimePrefix` | MIME 前缀过滤，如 `image/`、`video/` |
| `keyword` | 文件名模糊搜索 |

## 6. 转码与配额接口

### 6.1 GET `/transcode/{fileId}`

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
    "transcodeStatus": "SUCCESS",
    "extra": "{}",
    "records": []
  }
}
```

### 6.2 POST `/transcode/{fileId}/retry`

重新发送转码消息。

```json
{
  "code": 0,
  "message": "ok"
}
```

### 6.3 GET `/quota/me`

获取当前用户配额。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": 1,
    "totalBytes": 107374182400,
    "usedBytes": 0,
    "fileCount": 0
  }
}
```

## 7. 错误码

| Code | HTTP | Message | 说明 |
|------|------|---------|------|
| `0` | 200 | `ok` | 成功 |
| `100001` | 400 | `invalid parameter` | 参数校验失败 |
| `100002` | 401 | `unauthorized` | 未登录 / Token 失效 |
| `100003` | 403 | `forbidden` | 无权限 |
| `100004` | 404 | `not found` | 资源不存在 |
| `100005` | 429 | `too many requests` | 触发限流 |
| `100500` | 500 | `internal server error` | 服务内部异常 |
| `200001` | 400 | `upload session expired` | 上传会话过期 |
| `200002` | 400 | `chunk index out of range` | 分片序号越界 |
| `200003` | 400 | `chunk md5 mismatch` | 分片 MD5 校验失败 |
| `200004` | 409 | `same upload in progress` | 同 MD5 文件正在上传 |
| `200005` | 400 | `whole file md5 mismatch` | 合并后整文件 MD5 不一致 |
| `200006` | 500 | `storage compose failed` | 存储合并失败 |
| `200007` | 400 | `chunks not complete` | 分片未全部完成 |
| `300001` | 404 | `file not found` | 文件不存在 |
| `300002` | 410 | `file broken` | 文件已损坏 |
| `300003` | 416 | `range not satisfiable` | Range 区间非法 |
| `400001` | 404 | `transcode record not found` | 转码记录不存在 |
| `400002` | 409 | `transcode in progress` | 转码进行中 |
| `500001` | 500 | `storage unavailable` | 存储后端不可用 |
| `500002` | 507 | `storage insufficient` | 存储空间不足 |
| `600001` | 403 | `user quota exceeded` | 用户配额超限 |

## 8. 限流策略

当前限流在 `RateLimitFilter` 中按用户维度执行 Redis 令牌桶：

| 端点 | 默认速率 | 默认突发 |
|------|----------|----------|
| `/upload/chunk` | 30 rps / user | 60 |
| `/file/{fileId}/download` | 50 rps / user | 100 |

配置项见 [11 配置参考](./11-configuration-reference.md)。

## 9. WebSocket 上传进度

前端可订阅上传进度：

```text
ws://localhost:8080/ws/upload/{fileId}
```

服务端在分片上传或确认后推送 `ChunkUploadResponse` JSON：

```json
{
  "fileId": "4b7c7f6df8b34ef9a69c0d6f2c7b9a11",
  "chunkIndex": 0,
  "etag": "\"abc123\"",
  "status": 1,
  "allReady": false
}
```

## 10. OpenAPI

项目集成 springdoc-openapi，运行后可访问：

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
