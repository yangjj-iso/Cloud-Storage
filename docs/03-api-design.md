# 03 · API 设计

> 本文档定义 CloudChunk 对外 RESTful API，包括统一规范、核心接口与错误码。

---

## 1. 规范

### 1.1 通用约定

| 项 | 规范 |
|----|------|
| 基础路径 | `/api/v1` |
| 协议 | HTTP/1.1，生产 HTTPS |
| 编码 | UTF-8 |
| 内容类型 | `application/json`（分片上传使用 `multipart/form-data`） |
| 鉴权 | `Authorization: Bearer {JWT}` |
| 时间格式 | ISO-8601 `2025-01-01T12:00:00.000Z` |
| 分页 | `?page=1&size=20`，响应含 `total / page / size` |

### 1.2 统一响应结构

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "traceId": "b1f2c3d4-..."
}
```

- `code = 0` 表示成功；非 0 见 [§5 错误码](#5-错误码)
- `traceId` 用于全链路追踪（Skywalking / SLF4J MDC）

### 1.3 鉴权

- 登录 → 后端签发 JWT（此项目**不负责登录**，假设已有统一身份系统）
- 每次请求带 `Authorization: Bearer xxx`
- 内部服务调用可用 `X-Internal-Token`

---

## 2. 接口清单

| 分组 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 上传 | POST | `/upload/init` | 初始化上传会话 / 秒传检查 |
| 上传 | GET | `/upload/progress/{fileId}` | 查询分片进度 |
| 上传 | POST | `/upload/chunk` | 上传单个分片 |
| 上传 | POST | `/upload/merge/{fileId}` | 触发服务端合并 |
| 上传 | DELETE | `/upload/{fileId}` | 取消上传会话 |
| 文件 | GET | `/file/{fileId}` | 获取文件元数据 |
| 文件 | GET | `/file/{fileId}/download` | 下载（支持 Range） |
| 文件 | GET | `/file/{fileId}/url` | 获取预签名下载 URL |
| 文件 | DELETE | `/file/{fileId}` | 删除文件（逻辑删除 + ref_count--） |
| 文件 | GET | `/file` | 分页查询当前用户文件 |
| 转码 | GET | `/transcode/{fileId}` | 查询转码状态 |
| 转码 | POST | `/transcode/{fileId}/retry` | 手动重试转码 |
| 配额 | GET | `/quota/me` | 获取当前用户配额 |

---

## 3. 上传接口详细

### 3.1 POST `/upload/init` — 初始化 & 秒传

**请求**
```json
{
  "fileName": "demo.mp4",
  "fileSize": 1073741824,
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e",
  "chunkSize": 5242880,
  "chunkTotal": 205,
  "mimeType": "video/mp4"
}
```

**响应 — 秒传命中**
```json
{
  "code": 0,
  "data": {
    "mode": "INSTANT",
    "fileId": "a1b2c3...",
    "url": "https://cdn.example.com/xxx",
    "status": 2
  }
}
```

**响应 — 需要上传（新会话）**
```json
{
  "code": 0,
  "data": {
    "mode": "UPLOAD",
    "fileId": "a1b2c3...",
    "chunkSize": 5242880,
    "chunkTotal": 205,
    "uploaded": [],
    "expireAt": "2025-01-02T12:00:00Z"
  }
}
```

**响应 — 需要续传（会话已存在）**
```json
{
  "code": 0,
  "data": {
    "mode": "RESUME",
    "fileId": "a1b2c3...",
    "chunkSize": 5242880,
    "chunkTotal": 205,
    "uploaded": [0, 1, 2, 5, 6],
    "missing": [3, 4, 7, 8, "..."]
  }
}
```

---

### 3.2 GET `/upload/progress/{fileId}` — 查询进度

**响应**
```json
{
  "code": 0,
  "data": {
    "fileId": "a1b2c3...",
    "chunkTotal": 205,
    "uploaded": [0, 1, 2],
    "missing": [3, 4, "..."],
    "percent": 1.46
  }
}
```

---

### 3.3 POST `/upload/chunk` — 上传分片

**Content-Type**: `multipart/form-data`

| 表单字段 | 类型 | 说明 |
|----------|------|------|
| `fileId` | string | 上传会话 ID |
| `chunkIndex` | int | 分片序号（0-based） |
| `chunkMd5` | string | 分片 MD5 |
| `chunkSize` | long | 分片大小 |
| `file` | binary | 分片二进制内容 |

**响应**
```json
{
  "code": 0,
  "data": {
    "fileId": "a1b2c3...",
    "chunkIndex": 42,
    "etag": "\"abc123...\"",
    "status": 1
  }
}
```

**幂等性**：同一 `(fileId, chunkIndex, chunkMd5)` 重复上传直接返回已有 ETag，不报错。

---

### 3.4 POST `/upload/merge/{fileId}` — 触发合并

> 前端**不强制**调用；当所有分片上传完成，后端可自动触发。此接口用于显式触发或重试合并。

**请求**
```json
{
  "fileMd5": "d41d8cd98f00b204e9800998ecf8427e"
}
```

**响应**
```json
{
  "code": 0,
  "data": {
    "fileId": "a1b2c3...",
    "status": "MERGING",
    "mergeTaskId": "merge-xxx"
  }
}
```

- 合并为**异步**操作，前端通过 `/file/{fileId}` 或 WebSocket 轮询状态
- 返回 `status`: `MERGING` / `MERGED` / `AVAILABLE` / `BROKEN`

---

### 3.5 DELETE `/upload/{fileId}` — 取消会话

**响应**
```json
{ "code": 0, "data": { "cancelled": true } }
```

- 删除 Redis 进度 Key、清理已上传分片对象、更新 `upload_session.status=3`

---

## 4. 文件接口详细

### 4.1 GET `/file/{fileId}` — 元数据

**响应**
```json
{
  "code": 0,
  "data": {
    "fileId": "a1b2c3...",
    "fileName": "demo.mp4",
    "fileSize": 1073741824,
    "mimeType": "video/mp4",
    "md5": "d41d...",
    "status": 2,
    "transcodeStatus": 2,
    "thumbnailUrl": "https://.../cover.jpg",
    "extra": {
      "duration": 3600,
      "resolution": "1920x1080",
      "codec": "h264"
    },
    "createdAt": "2025-01-01T12:00:00Z"
  }
}
```

### 4.2 GET `/file/{fileId}/download` — 直接下载（支持 Range）

**请求头**（可选）
```
Range: bytes=0-1048575
```

**响应头（Range 命中）**
```
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-1048575/1073741824
Content-Length: 1048576
Content-Type: video/mp4
Accept-Ranges: bytes
```

### 4.3 GET `/file/{fileId}/url` — 预签名 URL

**响应**
```json
{
  "code": 0,
  "data": {
    "url": "https://minio.example.com/bucket/obj?X-Amz-Signature=...",
    "expireAt": "2025-01-01T12:30:00Z"
  }
}
```

### 4.4 DELETE `/file/{fileId}` — 删除

- 默认**逻辑删除**（`status=4`, `ref_count--`）
- `ref_count == 0` 时异步触发对象真实删除
- `?force=true`（仅管理员）：物理删除对象

### 4.5 GET `/file` — 分页列表

**Query**

| 参数 | 说明 |
|------|------|
| `page` | 页码，默认 1 |
| `size` | 每页大小，默认 20，最大 100 |
| `mimeType` | 可选，按类型过滤（如 `image/*`） |
| `keyword` | 可选，按文件名模糊 |
| `sort` | `createdAt,desc`（默认） |

---

## 5. 错误码

统一采用 **6 位数字**，前 2 位标识模块：

| 范围 | 模块 |
|------|------|
| `10xxxx` | 通用 |
| `20xxxx` | 上传 |
| `30xxxx` | 下载 / 文件 |
| `40xxxx` | 转码 |
| `50xxxx` | 存储 |
| `60xxxx` | 配额 |

### 5.1 错误码清单

| Code | HTTP | Message | 说明 |
|------|------|---------|------|
| `0` | 200 | ok | 成功 |
| `100001` | 400 | INVALID_PARAMETER | 参数校验失败 |
| `100002` | 401 | UNAUTHORIZED | 未登录 / Token 失效 |
| `100003` | 403 | FORBIDDEN | 无权限 |
| `100004` | 404 | NOT_FOUND | 资源不存在 |
| `100005` | 429 | TOO_MANY_REQUESTS | 限流 |
| `100500` | 500 | INTERNAL_ERROR | 服务内部异常 |
| `200001` | 400 | UPLOAD_SESSION_EXPIRED | 上传会话过期 |
| `200002` | 400 | CHUNK_INDEX_INVALID | 分片序号越界 |
| `200003` | 400 | CHUNK_MD5_MISMATCH | 分片 MD5 校验失败 |
| `200004` | 409 | UPLOAD_IN_PROGRESS | 同 MD5 文件正在上传（幂等锁） |
| `200005` | 400 | FILE_MD5_MISMATCH | 合并后整文件 MD5 不一致 |
| `200006` | 500 | COMPOSE_FAILED | MinIO Compose 失败 |
| `300001` | 404 | FILE_NOT_FOUND | 文件不存在 |
| `300002` | 410 | FILE_BROKEN | 文件已损坏 |
| `300003` | 416 | RANGE_NOT_SATISFIABLE | Range 区间非法 |
| `400001` | 404 | TRANSCODE_NOT_FOUND | 转码记录不存在 |
| `400002` | 409 | TRANSCODE_IN_PROGRESS | 转码进行中 |
| `500001` | 500 | STORAGE_UNAVAILABLE | 存储后端不可用 |
| `500002` | 507 | STORAGE_INSUFFICIENT | 存储空间不足 |
| `600001` | 403 | QUOTA_EXCEEDED | 用户配额超限 |

---

## 6. 限流策略

| 接口 | 维度 | 阈值 |
|------|------|------|
| `/upload/init` | user | 60/min |
| `/upload/chunk` | user | 600/min（约 10 路并发 × 60 分片/min） |
| `/upload/merge` | user + fileId | 10/min（幂等） |
| `/file/*/download` | user | 300/min |
| 全局 | IP | 1000/min |

实现：Redis + Lua 滑动窗口。

---

## 7. 可选：WebSocket 通知

合并进度、转码进度可通过 WebSocket 推送，避免前端轮询。

**端点**：`/ws/file?token={jwt}`

**消息**：
```json
{
  "type": "MERGE_DONE | TRANSCODE_DONE | FILE_BROKEN",
  "fileId": "a1b2c3...",
  "payload": { }
}
```

---

## 8. OpenAPI

项目集成 **springdoc-openapi**，运行后自动暴露：
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
