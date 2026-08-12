# CloudChunk 压测脚本

`deploy/bench` 提供用于受控环境的 k6 与 Bash 压测脚本。它们会调用真实 API、创建上传会话，并可能向对象存储写入测试对象；请使用专用测试账号和隔离数据环境。

完整指标解释与历史 JMH 输出见 [docs/08-benchmark-metrics.md](../../docs/08-benchmark-metrics.md)。

## 脚本清单

| 文件 | 类型 | 覆盖链路 |
| --- | --- | --- |
| `proxy-upload.js` | k6 | 后端代理上传分片 |
| `direct-upload.js` | k6 | 预签名 URL 直传 MinIO 并确认分片 |
| `download.js` | k6 | 文件下载 |
| `bench_e2e.sh` | Bash | 串行 init、代理分片、merge 和预签名下载 |
| `bench_concurrent.sh` | Bash | 并发 init、代理分片、merge 的端到端流程 |

## 前置条件

1. 运行 MySQL、Redis、RocketMQ、MinIO 与 CloudChunk 后端。
2. 注册或登录一个测试用户并获得 Bearer Token。
3. 安装对应工具：k6 或 Bash、`curl`、`dd`、`md5sum`、`awk`。
4. 对下载测试准备一个当前用户拥有、状态为 `AVAILABLE` 的 `FILE_ID`。

后端默认地址为 `http://localhost:8080`；Bash 脚本默认 API 前缀为 `http://localhost:8080/api/v1`。

PowerShell 设置 token：

```powershell
$env:AUTH_TOKEN = "<login-response-token>"
$env:BASE_URL = "http://localhost:8080"
```

Bash 设置 token：

```bash
export AUTH_TOKEN=<login-response-token>
export BASE_URL=http://localhost:8080/api/v1
```

## k6

安装方式请参考 [k6 官方文档](https://grafana.com/docs/k6/latest/set-up/install-k6/)。所有 k6 脚本均要求 `AUTH_TOKEN`；它们不会自动执行登录。

### 代理上传

```powershell
k6 run -e AUTH_TOKEN=$env:AUTH_TOKEN -e BASE_URL=http://localhost:8080 -e FILE_SIZE_MB=10 -e VUS=10 deploy/bench/proxy-upload.js
```

### 预签名直传

```powershell
k6 run -e AUTH_TOKEN=$env:AUTH_TOKEN -e BASE_URL=http://localhost:8080 -e FILE_SIZE_MB=10 -e VUS=10 deploy/bench/direct-upload.js
```

### 下载

```powershell
k6 run -e AUTH_TOKEN=$env:AUTH_TOKEN -e BASE_URL=http://localhost:8080 -e FILE_ID=<file-id> -e VUS=20 deploy/bench/download.js
```

### 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | 后端根地址，不带 `/api/v1` |
| `AUTH_TOKEN` | 无 | 必填，登录接口返回的 token |
| `FILE_SIZE_MB` | 10 | 上传脚本生成的文件大小 |
| `CHUNK_SIZE_MB` | 10 | 上传脚本的分片大小 |
| `VUS` | 脚本默认值 | 虚拟用户并发数 |
| `DURATION` | 脚本默认值 | 压测持续时间 |
| `FILE_ID` | 无 | 下载脚本要求的可用文件 ID |

如需比较代理与直传，必须固定文件大小、分片大小、VUS、持续时间、机器、依赖版本及网络环境，并分别保存原始结果：

```powershell
k6 run --out json=proxy.json -e AUTH_TOKEN=$env:AUTH_TOKEN -e FILE_SIZE_MB=100 -e CHUNK_SIZE_MB=10 -e VUS=10 deploy/bench/proxy-upload.js
k6 run --out json=direct.json -e AUTH_TOKEN=$env:AUTH_TOKEN -e FILE_SIZE_MB=100 -e CHUNK_SIZE_MB=10 -e VUS=10 deploy/bench/direct-upload.js
```

没有两份同条件结果时，不应写“直传提升 X%”；只能说明直传减少了后端的数据中转。

## Bash 脚本

Bash 脚本会生成随机 1 MB 分片并在 `/tmp` 写入统计中间文件。请确认目标环境允许创建这些测试会话和对象。

串行端到端压测，参数为运行次数，默认 50：

```bash
AUTH_TOKEN=<token> BASE_URL=http://localhost:8080/api/v1 bash deploy/bench/bench_e2e.sh 50
```

并发端到端压测，参数为并发数和总上传数，默认 `10 100`：

```bash
AUTH_TOKEN=<token> BASE_URL=http://localhost:8080/api/v1 bash deploy/bench/bench_concurrent.sh 10 100
```

| 脚本 | 中间输出目录 | 输出内容 |
| --- | --- | --- |
| `bench_e2e.sh` | `/tmp/bench_results` | init、chunk、merge、预签名下载的 avg/p50/p95/p99 |
| `bench_concurrent.sh` | `/tmp/bench_concurrent` | init、chunk、merge、端到端耗时和上传数/秒 |

## 结果记录

保存每次测试的提交版本、JDK/Docker/依赖版本、机器配置、文件与分片大小、并发数、运行时长、预热方式、原始输出和异常情况。k6 至少关注失败率、检查通过率、`http_req_duration` 分位数和按阶段标签汇总的指标。

压测结果只对记录的运行环境有效。不要把脚本默认参数或一次局部运行结果当作生产容量、SLA 或通用性能结论。