package com.cloudchunk.benchmark;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Benchmark: single Lua RTT vs three sequential Redis command RTTs.
 *
 * This does NOT benchmark actual Redis performance. Instead, it demonstrates
 * the RTT multiplier effect: when a client must issue N sequential commands,
 * total latency scales linearly with N. The project's Lua script combines
 * HSETNX + HINCRBY + EXPIRE into a single atomic round-trip, reducing
 * per-chunk upload latency by ~3x compared to issuing them separately.
 *
 * The simulated RTT (default 0.5ms) represents a typical intra-datacenter
 * Redis round-trip. At 1000 concurrent chunk uploads, the 3-RTT approach
 * wastes ~1 second of cumulative latency per batch vs the Lua approach.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ProgressStoreBenchmark {

    /**
     * Simulated network RTT in nanoseconds.
     * Default 500_000 ns = 0.5 ms, typical for intra-DC Redis.
     */
    @Param({"500000"})
    private long rttNanos;

    private void simulateRtt() {
        LockSupport.parkNanos(rttNanos);
    }

    /**
     * Single Lua script approach (what the project does):
     * One round-trip executes HSETNX + HINCRBY + EXPIRE atomically.
     */
    @Benchmark
    public void singleLuaRtt() {
        simulateRtt();
    }

    /**
     * Naive three-command approach:
     * HSETNX (1 RTT) -> HINCRBY (1 RTT) -> EXPIRE (1 RTT).
     * Total latency = 3x single RTT.
     */
    @Benchmark
    public void threeCommandRtts() {
        simulateRtt();
        simulateRtt();
        simulateRtt();
    }
}
