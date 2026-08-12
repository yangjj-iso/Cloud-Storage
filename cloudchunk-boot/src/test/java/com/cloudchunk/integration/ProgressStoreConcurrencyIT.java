package com.cloudchunk.integration;

import com.cloudchunk.core.upload.service.ProgressStore;
import com.cloudchunk.infra.redis.RedisService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对真实 Redis 验证上传进度的并发语义。
 * <p>
 * 这些不变量在单元测试里是证明不了的：Mock 掉 Redis 之后，Lua 脚本的原子性、
 * HSETNX 的并集语义、以及重建与并发写入之间的交错全都被抹掉了。而这恰好是多节点部署下
 * 最容易出错、且出错时不报任何异常的地方——进度只是悄悄地少了几片。
 */
@Testcontainers(disabledWithoutDocker = true)
class ProgressStoreConcurrencyIT {

    private static final Duration TTL = Duration.ofHours(1);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ProgressStore progressStore;

    private String fileId;

    @BeforeAll
    static void startRedis() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        progressStore = new ProgressStore(new RedisService(template));
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void freshFile() {
        fileId = "it-" + System.nanoTime();
    }

    /** 同一个分片被并发重复确认（重试、多标签页）时不能把完成数撑大。 */
    @Test
    void concurrentDuplicateMarkDoneCountsOnce() throws Exception {
        progressStore.init(fileId, 10, TTL);

        runConcurrently(32, i -> progressStore.markDone(fileId, 0, TTL));

        assertThat(progressStore.doneCount(fileId)).isEqualTo(1);
        assertThat(progressStore.uploadedSet(fileId)).containsExactly(0);
    }

    @Test
    void concurrentDistinctChunksAreAllCounted() throws Exception {
        int total = 64;
        progressStore.init(fileId, total, TTL);

        runConcurrently(total, i -> progressStore.markDone(fileId, i, TTL));

        assertThat(progressStore.doneCount(fileId)).isEqualTo(total);
    }

    /** 重建必须是并集：Redis 里已有但兜底数据里没有的分片位不能被抹掉。 */
    @Test
    void rebuildMergesInsteadOfOverwriting() {
        progressStore.init(fileId, 10, TTL);
        progressStore.markDone(fileId, 5, TTL);

        int count = progressStore.rebuild(fileId, 10, new TreeSet<>(Set.of(0, 1)), TTL);

        assertThat(count).isEqualTo(3);
        assertThat(progressStore.uploadedSet(fileId)).containsExactly(0, 1, 5);
        assertThat(progressStore.doneCount(fileId)).isEqualTo(3);
    }

    /**
     * 这条是真正的回归用例。
     * <p>
     * 共享上传会话允许多个参与者同时上传，因此一个节点重建进度时，另一个节点可能正在
     * markDone。旧实现直接 {@code HSET __count__ = confirmed.size()}，会把并发的自增覆盖掉，
     * 表现为进度永远差几片、合并一直报 CHUNK_NOT_COMPLETE。这里让重建线程反复拿着一份
     * 陈旧快照去重建，同时写入线程持续推进，最终计数必须等于实际写入的分片数。
     */
    @Test
    void rebuildNeverLosesConcurrentMarkDone() throws Exception {
        int total = 200;
        Set<Integer> staleSnapshot = new TreeSet<>();
        for (int i = 0; i < 10; i++) staleSnapshot.add(i);

        progressStore.init(fileId, total, TTL);
        for (int i = 0; i < 10; i++) progressStore.markDone(fileId, i, TTL);

        AtomicBoolean writersDone = new AtomicBoolean(false);
        Thread rebuilder = new Thread(() -> {
            while (!writersDone.get()) {
                progressStore.rebuild(fileId, total, staleSnapshot, TTL);
            }
        });
        rebuilder.start();

        runConcurrently(total - 10, i -> progressStore.markDone(fileId, 10 + i, TTL));
        writersDone.set(true);
        rebuilder.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(progressStore.doneCount(fileId)).isEqualTo(total);
        assertThat(progressStore.uploadedSet(fileId)).hasSize(total);
    }

    /** doneCount 是 O(1) 读取的计数字段，必须与实际置位的分片数一致。 */
    @Test
    void countFieldStaysConsistentWithChunkFields() throws Exception {
        int total = 50;
        progressStore.init(fileId, total, TTL);

        // 混合重复确认与重建，制造尽可能多的交错。
        runConcurrently(total, i -> {
            progressStore.markDone(fileId, i, TTL);
            progressStore.markDone(fileId, i, TTL);
            if (i % 10 == 0) {
                progressStore.rebuild(fileId, total, new TreeSet<>(Set.of(0, 1, 2)), TTL);
            }
        });

        assertThat(progressStore.doneCount(fileId))
                .isEqualTo(progressStore.uploadedSet(fileId).size())
                .isEqualTo(total);
    }

    private interface IndexedTask {
        void run(int index);
    }

    private void runConcurrently(int tasks, IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks, 16));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        try {
            for (int i = 0; i < tasks; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        task.run(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
