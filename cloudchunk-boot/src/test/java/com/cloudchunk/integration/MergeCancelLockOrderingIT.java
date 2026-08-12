package com.cloudchunk.integration;

import com.cloudchunk.common.enums.UploadSessionStatus;
import com.cloudchunk.core.file.entity.FileReference;
import com.cloudchunk.core.file.mapper.FileReferenceMapper;
import com.cloudchunk.core.upload.entity.UploadSession;
import com.cloudchunk.core.upload.mapper.UploadSessionMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证「先锁会话行，再读参与者」这条加锁顺序在真实 MySQL 上确实必要。
 * <p>
 * MergeTransactionService 曾经先读 file_reference、中途才更新 upload_session。在 InnoDB 的
 * REPEATABLE READ 下，事务的读视图是在第一次一致性读时建立的，因此并发的取消即便已经提交，
 * 合并事务仍然看得见那个参与者，进而为一个不再持有预留的用户结算配额，
 * commitReservation 找不到预留就抛错、整个合并回滚——一个人取消把其他人的合并一起弄失败。
 * <p>
 * 这里用生产的 Mapper 与真实事务复现该行为，并证明先取会话行锁能消除它。
 */
@Testcontainers(disabledWithoutDocker = true)
class MergeCancelLockOrderingIT {

    private static final String BUCKET = "cloudchunk";
    private static final long OWNER = 1L;
    private static final long PARTICIPANT = 2L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cloudchunk")
            .withUsername("cloudchunk")
            .withPassword("cloudchunk");

    private static HikariDataSource dataSource;
    private static UploadSessionMapper sessionMapper;
    private static FileReferenceMapper referenceMapper;
    private static TransactionTemplate tx;

    private static final AtomicInteger FILE_SEQ = new AtomicInteger();
    private String fileId;

