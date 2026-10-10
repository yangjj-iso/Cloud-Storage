package com.cloudchunk.knowledge;

import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.core.knowledge.KnowledgeIndexStore;
import com.cloudchunk.core.knowledge.KnowledgeService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class KnowledgeIsolationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("cloudchunk").withUsername("cloudchunk").withPassword("cloudchunk");
    private static final String FILE="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private JdbcTemplate jdbc;
    private KnowledgeService service;
    private KnowledgeIndexStore index;
    private TransactionTemplate tx;
    @BeforeAll static void migrate() {
        var result=Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())
            .locations("classpath:db/migration").load().migrate();
        assertTrue(result.success);
        assertTrue(result.migrationsExecuted >= 5,"V5 must actually execute");
    }
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        service=new KnowledgeService(jdbc,mock(IntegrationEventPublisher.class)); index=new KnowledgeIndexStore(jdbc);
        jdbc.update("DELETE FROM knowledge_document"); jdbc.update("DELETE FROM user_file"); jdbc.update("DELETE FROM file_meta");
        jdbc.update("""
            INSERT INTO file_meta(file_id,file_md5,file_name,file_size,mime_type,bucket,object_key,owner_id,status)
            VALUES (?,?,'private.txt',30,'text/plain','bucket','object',1,2)
            """,FILE,FILE);
        jdbc.update("INSERT INTO user_file(id,user_id,file_id,file_name) VALUES (101,1,?,'Alice.txt'),(102,2,?,'Bob.txt')",FILE,FILE);
    }
    private KnowledgeIndexStore.Claim claim() { return tx.execute(s -> index.claim(FILE)); }
    private void publish(KnowledgeIndexStore.Claim c,String text) {
        tx.executeWithoutResult(s -> index.publish(c,text,"READY",null));
    }
    @Test void liveOwnershipGatesSearchAndEvidenceEvenWhenPhysicalContentIsShared() {
        var c=claim(); publish(c,"needle: shared content");
        assertEquals(1,service.search(1,"needle").evidence().size());
        assertTrue(service.search(3,"needle").evidence().isEmpty());
        assertThrows(RuntimeException.class,() -> service.blocks(1,102,c.generation(),1));
        jdbc.update("UPDATE user_file SET status=1 WHERE id=101");
        assertTrue(service.search(1,"needle").evidence().isEmpty());
        assertThrows(RuntimeException.class,() -> service.blocks(1,101,c.generation(),1));
        assertEquals(1,service.search(2,"needle").evidence().size());
        jdbc.update("UPDATE user_file SET status=0,file_name='renamed.txt' WHERE id=101");
        assertEquals("renamed.txt",service.search(1,"needle").evidence().getFirst().fileName());
        jdbc.update("UPDATE file_meta SET status=5 WHERE file_id=?",FILE);
        assertTrue(service.search(2,"needle").evidence().isEmpty());
        index.cleanDeleted();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk",Integer.class));
    }
    @Test void anExpiredWorkerCannotPublishOverItsReplacement() {
        var old=claim();
        assertThrows(IllegalStateException.class,this::claim);
        jdbc.update("UPDATE knowledge_document SET lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
        var next=claim();
        publish(old,"obsolete needle");
        assertTrue(service.search(1,"needle").evidence().isEmpty());
        publish(next,"current needle");
        assertEquals("current needle",service.search(1,"needle").evidence().getFirst().content());
        assertTrue(service.blocks(1,101,old.generation(),1).blocks().isEmpty());
        publish(old,"late needle");
        assertEquals("current needle",service.search(1,"needle").evidence().getFirst().content());
        assertNull(claim());
    }
    @Test void publicationRollsBackAsAUnitAndWildcardsRemainLiteral() {
        var c=claim();
        assertThrows(IllegalStateException.class,() -> tx.executeWithoutResult(s -> {
            index.publish(c,"needle","READY",null); throw new IllegalStateException("rollback");
        }));
        assertTrue(service.search(1,"needle").evidence().isEmpty());
        publish(c,"needle 50%_ literal");
        assertEquals(1,service.search(1,"%_").evidence().size());
        assertTrue(service.search(1,"%x").evidence().isEmpty());
    }
    @Test void anExhaustedLeaseBecomesRetryableFailureInsteadOfBeingBusyForever() {
        claim();
        jdbc.update("UPDATE knowledge_document SET attempt_count=3,lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
        tx.executeWithoutResult(s -> service.recoverPending());
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM knowledge_document",String.class));
        assertNull(claim());
    }
}
