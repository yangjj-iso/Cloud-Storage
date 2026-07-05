package com.cloudchunk.common.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 令牌桶限速输入流：把读取速率限制在 bytesPerSec 以内。
 *
 * <p>桶容量 = 1 秒的速率（允许 1 秒突发）；每次读取先申请令牌，不足则阻塞到有预算。
 * 单次读取上限 {@link #MAX_CHUNK}，减少系统调用同时避免长时间独占。基于 {@link FilterInputStream}，
 * {@code close()} 透传底层流。</p>
 */
public class RateLimitedInputStream extends FilterInputStream {

    private static final int MAX_CHUNK = 256 * 1024;

    private final double bytesPerSec;
    private double tokens;
    private long lastNanos;

    public RateLimitedInputStream(InputStream in, long bytesPerSec) {
        super(in);
        this.bytesPerSec = bytesPerSec <= 0 ? Double.MAX_VALUE : bytesPerSec;
        this.tokens = Math.min(this.bytesPerSec, MAX_CHUNK);
        this.lastNanos = System.nanoTime();
    }

    @Override
    public int read() throws IOException {
        acquire(1);
        return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len <= 0) {
            return super.read(b, off, len);
        }
        int budget = acquire(Math.min(len, MAX_CHUNK));
        return super.read(b, off, budget);
    }

    /** 申请最多 want 字节的令牌，返回实际允许的字节数（≥1）；不足时阻塞。 */
    private synchronized int acquire(int want) throws IOException {
        for (;;) {
            refill();
            if (tokens >= 1) {
                int grant = (int) Math.min(want, tokens);
                if (grant < 1) grant = 1;
                tokens -= grant;
                return grant;
            }
            double needed = 1 - tokens;
            long sleepMs = (long) Math.ceil(needed / bytesPerSec * 1000.0);
            if (sleepMs < 1) sleepMs = 1;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("rate-limited read interrupted", e);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        tokens = Math.min(bytesPerSec, tokens + elapsedSec * bytesPerSec);
    }
}
