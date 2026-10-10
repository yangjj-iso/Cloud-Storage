package com.cloudchunk.integration;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.drive.mapper.UserFileMapper;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.mapper.FileReferenceMapper;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.quota.service.QuotaService;
import com.cloudchunk.contract.storage.ObjectDeletionQueue;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real InnoDB + MyBatis: the unique index is the concurrency authority for every writer. */
@Testcontainers(disabledWithoutDocker=true)
class DriveNameUniquenessIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("cloudchunk").withUsername("cloudchunk").withPassword("cloudchunk");
    private static HikariDataSource ds;
    private static JdbcTemplate jdbc;
    private static UserFileService drive;

    @BeforeAll static void setup() throws Exception {
        ds=MybatisTestSupport.migratedDataSource(MYSQL,32);
        jdbc=new JdbcTemplate(ds);
        SqlSessionFactory factory=MybatisTestSupport.sessionFactory(ds,UserFileMapper.class);
        var mapper=new SqlSessionTemplate(factory).getMapper(UserFileMapper.class);
        drive=new UserFileService(mapper,mock(FileMetaService.class),
            mock(FileReferenceMapper.class),mock(QuotaService.class),mock(ObjectDeletionQueue.class));
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND table_name='user_file'
              AND index_name='uk_drive_active_sibling'
            """,Integer.class);
        assertNotNull(count);
        assertTrue(count>0,"Flyway V13 must install unique constraint");
    }
    @AfterAll static void close(){if(ds!=null)ds.close();}

    @Test void mkdirRenameMoveAndRecycleRestoreHaveOneConsistentNamespace() {
        long userId=71001;
        var first=drive.mkdir(userId,0,"Reports");
        var other=drive.mkdir(userId,0,"Notes");
        assertEquals(ErrorCode.DRIVE_NAME_CONFLICT,
            assertThrows(BizException.class,()->drive.mkdir(userId,0,"reports")).getErrorCode());
        assertEquals(ErrorCode.DRIVE_NAME_CONFLICT,
            assertThrows(BizException.class,()->drive.rename(other.id(),userId,"REPORTS")).getErrorCode());

        var target=drive.mkdir(userId,0,"Target");
        var present=drive.mkdir(userId,target.id(),"duplicate");
        var moving=drive.mkdir(userId,0,"duplicate");
        assertEquals(ErrorCode.DRIVE_NAME_CONFLICT,
            assertThrows(BizException.class,()->drive.move(moving.id(),userId,target.id())).getErrorCode());
        assertEquals(0L,drive.getFileInfo(moving.id(),userId).parentId());

        drive.moveToRecycleBin(present.id(),userId);
        drive.move(moving.id(),userId,target.id());
        assertEquals(ErrorCode.DRIVE_NAME_CONFLICT,
            assertThrows(BizException.class,()->drive.restore(present.id(),userId)).getErrorCode());
        assertEquals(1,jdbc.queryForObject("SELECT status FROM user_file WHERE id=?",Integer.class,present.id()));
        drive.moveToRecycleBin(moving.id(),userId);
        drive.restore(present.id(),userId);
        assertEquals(0,jdbc.queryForObject("SELECT status FROM user_file WHERE id=?",Integer.class,present.id()));

        // Different owners and different folders may freely use the same display name.
        drive.mkdir(71002,0,"Reports");
        drive.mkdir(userId,first.id(),"Reports");
    }

    @Test void autoImportsDoNotFailOrOverwriteOnExistingDisplayName() {
        long userId=71003;
        drive.mkdir(userId,0,"Incoming");
        assertEquals("Incoming (1)",drive.mkdirImported(userId,0,"Incoming").fileName());
        String a="a".repeat(32),b="b".repeat(32);
        drive.createFileEntryIfAbsent(userId,0,a,"report.txt",1);
        drive.createFileEntryIfAbsent(userId,0,b,"REPORT.TXT",1);
        drive.createFileEntryIfAbsent(userId,0,a,"report.txt",1); // idempotent, not a third entry
        List<String> names=jdbc.query("""
            SELECT file_name FROM user_file WHERE user_id=? AND parent_id=0 AND status=0 AND is_dir=0
            ORDER BY id
            """,(rs,n)->rs.getString(1),userId);
        assertEquals(2,names.size());
        assertTrue(names.contains("report.txt"));
        assertTrue(names.contains("REPORT (1).TXT"));
    }

    @Test void auditHistoryReturnsOnlyCurrentUsersOperationsAndNoRequestBodies() {
        long alice=72001L,bob=72002L;
        jdbc.update("""
            INSERT INTO op_log(user_id,op_type,target_id,result,cost_ms,created_at)
            VALUES(?, 'DRIVE_RENAME','/api/v1/drive/rename',1,12,NOW(3)),
                  (?, 'AI_MOVE_UNDO','/api/v1/knowledge/assistant/move/abc/undo',0,34,NOW(3)),
                  (?, 'DRIVE_PURGE','/api/v1/recycle/55',1,5,NOW(3)),
                  (?, 'DRIVE_POST','/api/v1/drive/rename',1,3,NOW(3))
            """,alice,alice,bob,alice);
        var history=new com.cloudchunk.core.audit.service.DriveOperationHistory(jdbc);
        var aliceEvents=history.recent(alice);
        var bobEvents=history.recent(bob);
        assertEquals(2,aliceEvents.size());
        assertTrue(aliceEvents.stream().anyMatch(e->e.operation().equals("DRIVE_RENAME") && e.success()));
        assertTrue(aliceEvents.stream().anyMatch(e->e.operation().equals("AI_MOVE_UNDO") && !e.success()));
        assertEquals(1,bobEvents.size());
        assertEquals("DRIVE_PURGE",bobEvents.getFirst().operation());
        assertTrue(history.recent(72003L).isEmpty());
    }

    @Test void competingDirectoryCreatesCannotBothWinUnderRealMySql() throws Exception {
        long userId=71004;
        int competitors=24;
        var executor=Executors.newFixedThreadPool(12);
        var start=new CountDownLatch(1);
        var done=new CountDownLatch(competitors);
        var successes=new AtomicInteger();
        var conflicts=new AtomicInteger();
        var unexpected=new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        try {
            for(int i=0;i<competitors;i++)executor.execute(()->{
                try {
                    start.await();
                    drive.mkdir(userId,0,"Concurrent");
                    successes.incrementAndGet();
                }catch(BizException e){
                    if(e.getErrorCode()==ErrorCode.DRIVE_NAME_CONFLICT)conflicts.incrementAndGet();
                    else unexpected.add(e);
                }catch(Throwable t){unexpected.add(t);}
                finally{done.countDown();}
            });
            start.countDown();
            assertTrue(done.await(60,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();}
        assertTrue(unexpected.isEmpty(),unexpected.toString());
        assertEquals(1,successes.get());
        assertEquals(competitors-1,conflicts.get());
        assertEquals(1,jdbc.queryForObject("""
            SELECT COUNT(*) FROM user_file WHERE user_id=? AND parent_id=0 AND status=0
              AND file_name='Concurrent'
            """,Integer.class,userId));
    }

    @Test void competingAutomaticImportsReceiveDistinctNonDestructiveSuffixes() throws Exception {
        long userId=71005;
        int competitors=12;
        var pool=Executors.newFixedThreadPool(12);
        var start=new CountDownLatch(1);
        var done=new CountDownLatch(competitors);
        var unexpected=new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        try {
            for(int i=0;i<competitors;i++){
                final String physical=String.format("%032x",i+1);
                pool.submit(()->{
                    try{
                        start.await();
                        drive.createFileEntryIfAbsent(userId,0,physical,"shared.txt",1);
                    }catch(Throwable t){unexpected.add(t);}
                    finally{done.countDown();}
                });
            }
            start.countDown();
            assertTrue(done.await(90,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
        assertTrue(unexpected.isEmpty(),unexpected.toString());
        List<String> names=jdbc.query("""
            SELECT file_name FROM user_file WHERE user_id=? AND status=0 AND is_dir=0 ORDER BY id
            """,(rs,n)->rs.getString(1),userId);
        assertEquals(competitors,names.size());
        assertEquals(competitors,new HashSet<>(names).size());
        assertTrue(names.contains("shared.txt"));
    }
}
