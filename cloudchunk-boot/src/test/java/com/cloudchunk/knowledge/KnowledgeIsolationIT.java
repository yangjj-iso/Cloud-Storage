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
        assertTrue(result.migrationsExecuted >= 8,"V8 must actually execute");
    }
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        service=new KnowledgeService(jdbc,mock(IntegrationEventPublisher.class)); index=new KnowledgeIndexStore(jdbc);
        jdbc.update("DELETE FROM knowledge_document"); jdbc.update("DELETE FROM user_file"); jdbc.update("DELETE FROM file_meta");
        jdbc.update("DELETE FROM ai_model_daily_usage");
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
    @Test void recycleDuringExtractionKeepsDerivedDataForRestoreWithoutExposingIt() {
        var c = claim();
        jdbc.update("UPDATE user_file SET status=1 WHERE file_id=?", FILE);
        publish(c, "restorable needle");
        assertEquals("READY", jdbc.queryForObject(
                "SELECT status FROM knowledge_document WHERE file_id=?", String.class, FILE));
        assertTrue(service.search(1, "restorable").evidence().isEmpty());
        assertTrue(service.search(2, "restorable").evidence().isEmpty());
        jdbc.update("UPDATE user_file SET status=0 WHERE id=101");
        assertEquals(1, service.search(1, "restorable").evidence().size());
        assertTrue(service.search(2, "restorable").evidence().isEmpty());
    }
    @Test void orphanedContentIsGarbageCollectedWithoutRemovingRecycledContent() {
        var c = claim(); publish(c, "private needle");
        jdbc.update("UPDATE user_file SET status=1 WHERE file_id=?", FILE);
        index.cleanDeleted();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class));
        jdbc.update("DELETE FROM user_file WHERE file_id=?", FILE);
        index.cleanDeleted();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class));
    }
    @Test void exactCitationLookupChecksCurrentOwnerVersionAndFileStatus() {
        var c = claim(); publish(c, "private citation text");
        assertNotNull(service.source(1, 101, c.generation(), 0));
        assertNull(service.source(2, 101, c.generation(), 0));
        assertNull(service.source(1, 102, c.generation(), 0));
        assertNull(service.source(1, 101, c.generation() + 1, 0));
        jdbc.update("UPDATE user_file SET status=1 WHERE id=101");
        assertNull(service.source(1, 101, c.generation(), 0));
        jdbc.update("UPDATE user_file SET status=0 WHERE id=101");
        assertNotNull(service.source(1, 101, c.generation(), 0));
        jdbc.update("UPDATE file_meta SET status=5 WHERE file_id=?", FILE);
        assertNull(service.source(1, 101, c.generation(), 0));
    }
    @Test void semanticIndexRequiresOwnerConsentAndSupportsRevocation() {
        var first=claim();publish(first,"sample text about encrypted uploads and retries");
        var gateway=mock(com.cloudchunk.core.knowledge.EmbeddingGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(gateway.modelKey()).thenReturn("a".repeat(64));
        when(gateway.dimensions()).thenReturn(8);
        when(gateway.embed(anyString())).thenReturn(new double[]{1,0,0,0,0,0,0,0});
        var semantic=new com.cloudchunk.core.knowledge.SemanticIndexService(
            jdbc,gateway,new com.fasterxml.jackson.databind.ObjectMapper(),
            new DataSourceTransactionManager(jdbc.getDataSource()),mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->semantic.request(1,101,false));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->semantic.request(3,101,true));
        assertEquals("PENDING",semantic.request(1,101,true).status());
        semantic.workOne();
        assertEquals("READY",semantic.status(1,101).status());
        var hybrid=new com.cloudchunk.core.knowledge.HybridSearchService(
            service,jdbc,gateway,new com.fasterxml.jackson.databind.ObjectMapper(),
            mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            mock(com.cloudchunk.core.knowledge.QdrantVectorStore.class),
            mock(com.cloudchunk.core.knowledge.QdrantMirrorService.class));
        var found=hybrid.search(1,"how do uploads work",true);
        assertEquals("HYBRID_RRF_LIMITED",found.mode());
        assertEquals("101",found.evidence().getFirst().userFileId());
        assertTrue(hybrid.search(2,"how do uploads work",true).evidence().isEmpty());
        jdbc.update("UPDATE user_file SET status=1 WHERE id=101");
        assertTrue(hybrid.search(1,"how do uploads work",true).evidence().isEmpty());
        jdbc.update("UPDATE user_file SET status=0 WHERE id=101");
        assertEquals(1,hybrid.search(1,"how do uploads work",true).evidence().size());
        semantic.revoke(1,101);
        assertEquals("NOT_REQUESTED",semantic.status(1,101).status());
        assertTrue(hybrid.search(1,"how do uploads work",true).evidence().isEmpty());
    }
    @Test void deletingDocumentCascadesEmbeddingVectorsAndGrants() {
        var first=claim();publish(first,"upload retry and processing");
        var gateway=mock(com.cloudchunk.core.knowledge.EmbeddingGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(gateway.modelKey()).thenReturn("b".repeat(64));
        when(gateway.dimensions()).thenReturn(8);
        when(gateway.embed(anyString())).thenReturn(new double[]{1,0,0,0,0,0,0,0});
        var semantic=new com.cloudchunk.core.knowledge.SemanticIndexService(
            jdbc,gateway,new com.fasterxml.jackson.databind.ObjectMapper(),
            new DataSourceTransactionManager(jdbc.getDataSource()),mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class));
        semantic.request(1,101,true);semantic.workOne();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_vector",Integer.class));
        jdbc.update("DELETE FROM knowledge_document WHERE file_id=?",FILE);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_vector",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_embedding_job",Integer.class));
    }
    @Test void dailyModelBudgetsAreAtomicAcrossParallelRequestsAndUsers() throws Exception {
        var env = new org.springframework.mock.env.MockEnvironment()
            .withProperty("cloudchunk.ai.budget.chat-calls-per-day","3")
            .withProperty("cloudchunk.ai.budget.embedding-calls-per-day","2");
        var guard = new com.cloudchunk.core.knowledge.AiDailyCallBudget(
            jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),env);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            var start=new java.util.concurrent.CountDownLatch(1);
            for(int i=0;i<24;i++) futures.add(pool.submit(()->{
                start.await();
                try {
                    guard.reserve(7L,com.cloudchunk.core.knowledge.AiDailyCallBudget.Kind.CHAT);
                    return true;
                } catch(com.cloudchunk.common.exception.BizException ex) {
                    assertEquals(com.cloudchunk.common.exception.ErrorCode.AI_BUDGET_EXHAUSTED,ex.getErrorCode());
                    return false;
                }
            }));
            start.countDown();
            int allowed=0;
            for(var future:futures) if(future.get()) allowed++;
            assertEquals(3,allowed,"concurrent requests cannot exceed a single UTC-day ceiling");
            assertEquals(0,guard.snapshot(7).chat().remaining());
            assertEquals(3,guard.snapshot(7).chat().used());
            assertEquals(3,guard.snapshot(8).chat().remaining(),"other users retain their quota");
            guard.reserve(7L,com.cloudchunk.core.knowledge.AiDailyCallBudget.Kind.EMBEDDING);
            guard.reserve(7L,com.cloudchunk.core.knowledge.AiDailyCallBudget.Kind.EMBEDDING);
            assertEquals(0,guard.snapshot(7).embedding().remaining());
            assertThrows(com.cloudchunk.common.exception.BizException.class,
                () -> guard.reserve(7L,com.cloudchunk.core.knowledge.AiDailyCallBudget.Kind.EMBEDDING));
        } finally {pool.shutdownNow();}
    }
    @Test void qdrantMirrorReconcilesAuthorizedVectorsAndPrunesRevokedPointers() throws Exception {
        var first=claim();publish(first,"private vector source");
        var gateway=mock(com.cloudchunk.core.knowledge.EmbeddingGateway.class);
        String modelKey="e".repeat(64);
        when(gateway.configured()).thenReturn(true);
        when(gateway.modelKey()).thenReturn(modelKey);
        when(gateway.dimensions()).thenReturn(8);
        when(gateway.embed(anyString())).thenReturn(new double[]{1,0,0,0,0,0,0,0});
        var semantic=new com.cloudchunk.core.knowledge.SemanticIndexService(
            jdbc,gateway,new com.fasterxml.jackson.databind.ObjectMapper(),
            new DataSourceTransactionManager(jdbc.getDataSource()),
            mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class));
        semantic.request(1,101,true);semantic.workOne();
        var store=mock(com.cloudchunk.core.knowledge.QdrantVectorStore.class);
        when(store.enabled()).thenReturn(true);
        var mirror=new com.cloudchunk.core.knowledge.QdrantMirrorService(
            jdbc,gateway,store,new com.fasterxml.jackson.databind.ObjectMapper());
        assertFalse(mirror.readyForUser(1));
        assertEquals("SYNCING",mirror.healthForUser(1).state());
        mirror.syncOne();
        verify(store).upsert(eq(modelKey),eq(8),argThat(points->
            points.size()==1 && points.getFirst().userId()==1 &&
            points.getFirst().fileId().equals(FILE)));
        assertTrue(mirror.readyForUser(1));
        when(store.collectionAvailable(modelKey,8)).thenReturn(true);
        var ownHealth=mirror.healthForUser(1);
        assertEquals("READY",ownHealth.state());
        assertEquals(1,ownHealth.readyDocuments());
        assertEquals(1,ownHealth.mirroredDocuments());
        assertEquals(0,ownHealth.pendingDocuments());
        assertEquals("NO_INDEX",mirror.healthForUser(2).state());
        when(store.collectionAvailable(modelKey,8)).thenReturn(false);
        assertEquals("DEGRADED",mirror.healthForUser(1).state());
        assertFalse(mirror.readyForUser(2),"unconsented second owner is never mirrored");
        String pointId=com.cloudchunk.core.knowledge.QdrantVectorStore
            .pointId(1L,FILE,first.generation(),0,modelKey);
        semantic.revoke(1,101);
        assertEquals("NO_INDEX",mirror.healthForUser(1).state());
        assertFalse(mirror.readyForUser(1));
        when(store.scroll(eq(modelKey),isNull(),eq(40)))
            .thenReturn(new com.cloudchunk.core.knowledge.QdrantVectorStore.Page(
                java.util.List.of(new com.cloudchunk.core.knowledge.QdrantVectorStore.Hit(
                    pointId,1L,FILE,first.generation(),0,0.7)),
                com.fasterxml.jackson.databind.node.NullNode.getInstance()));
        mirror.pruneOne();
        verify(store).delete(eq(modelKey),eq(java.util.List.of(pointId)));
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
