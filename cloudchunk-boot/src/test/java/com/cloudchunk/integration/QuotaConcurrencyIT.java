package com.cloudchunk.integration;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.quota.entity.UserQuota;
import com.cloudchunk.core.quota.mapper.UserQuotaMapper;
import com.cloudchunk.core.quota.service.QuotaService;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 针对真实 MySQL 验证「准入即预留」配额模型的并发语义。
 * <p>
 * 这套模型的全部价值就在于：并发上传不能通过「先检查、后累加」的时间窗把容量撑爆。
 * 这个性质依赖单条 UPDATE 里的条件判断在 InnoDB 行锁下串行化，Mock 掉数据库就什么都没验证。
 */
@Testcontainers(disabledWithoutDocker = true)
class QuotaConcurrencyIT {

    /** 每次预留的大小，配额总量取它的整数倍，方便断言「恰好放行 N 个」。 */
    private static final long UNIT = 10L * 1024 * 1024;
    private static final int CAPACITY_UNITS = 10;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cloudchunk")
            .withUsername("cloudchunk")
            .withPassword("cloudchunk");

    private static HikariDataSource dataSource;
    private static UserQuotaMapper quotaMapper;
    private static QuotaService quotaService;

    private static final AtomicInteger USER_SEQ = new AtomicInteger(1000);
    private long userId;

    @BeforeAll
    static void bootstrap() throws Exception {
        dataSource = MybatisTestSupport.migratedDataSource(MYSQL, 32);
        SqlSessionFactory sessionFactory =
                MybatisTestSupport.sessionFactory(dataSource, UserQuotaMapper.class);

        // SqlSessionTemplate 是线程安全的，且不在 Spring 事务中时每次调用各自提交，
        // 正好对应「每条原子 UPDATE 独立成事务」的并发模型。
        quotaMapper = new SqlSessionTemplate(sessionFactory).getMapper(UserQuotaMapper.class);
        quotaService = new QuotaService(quotaMapper);
    }

    @AfterAll
    static void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void freshUser() {
        userId = USER_SEQ.incrementAndGet();
        quotaService.allocateSpace(userId, UNIT * CAPACITY_UNITS);
    }

    private UserQuota quota() {
        return quotaMapper.selectById(userId);
    }

    /**
     * 核心不变量：无论多少并发同时申请，放行数量恰好等于容量，且任何时刻都不超卖。
     * 「先 checkCapacity 再 addUsed」的旧写法在这里必然超发。
     */
    @Test
    void concurrentReservationsNeverExceedTotal() throws Exception {
        int contenders = 64;
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(contenders, () -> {
            try {
                quotaService.tryReserve(userId, UNIT);
                granted.incrementAndGet();
            } catch (BizException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
                rejected.incrementAndGet();
            }
        });

        assertThat(granted.get()).isEqualTo(CAPACITY_UNITS);
        assertThat(rejected.get()).isEqualTo(contenders - CAPACITY_UNITS);

        UserQuota q = quota();
        assertThat(q.getReservedBytes()).isEqualTo(UNIT * CAPACITY_UNITS);
        assertThat(q.getUsedBytes() + q.getReservedBytes()).isEqualTo(q.getTotalBytes());
        assertThat(q.getReservedFileCount()).isEqualTo(CAPACITY_UNITS);
    }

    /** 秒传走 tryAddUsed、上传走 tryReserve，两条路径必须共用同一个上限。 */
    @Test
    void reservationsAndInstantConsumesShareOneCeiling() throws Exception {
        int contenders = 64;
        AtomicInteger granted = new AtomicInteger();

        runConcurrently(contenders, index -> {
            try {
                if (index % 2 == 0) {
                    quotaService.tryReserve(userId, UNIT);
                } else {
                    quotaService.tryConsume(userId, UNIT);
                }
                granted.incrementAndGet();
            } catch (BizException ignored) {
                // 容量耗尽后的拒绝是预期结果
            }
        });

        assertThat(granted.get()).isEqualTo(CAPACITY_UNITS);
        UserQuota q = quota();
        assertThat(q.getUsedBytes() + q.getReservedBytes()).isEqualTo(q.getTotalBytes());
    }

    /** 预留 → 提交是等量搬运：占用总量不变，只是从 reserved 挪到 used。 */
    @Test
    void commitMovesReservedIntoUsedWithoutChangingOccupancy() {
        quotaService.tryReserve(userId, UNIT);
        quotaService.commitReservation(userId, UNIT);

        UserQuota q = quota();
        assertThat(q.getUsedBytes()).isEqualTo(UNIT);
        assertThat(q.getReservedBytes()).isZero();
        assertThat(q.getFileCount()).isEqualTo(1);
        assertThat(q.getReservedFileCount()).isZero();
    }

    /**
     * 合并与取消并发时，取消可能先一步释放了预留。此时提交必须显式失败而不是把
     * reserved 扣成负数——合并事务据此回滚，总量不会被算错。
     */
    @Test
    void commitFailsLoudlyWhenTheReservationWasAlreadyReleased() {
        quotaService.tryReserve(userId, UNIT);
        quotaService.releaseReservation(userId, UNIT);

        assertThatThrownBy(() -> quotaService.commitReservation(userId, UNIT))
                .isInstanceOf(IllegalStateException.class);

        UserQuota q = quota();
        assertThat(q.getReservedBytes()).isZero();
        assertThat(q.getUsedBytes()).isZero();
    }

    /** 重复释放（重试、过期清理与取消撞车）不能把配额扣穿。 */
    @Test
    void repeatedReleaseNeverDrivesQuotaNegative() throws Exception {
        quotaService.tryReserve(userId, UNIT);

        runConcurrently(16, () -> quotaService.releaseReservation(userId, UNIT));

        UserQuota q = quota();
        assertThat(q.getReservedBytes()).isZero();
        assertThat(q.getReservedFileCount()).isZero();
    }

    /** 已占用的容量不能被管理员下调到低于占用量，否则后续结算会出现负数。 */
    @Test
    void allocateSpaceCannotDropBelowCommittedUsage() {
        quotaService.tryReserve(userId, UNIT * 4);

        assertThatThrownBy(() -> quotaService.allocateSpace(userId, UNIT))
                .isInstanceOf(BizException.class);

        assertThat(quota().getTotalBytes()).isEqualTo(UNIT * CAPACITY_UNITS);
    }

    private void runConcurrently(int tasks, Runnable task) throws Exception {
        runConcurrently(tasks, index -> task.run());
    }

    private interface IndexedTask {
        void run(int index);
    }

    private void runConcurrently(int tasks, IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks, 24));
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
            assertThat(done.await(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
