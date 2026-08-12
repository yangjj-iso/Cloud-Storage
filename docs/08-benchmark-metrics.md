# 基准测试与量化边界

本章说明哪些数字可以从配置或已保存结果得出，哪些数字必须在目标环境重新运行后才能使用。压测结果依赖机器、JDK、网络、对象存储、数据库、队列与并发模型；仓库中的脚本提供复现入口，不构成通用性能承诺。

## 指标来源

| 类型 | 来源 | 可以表述为 |
| --- | --- | --- |
| 配置边界 | `application.yml`、代码常量 | 当前默认值、限制或周期 |
| JMH 微基准 | `cloudchunk-benchmark` 与 `benchmark-output.txt` | 指定 JVM 与模拟条件下的结果 |
| 端到端压测 | `deploy/bench` k6/Bash 脚本及本次输出 | 固定环境、输入和并发下的实测结果 |
| 业务统计 | 线上采集、故障注入或长期观测 | 当前仓库未提供，不应虚构 |

没有附带环境和输出的“上传成功率”“秒传命中率”“TP99”或“吞吐提升”都不应表述为实测数据。

## 当前配置边界

| 项 | 默认值 | 来源 |
| --- | --- | --- |
| 后端默认分片 | 10 MB | `cloudchunk.chunk.default-size` |
| 允许分片范围 | 1 MB - 200 MB | `cloudchunk.chunk.min-size/max-size` |
| 前端动态分片 | 5 / 10 / 20 / 40 MB | `cloudchunk-web/src/lib/utils.ts` 的 `guessChunkSize` |
| 上传会话 TTL | 24 小时 | `cloudchunk.chunk.session-ttl` |
| 自动合并 | 开启 | `cloudchunk.upload.auto-merge` |
| 预签名 URL TTL | 30 分钟 | `cloudchunk.storage.presign-ttl` |
| Compose 批次 | 1000 个 source | `cloudchunk.storage.compose-batch-size` |
| 元数据缓存 | 20,000 条、5 分钟 | `cloudchunk.cache.file-meta-*` |
| 上传请求限流 | 30 rps、burst 60 | `cloudchunk.rate-limit.upload-chunk-*` |
| 下载请求限流 | 50 rps、burst 100 | `cloudchunk.rate-limit.download-*` |
| ZIP 条目/遍历上限 | 20,000 / 100,000 | `DriveZipService` 常量 |
| 上传会话清理 | 约 1 小时 | `StaleSessionCleanupTask` |
| 登录 Token TTL | 7 天 | `cloudchunk.auth.token-ttl` |
| 视频处理超时 | 30 分钟 | `cloudchunk.transcode.video-task-timeout` |

这些值是当前部署默认值，不等同于容量上限或 SLA。生产环境应根据实例资源、对象存储限制和真实负载重新配置。

## JMH 微基准

### 运行命令

在仓库根目录执行：

```powershell
mvn clean package -pl cloudchunk-benchmark -am -DskipTests
java -jar cloudchunk-benchmark/target/benchmarks.jar
```

只执行一个基准：

```powershell
java -jar cloudchunk-benchmark/target/benchmarks.jar ProgressStoreBenchmark
java -jar cloudchunk-benchmark/target/benchmarks.jar Md5Benchmark
```

保存本次输出：

```powershell
java -jar cloudchunk-benchmark/target/benchmarks.jar | Tee-Object -FilePath benchmark-output.txt
```

### ProgressStoreBenchmark

该基准不连接真实 Redis。它使用 `LockSupport.parkNanos` 模拟一次网络 RTT，对比：

| 方法 | 模拟行为 |
| --- | --- |
| `singleLuaRtt` | 一次 RTT：在 Redis 脚本中完成分片置位、计数和 TTL 刷新 |
| `threeCommandRtts` | 三次顺序 RTT：拆分为多条客户端命令 |

仓库根目录 [benchmark-output.txt](../benchmark-output.txt) 记录的历史运行条件包括 JDK 21.0.9、单线程、0.5 ms 模拟 RTT、3 次预热和 5 次测量。该次结果为：

| 方法 | 结果 |
| --- | --- |
| `singleLuaRtt` | 64.192 ops/s |
| `threeCommandRtts` | 21.475 ops/s |

该比例约为 2.99，说明在此模拟条件下，把三次顺序等待收敛为一次等待会降低 RTT 叠加成本。它不说明真实 Redis 服务器、连接池、Pipeline、序列化、网络拥塞或多线程下的总吞吐一定提升相同比例。

### Md5Benchmark

该基准比较完整 `byte[]` 直接 digest 与 1 MB buffer 流式计算 MD5。历史输出中的平均值如下：

