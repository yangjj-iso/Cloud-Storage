package com.cloudchunk.benchmark;

import com.cloudchunk.common.util.Md5Utils;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark: streaming MD5 (1MB buffer, DigestInputStream) vs naive readAllBytes + digest.
 *
 * The streaming approach avoids allocating the entire file content on the heap,
 * which matters for large files (100MB+) in a concurrent upload service.
 * This benchmark measures raw compute time; the real advantage of streaming
 * is lower GC pressure and bounded memory usage under concurrency.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class Md5Benchmark {

    @Param({"1048576", "10485760", "104857600"})
    private int fileSize;

    private byte[] data;

    @Setup(Level.Trial)
    public void setup() {
        data = new byte[fileSize];
        new Random(42).nextBytes(data);
    }

    /**
     * Project's streaming approach: Md5Utils.md5(InputStream) with 1MB buffer.
     * Memory usage is bounded regardless of file size.
     */
    @Benchmark
    public String streamingMd5() throws IOException {
        return Md5Utils.md5(new ByteArrayInputStream(data));
    }

    /**
     * Naive approach: load entire content into memory then digest.
     * Equivalent to MessageDigest.digest(byte[]) — simple but allocates full file on heap.
     */
    @Benchmark
    public String naiveReadAllBytesMd5() throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        return Md5Utils.hex(digest);
    }
}
