# cloudchunk-benchmark

该 Maven 模块包含 CloudChunk 的 JMH 微基准。它用于验证局部实现的成本模型，不替代真实 Redis、MinIO 或端到端上传压测。

完整指标边界与 k6/Bash 压测入口见 [docs/08-benchmark-metrics.md](../docs/08-benchmark-metrics.md)。

## 基准清单

| 类 | 测试对象 | 能够说明什么 | 不能说明什么 |
| --- | --- | --- | --- |
| `Md5Benchmark` | 1 MB buffer 流式 MD5 与完整 `byte[]` digest | 流式计算的纯 CPU 耗时同量级，且应用额外内存可受 buffer 控制 | 真实 HTTP 上传速度、MinIO 吞吐或 GC 全貌 |
| `ProgressStoreBenchmark` | 1 次模拟 Lua RTT 与 3 次顺序模拟命令 RTT | 多次顺序网络等待的累计成本，以及 Lua 将进度置位/计数/TTL 放入一次原子操作的意义 | 真实 Redis 服务端吞吐、Pipeline、连接池、多线程和网络抖动 |

## 构建与运行

从仓库根目录执行：

```powershell
mvn clean package -pl cloudchunk-benchmark -am -DskipTests
java -jar cloudchunk-benchmark/target/benchmarks.jar
```

只执行一个基准：

```powershell
java -jar cloudchunk-benchmark/target/benchmarks.jar Md5Benchmark
java -jar cloudchunk-benchmark/target/benchmarks.jar ProgressStoreBenchmark
```

保存当前环境的输出：

```powershell
java -jar cloudchunk-benchmark/target/benchmarks.jar | Tee-Object -FilePath benchmark-output.txt
```

## 解释结果

`ProgressStoreBenchmark` 使用 `LockSupport.parkNanos` 模拟网络 RTT，而不是连接 Redis。当前仓库中的 `benchmark-output.txt` 是一份历史输出：JDK 21.0.9、单线程、0.5 ms 模拟 RTT、3 次预热和 5 次测量。在该条件下，单次模拟 Lua RTT 与三次顺序模拟 RTT 的吞吐比接近 3:1，反映的是 RTT 数量差异。

`Md5Benchmark` 的重点是内存边界。对大文件和多并发请求，流式读取消除了“每个请求先持有完整文件数组”的额外堆占用；它不保证比完整数组 digest 更快。

## 复现记录

引用基准结果时请保存：

- Git 提交版本与源代码状态。
- JDK 版本、JVM 参数、操作系统和机器规格。
- JMH 参数：线程数、预热、测量、fork、文件大小与模拟 RTT。
- 原始输出文件与运行时间。

不同 JDK、CPU、负载和 JMH Blackhole 模式之间的数字不可直接横向比较。需要验证真实上传、下载或存储性能时，使用 `deploy/bench` 下的 k6/Bash 脚本并记录完整环境。