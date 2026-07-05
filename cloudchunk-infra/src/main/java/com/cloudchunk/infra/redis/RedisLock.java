package com.cloudchunk.infra.redis;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁（基于 Redisson）。
 *
 * <p>相较手写 SET NX + Lua 释放，Redisson 提供<strong>看门狗自动续租</strong>：默认 30s 租约、
 * 每 ~10s 自动续期，直到显式 {@link #unlock} 释放；持有进程崩溃时租约到期自动释放，避免死锁。
 * 锁按当前线程持有（可重入），因此获取与释放需在同一线程（本项目均为 try/finally 同线程用法）。</p>
 *
 * <p>为兼容既有调用方，保留 (key, token, ttl) 签名；token 与 ttl 在 Redisson 实现下不再需要，
 * 租约由看门狗管理，仅保留以避免大范围改动调用点。</p>
 */
@Component
public class RedisLock {

    private final RedissonClient redisson;

    public RedisLock(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /** 非阻塞尝试获取锁；成功返回 true。租约由看门狗自动续租至 unlock。 */
    public boolean tryLock(String key, String token, Duration ttl) {
        RLock lock = redisson.getLock(key);
        try {
            // waitTime=0 非阻塞；leaseTime=-1 启用看门狗自动续租。
            return lock.tryLock(0, -1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 释放锁；仅当前线程持有时才释放，避免 IllegalMonitorStateException。 */
    public boolean unlock(String key, String token) {
        RLock lock = redisson.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            return true;
        }
        return false;
    }

    /** 带锁执行；获取失败返回 null，由调用方决定异常处理。 */
    public <T> T executeIfLocked(String key, String token, Duration ttl, Supplier<T> action) {
        if (!tryLock(key, token, ttl)) {
            return null;
        }
        try {
            return action.get();
        } finally {
            unlock(key, token);
        }
    }
}
