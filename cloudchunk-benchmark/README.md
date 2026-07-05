# cloudchunk-benchmark

JMH benchmarks for CloudChunK performance claims.

## Benchmarks

| Class | What it measures |
|-------|-----------------|
| `Md5Benchmark` | Streaming MD5 (1MB buffer) vs naive `readAllBytes` + digest for 1MB / 10MB / 100MB payloads |
| `ProgressStoreBenchmark` | Single Lua RTT vs 3 sequential Redis command RTTs (simulated latency) |

## Build & Run

```bash
mvn clean package -pl cloudchunk-benchmark -am -DskipTests
java -jar cloudchunk-benchmark/target/benchmarks.jar
```

Run a specific benchmark:

```bash
java -jar cloudchunk-benchmark/target/benchmarks.jar Md5Benchmark
java -jar cloudchunk-benchmark/target/benchmarks.jar ProgressStoreBenchmark
```

## Notes

- The Md5Benchmark shows raw compute time. The real advantage of streaming is bounded memory
  usage under concurrency (O(1MB) vs O(fileSize) per thread).
- The ProgressStoreBenchmark uses `LockSupport.parkNanos` to simulate network RTT.
  It proves the 3x latency multiplier of issuing commands separately vs a single Lua script.
  Actual Redis throughput depends on connection pooling, server load, and network conditions.
