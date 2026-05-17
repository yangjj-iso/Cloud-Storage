# 12 · 常见问题排障

> 排障原则：先确认依赖服务健康，再看应用配置，最后按上传 / 下载 / 转码链路定位业务问题。

## 1. 快速体检

```bash
docker compose -f deploy/docker-compose.yml ps
curl http://localhost:8080/api/v1/ping
curl http://localhost:8080/actuator/health
```

如果后端未启动，先看控制台日志；如果依赖容器异常，使用：

```bash
docker compose -f deploy/docker-compose.yml logs -f mysql
docker compose -f deploy/docker-compose.yml logs -f redis
docker compose -f deploy/docker-compose.yml logs -f rmq-broker
docker compose -f deploy/docker-compose.yml logs -f minio
```

## 2. 端口冲突

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| MySQL 容器启动失败 | `3308` 被占用 | 修改 `deploy/docker-compose.yml` 的 `3308:3306`，并同步 `MYSQL_PORT` |
| Redis 容器启动失败 | `6380` 被占用 | 修改 Redis 端口映射，并同步 `REDIS_PORT` |
| MinIO 无法访问 | `9002` / `9003` 被占用 | 修改 MinIO 端口映射，并同步 `MINIO_ENDPOINT` |
| 前端打不开 | `5173` 被占用 | Vite 会提示换端口，也可以调整 `cloudchunk-web/vite.config.ts` |

Windows 下查看端口占用：

```powershell
netstat -ano | findstr :8080
```

## 3. MySQL 连接失败

常见日志：

```text
Communications link failure
Access denied for user 'root'
Unknown database 'cloudchunk'
```

排查：

```bash
docker compose -f deploy/docker-compose.yml ps mysql
docker compose -f deploy/docker-compose.yml logs mysql
```

处理建议：

- 确认后端连接端口是宿主机 `3308`，不是容器内 `3306`。
- 确认 `MYSQL_PASSWORD` 与 `deploy/.env.example` 中的 `MYSQL_ROOT_PASSWORD` 一致。
- 如果初始化 SQL 没有执行，重建卷或手动执行 `deploy/sql/schema.sql`。

## 4. Redis 连接失败

常见日志：

```text
Unable to connect to Redis server
Connection refused: 127.0.0.1:6380
```

排查：

```bash
docker compose -f deploy/docker-compose.yml ps redis
docker compose -f deploy/docker-compose.yml logs redis
```

处理建议：

- 确认 `REDIS_PORT=6380`。
- 如果使用外部 Redis，检查网络、防火墙和密码配置。
- 进度丢失时可通过 MySQL `chunk_record` 和对象存储分片列表重建，详见 [04 分片上传协议](./04-chunk-upload-protocol.md)。

## 5. RocketMQ 启动或发送失败

常见日志：

```text
No route info of this topic
connect to <broker> failed
sendDefaultImpl call timeout
```

排查：

```bash
docker compose -f deploy/docker-compose.yml ps rmq-namesrv rmq-broker
docker compose -f deploy/docker-compose.yml logs rmq-broker
```

处理建议：

- 确认 `RMQ_NAMESRV=127.0.0.1:9876`。
- 本地 compose 已开启 `autoCreateTopicEnable=true`，生产环境如果关闭自动创建 Topic，需要提前创建 `cloudchunk-transcode`、`cloudchunk-checksum`、`cloudchunk-broken`。
- 打开 `http://localhost:8180` 查看 Topic 与消费者组状态。

## 6. MinIO 访问失败

常见日志：

```text
storage unavailable
The Access Key Id you provided does not exist
Connection refused: 127.0.0.1:9002
```

排查：

```bash
docker compose -f deploy/docker-compose.yml ps minio
docker compose -f deploy/docker-compose.yml logs minio
```

处理建议：

- 后端访问 MinIO API 使用 `http://127.0.0.1:9002`。
- 浏览器打开控制台使用 `http://localhost:9003`。
- 默认账号密码为 `minioadmin / minioadmin`。
- 当前 `MinioStorageStrategy` 会在应用启动时自动创建默认 bucket。