    @BeforeAll
    static void bootstrap() throws Exception {
        dataSource = MybatisTestSupport.migratedDataSource(MYSQL, 8);
        SqlSessionFactory sessionFactory = MybatisTestSupport.sessionFactory(
                dataSource, UploadSessionMapper.class, FileReferenceMapper.class);
        SqlSessionTemplate template = new SqlSessionTemplate(sessionFactory);
        sessionMapper = template.getMapper(UploadSessionMapper.class);
        referenceMapper = template.getMapper(FileReferenceMapper.class);

        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @AfterAll
    static void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void seedRunningSessionWithTwoParticipants() {
        fileId = String.format("%032d", FILE_SEQ.incrementAndGet());
        UploadSession s = new UploadSession();
        s.setFileId(fileId);
        s.setFileMd5(String.format("%032d", 1));
        s.setFileName("race.bin");
        s.setFileSize(1024L);
        s.setMimeType("application/octet-stream");
        s.setChunkSize(1024);
        s.setChunkTotal(1);
        s.setOwnerId(OWNER);
        s.setStatus(UploadSessionStatus.RUNNING);
        s.setBucket(BUCKET);
        s.setObjectKey("objects/" + fileId);
        s.setExpireAt(LocalDateTime.now().plusHours(1));
        sessionMapper.insert(s);

        addReference(OWNER);
        addReference(PARTICIPANT);
    }

    private void addReference(long userId) {
        FileReference r = new FileReference();
        r.setFileId(fileId);
        r.setUserId(userId);
        r.setFileName("race.bin");
        referenceMapper.insert(r);
    }

    /**
     * 复现旧行为：合并事务先读参与者，此后并发取消的提交对它不可见。
     * 这就是「一个人取消导致其他人合并失败」的根因。
     */
    @Test
    void readingParticipantsBeforeLockingKeepsAStaleSnapshot() throws Exception {
        CountDownLatch mergeDidFirstRead = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        AtomicReference<Integer> firstRead = new AtomicReference<>();
        AtomicReference<Integer> secondRead = new AtomicReference<>();

        Thread merge = new Thread(() -> tx.executeWithoutResult(status -> {
            // 没有先锁会话行：这一次读就把读视图固定在了取消提交之前。
            firstRead.set(referenceMapper.selectByFileId(fileId).size());
            mergeDidFirstRead.countDown();
            awaitQuietly(cancelCommitted);
            secondRead.set(referenceMapper.selectByFileId(fileId).size());
        }));

        Thread cancel = new Thread(() -> {
            awaitQuietly(mergeDidFirstRead);
            tx.executeWithoutResult(status -> {
                sessionMapper.selectByFileIdForUpdate(fileId);
                referenceMapper.deleteByFileAndUser(fileId, PARTICIPANT);
            });
            cancelCommitted.countDown();
        });

        merge.start();
        cancel.start();
        merge.join(TimeUnit.SECONDS.toMillis(30));
        cancel.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(firstRead.get()).isEqualTo(2);
        // 取消已经提交，合并事务却依然看得见它——快照隔离的结果。
        assertThat(secondRead.get()).isEqualTo(2);
        assertThat(referenceMapper.countByFileId(fileId)).isEqualTo(1);
    }

    /**
     * 修复后的顺序：合并事务先取会话行锁。该锁与取消事务竞争，
     * 因此拿到锁之后的第一次一致性读必然包含此前已提交的取消。
     */
    @Test
    void lockingSessionRowFirstExposesTheCommittedCancellation() throws Exception {
        CountDownLatch cancelHoldsLock = new CountDownLatch(1);
        AtomicReference<Integer> participantsSeenByMerge = new AtomicReference<>();

        Thread cancel = new Thread(() -> tx.executeWithoutResult(status -> {
            sessionMapper.selectByFileIdForUpdate(fileId);
            referenceMapper.deleteByFileAndUser(fileId, PARTICIPANT);
            cancelHoldsLock.countDown();
            // 让合并线程先撞上会话行锁，再提交。
            sleepQuietly(300);
        }));

        Thread merge = new Thread(() -> {
            awaitQuietly(cancelHoldsLock);
            tx.executeWithoutResult(status -> {
                sessionMapper.selectByFileIdForUpdate(fileId);
                participantsSeenByMerge.set(referenceMapper.selectByFileId(fileId).size());
            });
        });

        cancel.start();
        merge.start();
        cancel.join(TimeUnit.SECONDS.toMillis(30));
        merge.join(TimeUnit.SECONDS.toMillis(30));

        // 会话行锁把两个事务串起来，合并读到的是取消之后的真实参与者集合。
        assertThat(participantsSeenByMerge.get()).isEqualTo(1);
    }

    /** 反向顺序：合并先拿到锁，取消必须等它结束，因此不会看到进行中的中间状态。 */
    @Test
    void cancelWaitsWhenMergeHoldsTheSessionRow() throws Exception {
        CountDownLatch mergeHoldsLock = new CountDownLatch(1);
        AtomicReference<Integer> participantsSeenByMerge = new AtomicReference<>();
        AtomicReference<UploadSessionStatus> statusSeenByCancel = new AtomicReference<>();

        Thread merge = new Thread(() -> tx.executeWithoutResult(status -> {
            sessionMapper.selectByFileIdForUpdate(fileId);
            participantsSeenByMerge.set(referenceMapper.selectByFileId(fileId).size());
            mergeHoldsLock.countDown();
            sleepQuietly(300);
            UploadSession completed = new UploadSession();
            completed.setFileId(fileId);
            completed.setStatus(UploadSessionStatus.COMPLETED);
            sessionMapper.update(completed, new com.baomidou.mybatisplus.core.conditions.update
                    .LambdaUpdateWrapper<UploadSession>().eq(UploadSession::getFileId, fileId));
        }));

        Thread cancel = new Thread(() -> {
            awaitQuietly(mergeHoldsLock);
            tx.executeWithoutResult(status ->
                    statusSeenByCancel.set(sessionMapper.selectByFileIdForUpdate(fileId).getStatus()));
        });

        merge.start();
        cancel.start();
        merge.join(TimeUnit.SECONDS.toMillis(30));
        cancel.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(participantsSeenByMerge.get()).isEqualTo(2);
        // 取消被阻塞到合并提交之后，看到 COMPLETED 就会拒绝释放预留。
        assertThat(statusSeenByCancel.get()).isEqualTo(UploadSessionStatus.COMPLETED);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for peer transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