| 文件大小 | 直接 digest | 流式 MD5 |
| --- | --- | --- |
| 1 MB | 0.970 ms/op | 1.024 ms/op |
| 10 MB | 9.941 ms/op | 10.176 ms/op |
| 100 MB | 99.309 ms/op | 103.015 ms/op |

结论是两者的纯计算时间处于同一量级，流式方式的主要价值是让应用额外内存接近 buffer 大小，而非随完整文件大小线性增长。它不能用来证明“流式 MD5 更快”。

## k6 压测

`deploy/bench` 提供以下 k6 脚本：

| 脚本 | 覆盖链路 |
| --- | --- |
| `proxy-upload.js` | 浏览器/客户端经后端代理上传分片 |
| `direct-upload.js` | 获取预签名 URL 后直接 PUT 到 MinIO，再确认分片 |
| `download.js` | 文件下载路径 |

### 前置条件

1. 启动 Compose 基础设施与后端服务。
2. 注册或登录一个用户，取得 Bearer token。
3. 准备 k6；脚本不会自动登录。
4. 对下载脚本准备一个当前用户拥有的可用 `FILE_ID`。

PowerShell 示例：

```powershell
$env:AUTH_TOKEN = "<login-response-token>"
$env:BASE_URL = "http://localhost:8080"
k6 run -e FILE_SIZE_MB=10 -e VUS=10 deploy/bench/proxy-upload.js
```

直传与下载示例：

```powershell
k6 run -e AUTH_TOKEN=$env:AUTH_TOKEN -e BASE_URL=http://localhost:8080 -e FILE_SIZE_MB=10 -e VUS=10 deploy/bench/direct-upload.js
k6 run -e AUTH_TOKEN=$env:AUTH_TOKEN -e BASE_URL=http://localhost:8080 -e FILE_ID=<file-id> -e VUS=20 deploy/bench/download.js
```

常用环境变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | 后端地址 |
| `AUTH_TOKEN` | 无，必填 | Bearer Token |
| `FILE_SIZE_MB` | 10 | 生成/模拟的文件大小 |
| `CHUNK_SIZE_MB` | 10 | 分片大小 |
| `VUS` | 脚本默认值 | 并发虚拟用户数 |
| `DURATION` | 脚本默认值 | 运行时长 |
| `FILE_ID` | 无 | 下载脚本所需的可用文件 ID |

要比较直传与代理路径，必须固定文件大小、分片大小、VUS、持续时间、后端/MinIO 实例和网络环境，并保存两次原始输出。例如：

```powershell
k6 run --out json=proxy.json -e AUTH_TOKEN=$env:AUTH_TOKEN -e FILE_SIZE_MB=100 -e CHUNK_SIZE_MB=10 -e VUS=10 deploy/bench/proxy-upload.js
k6 run --out json=direct.json -e AUTH_TOKEN=$env:AUTH_TOKEN -e FILE_SIZE_MB=100 -e CHUNK_SIZE_MB=10 -e VUS=10 deploy/bench/direct-upload.js
```

比较时至少记录 `http_req_duration` 的分位数、失败率、检查通过率、阶段标签与机器/依赖版本。没有这份记录时，只能说明直传减少了应用服务的数据中转，不应给出百分比提升。

## Bash 端到端脚本

`deploy/bench/bench_e2e.sh` 与 `deploy/bench/bench_concurrent.sh` 适合 Bash 环境。它们依赖 `curl`、`dd`、`md5sum`、`awk`，会创建随机 1 MB 测试分片、上传会话和测试文件；请仅在隔离环境或专用测试账号中运行。

串行脚本：

```bash
AUTH_TOKEN=<token> BASE_URL=http://localhost:8080/api/v1 bash deploy/bench/bench_e2e.sh 50
```

并发脚本：

```bash
AUTH_TOKEN=<token> BASE_URL=http://localhost:8080/api/v1 bash deploy/bench/bench_concurrent.sh 10 100
```

第一个位置参数分别是运行次数，或并发数；第二个位置参数是总上传数。脚本会在 `/tmp/bench_results` 或 `/tmp/bench_concurrent` 写入中间统计数据。

## 记录模板

每次要引用端到端结果时至少保存：

```text
时间：
提交版本：
机器与操作系统：
JDK / Docker / MinIO / MySQL / Redis / RocketMQ 版本：
后端配置与 JVM 参数：
文件大小、分片大小、VUS/并发、持续时间：
预热次数：
原始输出文件：
失败率、avg、p95、p99、吞吐：
异常与环境变化：
```

这些元数据使结果可以被复跑、解释和比较。详细命令见 [压测脚本 README](../deploy/bench/README.md) 与 [JMH 模块 README](../cloudchunk-benchmark/README.md)。