## 7. 上传分片失败

| 错误码 | 含义 | 常见原因 |
|--------|------|----------|
| `100001` | 参数错误 | `chunkSize` 与实际请求体长度不一致，或分片超过最大值 |
| `200002` | 分片序号非法 | `chunkIndex` 超出总分片范围 |
| `200003` | 分片 MD5 不一致 | 前端计算值与后端流式校验值不同 |
| `200004` | 同文件上传中 | 秒传锁或上传会话仍存在 |
| `200007` | 分片未完成 | 提前触发 merge |
| `500001` | 存储不可用 | MinIO 连接、账号或 bucket 异常 |

排查顺序：

1. 前端 Network 面板确认请求为 `POST /api/v1/upload/chunk`。
2. 确认请求 `Content-Type` 是 `application/octet-stream` 或 multipart 兼容格式。
3. 检查 query 参数：`fileId`、`chunkIndex`、`chunkMd5`、`chunkSize`。
4. 检查后端日志中的 `traceId` 和业务 `fileId`。
5. 查看 Redis `cc:upload:progress:{fileId}` 是否记录分片进度。

## 8. 合并失败

常见原因：

- 分片没有全部上传完成。
- MinIO 中缺少某个 chunk object。
- 分片数量过多，触发 Compose 分批逻辑但中间对象清理失败。
- 合并锁 `cc:upload:merge-lock:{fileId}` 还未释放。

处理建议：

- 先调用 `GET /api/v1/upload/progress/{fileId}` 确认完成数量。
- 再调用 `POST /api/v1/upload/merge/{fileId}` 手动重试。
- 如果是测试数据，可以删除会话后重新上传。

## 9. 下载失败

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| `404 FILE_NOT_FOUND` | fileId 不存在或不属于当前用户 | 检查 `file_meta` |
| `410 FILE_BROKEN` | 异步校验失败，文件被标记损坏 | 重新上传 |
| `416 RANGE_NOT_SATISFIABLE` | Range 超出文件大小 | 检查客户端 `Range` 请求头 |
| 浏览器下载文件名乱码 | 客户端未正确识别 `filename*` | 使用现代浏览器或直接使用预签名 URL |

下载接口支持：

- `If-None-Match` 命中返回 `304`。
- `Range: bytes=start-end` 命中返回 `206`。
- 普通下载返回 `200` 和完整文件流。

## 10. 前端请求失败

常见原因：

- 后端未启动，Vite 代理无法连接 `http://localhost:8080`。
- 用户上下文缺失。开发环境前端会自动带 `X-User-Id: 1`。
- API 返回统一结构，前端 `ApiError` 会读取 `code`、`message` 和 `traceId`。

排查：

```bash
npm --prefix cloudchunk-web run build
```

如果构建成功但页面调用失败，优先看浏览器 Network 和后端日志。

## 11. 转码没有结果

排查顺序：

1. 确认文件已合并完成且状态允许转码。
2. 打开 RocketMQ Dashboard，确认 `cloudchunk-transcode` 有消息。
3. 检查消费者组 `CG-transcode-img`、`CG-transcode-video`、`CG-transcode-doc`。
4. 视频任务确认本机或容器内可以执行 `ffmpeg -version`。
5. 查看 `transcode_record` 的状态与错误信息。

本地只验证上传下载时，可以暂时忽略 FFmpeg；视频转码相关错误不会影响普通文件下载。

## 12. 何时重建环境

出现以下情况时，重建依赖数据通常最快：

- schema 改动较大，旧表结构与代码不匹配。
- Redis 中保留了过期上传会话，反复命中脏进度。
- MinIO 测试桶内存在不完整 chunk object。
- RocketMQ 本地 Topic / offset 状态混乱。

```bash
docker compose -f deploy/docker-compose.yml down -v
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example up -d
```
