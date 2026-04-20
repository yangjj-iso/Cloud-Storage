package com.cloudchunk.common.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 虚拟线程 + {@link Semaphore} 背压的有界执行器。
 *
 * <p>为什么不用 {@code Thread.startVirtualThread}？</p>
 * <ul>
 *   <li>虚拟线程本身几乎零成本，但它们背后持有的 <b>下游资源</b>（MinIO 连接、DB 连接、文件描述符）
 *       不是无限的。无界地起虚拟线程 = 无界地抢这些资源，瞬间打爆外部服务。</li>
 *   <li>这个包装保留了虚拟线程的吞吐优势（每个任务仍跑在 vthread 上，载体线程不阻塞），
 *       同时用信号量设定"同时能打出去的并发量"上限，给外部系统留喘息空间。</li>
 * </ul>
 *
 * <p>典型用法：后台清理 / MinIO 批量删除 / MQ 补偿 / 异步校验，
 * 这些任务对延迟不敏感，但如果并发爆炸会拖垮主链路。</p>
 */
public class BoundedVirtualThreadExecutor implements Executor, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedVirtualThreadExecutor.class);

    private final String name;
    private final Semaphore permits;
    private final ExecutorService delegate;
    private final long acquireTimeoutMs;

    public BoundedVirtualThreadExecutor(String name, int maxConcurrent, long acquireTimeoutMs) {
        if (maxConcurrent <= 0) throw new IllegalArgumentException("maxConcurrent must be > 0");
        this.name = name;
        this.permits = new Semaphore(maxConcurrent);
        this.acquireTimeoutMs = acquireTimeoutMs;
        this.delegate = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("bvte-" + name + "-", 0).factory());
    }

    @Override
    public void execute(Runnable task) {
        try {
            boolean acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("[{}] backpressure: rejected, available={}", name, permits.availablePermits());
                throw new java.util.concurrent.RejectedExecutionException(
                        "bounded-executor " + name + " full");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.RejectedExecutionException("interrupted", e);
        }
        delegate.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("[{}] task failed", name, t);
            } finally {
                permits.release();
            }
        });
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int queueLength() {
        return permits.getQueueLength();
    }

    @Override
    public void close() {
        delegate.shutdown();
        try {
            if (!delegate.awaitTermination(10, TimeUnit.SECONDS)) {
                delegate.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delegate.shutdownNow();
        }
    }
}
