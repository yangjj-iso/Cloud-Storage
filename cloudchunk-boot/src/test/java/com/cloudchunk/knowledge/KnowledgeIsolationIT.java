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
        assertTrue(result.migrationsExecuted >= 16,"V16 strategy recommendation feedback migration must execute on fresh MySQL");
    }
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        service=new KnowledgeService(jdbc,mock(IntegrationEventPublisher.class)); index=new KnowledgeIndexStore(jdbc);
        jdbc.update("DELETE FROM ai_strategy_recommendation");
        jdbc.update("DELETE FROM ai_organize_run_event");
        jdbc.update("DELETE FROM ai_organize_run_step");
        jdbc.update("DELETE FROM ai_organize_run");
        jdbc.update("DELETE FROM ai_organize_template");
        jdbc.update("DELETE FROM ai_agent_workflow_event");
        jdbc.update("DELETE FROM ai_agent_workflow_step");
        jdbc.update("DELETE FROM ai_agent_workflow");
        jdbc.update("DELETE FROM ai_rename_proposal");
        jdbc.update("DELETE FROM ai_move_proposal");
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
    @Test void aiStrategyRecommendationChecksTemplateOwnershipBeforeAgentAndFeedbackIsPrivate() {
        jdbc.update("INSERT INTO user_file(id,user_id,file_name,is_dir,parent_id) VALUES (200,1,'Research',1,0)");
        jdbc.update("INSERT INTO user_file(id,user_id,file_name,is_dir,parent_id) VALUES (201,2,'PrivateFolder',1,0)");
        var move=mock(com.cloudchunk.core.knowledge.AiMoveAgentService.class);
        var strategy=new com.cloudchunk.core.knowledge.OrganizationTemplateService(
                jdbc,mock(com.cloudchunk.core.drive.service.UserFileService.class));
        var template=strategy.create(1,new com.cloudchunk.core.knowledge.OrganizationTemplateService.CreateTemplate(
                "Sort text notes","TEXT","200"));
        var foreign=strategy.create(2,new com.cloudchunk.core.knowledge.OrganizationTemplateService.CreateTemplate(
                "Other owner's folder","TEXT","201"));
        var claim=claim();publish(claim,"Text describing safe data recovery.");
        when(move.capabilities()).thenReturn(
                new com.cloudchunk.core.knowledge.AiMoveAgentService.Capabilities(
                    true,"SINGLE_FILE_MOVE_WITH_CONFIRMATION"));
        String proposalId="11111111-1111-4111-8111-111111111111";
        when(move.suggest(eq(1L),any())).thenAnswer(inv->{
            var request=inv.getArgument(1,com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest.class);
            assertEquals(java.util.List.of(200L),request.candidateFolderIds());
            assertTrue(request.consent());
            insertMoveProposal(proposalId,1,101,"Alice.txt");
            jdbc.update("UPDATE ai_move_proposal SET destination_id=200,destination_name='Research' WHERE id=?",proposalId);
            return new com.cloudchunk.core.knowledge.AiMoveAgentService.Proposal(
                    proposalId,"101","Alice.txt",0,200L,"Research","2099-01-01T00:00:00","PENDING");
        });
        var ai=new com.cloudchunk.core.knowledge.AiStrategyRecommendationService(jdbc,service,move);
        var ref=new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("101",claim.generation(),0);
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                ai.suggest(1,new com.cloudchunk.core.knowledge.AiStrategyRecommendationService.SuggestRequest(
                        ref,java.util.List.of(template.id(),foreign.id()),true)));
        verify(move,never()).suggest(anyLong(),any());

        var recommendation=ai.suggest(1,
                new com.cloudchunk.core.knowledge.AiStrategyRecommendationService.SuggestRequest(
                    ref,java.util.List.of(template.id()),true));
        assertEquals(template.id(),recommendation.templateId());
        assertEquals("PENDING",recommendation.state());
        assertEquals(1,ai.quality(1).unrated());
        assertEquals(1,ai.recent(1).size());
        assertTrue(ai.recent(2).isEmpty());
        assertEquals(0,ai.quality(2).unrated());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                ai.rate(2,recommendation.id(),
                    new com.cloudchunk.core.knowledge.AiStrategyRecommendationService.RateRequest(
                        "ACCEPTED","USEFUL",true)));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                ai.rate(1,recommendation.id(),
                    new com.cloudchunk.core.knowledge.AiStrategyRecommendationService.RateRequest(
                        "REJECTED","anything raw",true)));
        var rated=ai.rate(1,recommendation.id(),
                new com.cloudchunk.core.knowledge.AiStrategyRecommendationService.RateRequest(
                    "REJECTED","WRONG_FOLDER",true));
        assertEquals("REJECTED",rated.state());
        assertEquals("WRONG_FOLDER",rated.feedbackCode());
        assertEquals(1,ai.quality(1).rejected());
        assertNull(ai.quality(1).acceptanceRatio());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                ai.rate(1,recommendation.id(),
                    new com.cloudchunk.core.knowledge.AiStrategyRecommendationService.RateRequest(
                        "ACCEPTED","USEFUL",true)));
        // Feedback and the template recommendation never call UserFileService.move.
        verify(move,times(1)).suggest(eq(1L),any());
        jdbc.update("UPDATE user_file SET status=1 WHERE id=101");
        assertTrue(ai.recent(1).isEmpty());
    }

    @Test void aiStrategyRecommendationRejectsCategoryMismatchBeforeModelAndNoAuditRowWritten() {
        jdbc.update("INSERT INTO user_file(id,user_id,file_name,is_dir,parent_id) VALUES (200,1,'Research',1,0)");
        var policy=new com.cloudchunk.core.knowledge.OrganizationTemplateService(
                jdbc,mock(com.cloudchunk.core.drive.service.UserFileService.class))
                .create(1,new com.cloudchunk.core.knowledge.OrganizationTemplateService.CreateTemplate(
                        "Only PDFs","PDF","200"));
        var claim=claim();publish(claim,"Some text content");
        var move=mock(com.cloudchunk.core.knowledge.AiMoveAgentService.class);
        when(move.capabilities()).thenReturn(
                new com.cloudchunk.core.knowledge.AiMoveAgentService.Capabilities(true,"MOVE"));
        var ai=new com.cloudchunk.core.knowledge.AiStrategyRecommendationService(jdbc,service,move);
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                ai.suggest(1,new com.cloudchunk.core.knowledge.AiStrategyRecommendationService.SuggestRequest(
                    new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("101",claim.generation(),0),
                    java.util.List.of(policy.id()),true)));
        verify(move,never()).suggest(anyLong(),any());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_strategy_recommendation",Integer.class));
    }

    @Test void organizingTemplateSimulationBlocksCrossAccountAndRequiresSeparateApproval() {
        jdbc.update("INSERT INTO user_file(id,user_id,file_name,is_dir,parent_id) VALUES (200,1,'Organized',1,0)");
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        var policies=new com.cloudchunk.core.knowledge.OrganizationTemplateService(jdbc,drive);
        var policy=policies.create(1,new com.cloudchunk.core.knowledge.OrganizationTemplateService.CreateTemplate(
                "Text to Organized","TEXT","200"));
        assertEquals("Organized",policy.destinationName());
        assertEquals(1,policies.templates(1).size());
        assertTrue(policies.templates(2).isEmpty());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->policies.simulate(2,
                new com.cloudchunk.core.knowledge.OrganizationTemplateService.SimulateRequest(
                        policy.id(),java.util.List.of("102"))));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->policies.simulate(1,
                new com.cloudchunk.core.knowledge.OrganizationTemplateService.SimulateRequest(
                        policy.id(),java.util.List.of("101","102"))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_organize_run",Integer.class));
        var plan=policies.simulate(1,
                new com.cloudchunk.core.knowledge.OrganizationTemplateService.SimulateRequest(
                        policy.id(),java.util.List.of("101")));
        assertEquals("READY",plan.steps().getFirst().state());
        assertEquals("ACTIVE",plan.status());
        assertEquals(1,plan.matched());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->policies.detail(2,plan.id()));
        var yes=new com.cloudchunk.core.knowledge.OrganizationTemplateService.Confirm(true);
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policies.execute(1,plan.id(),1,yes));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policies.approve(1,plan.id(),1,
                        new com.cloudchunk.core.knowledge.OrganizationTemplateService.Confirm(false)));
        assertEquals("APPROVED",policies.approve(1,plan.id(),1,yes).steps().getFirst().state());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policies.approve(1,plan.id(),1,yes));
        doAnswer(inv -> {
            jdbc.update("UPDATE user_file SET parent_id=200 WHERE id=101 AND user_id=1");
            return null;
        }).when(drive).move(101L,1L,200L);
        var applied=policies.execute(1,plan.id(),1,yes);
        assertEquals("APPLIED",applied.steps().getFirst().state());
        assertEquals("COMPLETED",applied.status());
        assertEquals(200L,jdbc.queryForObject(
                "SELECT parent_id FROM user_file WHERE id=101",Long.class));
        verify(drive,times(1)).move(101L,1L,200L);
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policies.execute(1,plan.id(),1,yes));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policies.cancel(1,plan.id(),yes));
        assertEquals(3,jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_organize_run_event WHERE run_id=?",Integer.class,plan.id()));
    }

    @Test void organizingDryRunExposesConflictsAndExecutionBlocksStaleSnapshots() {
        jdbc.update("INSERT INTO user_file(id,user_id,file_name,is_dir,parent_id) VALUES (200,1,'Destination',1,0)");
        jdbc.update("INSERT INTO user_file(id,user_id,file_id,file_name,is_dir,parent_id) VALUES (202,1,?,'Alice.txt',0,200)",FILE);
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        var policyService=new com.cloudchunk.core.knowledge.OrganizationTemplateService(jdbc,drive);
        var policy=policyService.create(1,
                new com.cloudchunk.core.knowledge.OrganizationTemplateService.CreateTemplate(
                        "Text files","TEXT","200"));
        var yes=new com.cloudchunk.core.knowledge.OrganizationTemplateService.Confirm(true);
        var blocked=policyService.simulate(1,
                new com.cloudchunk.core.knowledge.OrganizationTemplateService.SimulateRequest(
                        policy.id(),java.util.List.of("101")));
        assertEquals("NAME_CONFLICT",blocked.steps().getFirst().state());
        assertEquals(1,blocked.blocked());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policyService.approve(1,blocked.id(),1,yes));
        assertEquals("COMPLETED",policyService.skip(1,blocked.id(),1,yes).status());
        verifyNoInteractions(drive);

        jdbc.update("DELETE FROM user_file WHERE id=202");
        var fresh=policyService.simulate(1,
                new com.cloudchunk.core.knowledge.OrganizationTemplateService.SimulateRequest(
                        policy.id(),java.util.List.of("101")));
        policyService.approve(1,fresh.id(),1,yes);
        jdbc.update("UPDATE user_file SET file_name='Changed.txt',updated_at=DATE_ADD(updated_at,INTERVAL 1 SECOND) WHERE id=101");
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policyService.execute(1,fresh.id(),1,yes));
        verifyNoInteractions(drive);
        assertEquals("APPROVED",policyService.detail(1,fresh.id()).steps().getFirst().state());
        assertEquals("CANCELLED",policyService.cancel(1,fresh.id(),yes).status());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policyService.execute(1,fresh.id(),1,yes));
        policyService.archive(1,policy.id(),yes);
        assertTrue(policyService.templates(1).isEmpty());
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
                policyService.simulate(1,new com.cloudchunk.core.knowledge.OrganizationTemplateService.SimulateRequest(
                        policy.id(),java.util.List.of("101"))));
        verifyNoInteractions(drive);
    }

    @Test void durableAgentPlanRequiresOwnershipAndSequentialExplicitActions() {
        // All proposals are synthetic; existing AiMoveAgentService tests cover actual
        // file state, row-lock, sibling conflict and 15-minute conditional undo.
        var move = mock(com.cloudchunk.core.knowledge.AiMoveAgentService.class);
        var planService = new com.cloudchunk.core.knowledge.AiWorkflowService(jdbc,move);
        var p1 = "11111111-1111-4111-8111-111111111111";
        var p2 = "22222222-2222-4222-8222-222222222222";
        var foreign = "33333333-3333-4333-8333-333333333333";
        jdbc.update("INSERT INTO user_file(id,user_id,file_id,file_name) VALUES (103,1,?,'AliceSecond.txt')",FILE);
        insertMoveProposal(p1,1,101,"Alice.txt");
        insertMoveProposal(p2,1,103,"AliceSecond.txt");
        insertMoveProposal(foreign,2,102,"Bob.txt");

        assertThrows(com.cloudchunk.common.exception.BizException.class, () ->
            planService.create(1,new com.cloudchunk.core.knowledge.AiWorkflowService.CreateRequest(
                java.util.List.of(p1,foreign),true)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_workflow",Integer.class));

        var created = planService.create(1,
            new com.cloudchunk.core.knowledge.AiWorkflowService.CreateRequest(
                java.util.List.of(p1,p2),true));
        String id=created.summary().id();
        assertEquals("ACTIVE",created.summary().status());
        assertEquals(2,created.summary().pending());
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            () -> planService.detail(2,id));
        var yes = new com.cloudchunk.core.knowledge.AiWorkflowService.ConfirmRequest(true);
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            () -> planService.approve(1,id,2,yes));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            () -> planService.execute(1,id,1,yes));
        assertEquals("APPROVED",planService.approve(1,id,1,yes).steps().getFirst().state());
        assertEquals("APPLIED",planService.execute(1,id,1,yes).steps().getFirst().state());
        verify(move).apply(eq(1L),eq(p1),any());
        assertEquals("REVERTED",planService.undo(1,id,1,yes).steps().getFirst().state());
        verify(move).undo(eq(1L),eq(p1),any());
        planService.approve(1,id,2,yes);
        var completed=planService.skip(1,id,2,yes);
        assertEquals("COMPLETED",completed.summary().status());
        assertEquals(6,jdbc.queryForObject(
            "SELECT COUNT(*) FROM ai_agent_workflow_event WHERE workflow_id=?",Integer.class,id));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            () -> planService.execute(1,id,2,yes));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            () -> planService.cancel(1,id,yes));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            () -> planService.cancel(2,id,yes));
    }

    @Test void cancelledAgentPlanCannotApplyButCanUndoEarlierApprovedMove() {
        var move=mock(com.cloudchunk.core.knowledge.AiMoveAgentService.class);
        var planner=new com.cloudchunk.core.knowledge.AiWorkflowService(jdbc,move);
        var p1="44444444-4444-4444-8444-444444444444";
        var p2="55555555-5555-4555-8555-555555555555";
        jdbc.update("INSERT INTO user_file(id,user_id,file_id,file_name) VALUES (103,1,?,'Another.txt')",FILE);
        insertMoveProposal(p1,1,101,"Alice.txt");
        insertMoveProposal(p2,1,103,"Another.txt");
        var confirmed=new com.cloudchunk.core.knowledge.AiWorkflowService.ConfirmRequest(true);
        String id=planner.create(1,new com.cloudchunk.core.knowledge.AiWorkflowService.CreateRequest(
            java.util.List.of(p1,p2),true)).summary().id();
        planner.approve(1,id,1,confirmed);
        planner.execute(1,id,1,confirmed);
        assertEquals("CANCELLED",planner.cancel(1,id,confirmed).summary().status());
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            () -> planner.approve(1,id,2,confirmed));
        assertEquals("REVERTED",planner.undo(1,id,1,confirmed).steps().getFirst().state());
        verify(move).undo(eq(1L),eq(p1),any());
        verify(move,never()).apply(eq(1L),eq(p2),any());
    }

    private void insertMoveProposal(String id,long userId,long entry,String name) {
        jdbc.update("""
            INSERT INTO ai_move_proposal(id,user_id,user_file_id,generation,chunk_index,
                original_name,source_parent_id,source_updated_at,destination_id,
                destination_name,destination_parent_id,destination_updated_at,expires_at)
            VALUES (?,?,?,1,0,?,0,NOW(3),900,'Synthetic folder',0,
                    NOW(3),DATE_ADD(NOW(3),INTERVAL 10 MINUTE))
            """,id,userId,entry,name);
    }

    @Test void researchBriefRechecksActualOwnerAndGenerationAtExportTime() {
        var c = claim();
        publish(c, "citation text for export only");
        var brief = new com.cloudchunk.core.knowledge.ResearchBriefService(service);
        var ref = new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("101", c.generation(), 0);
        var data = brief.prepare(1, java.util.List.of(ref));
        assertEquals("EXACT_AUTHORIZED_EXCERPTS", data.mode());
        assertEquals("citation text for export only", data.evidence().getFirst().content());
        assertEquals(com.cloudchunk.common.exception.ErrorCode.AI_SOURCE_CHANGED,
            assertThrows(com.cloudchunk.common.exception.BizException.class,
                () -> brief.prepare(2, java.util.List.of(ref))).getErrorCode());
        jdbc.update("UPDATE user_file SET status=1 WHERE id=101");
        assertEquals(com.cloudchunk.common.exception.ErrorCode.AI_SOURCE_CHANGED,
            assertThrows(com.cloudchunk.common.exception.BizException.class,
                () -> brief.prepare(1, java.util.List.of(ref))).getErrorCode());
        jdbc.update("UPDATE user_file SET status=0 WHERE id=101");
        assertEquals(com.cloudchunk.common.exception.ErrorCode.AI_SOURCE_CHANGED,
            assertThrows(com.cloudchunk.common.exception.BizException.class,
                () -> brief.prepare(1, java.util.List.of(
                    new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("101", c.generation()+1, 0)))).getErrorCode());
    }

    @Test void bulkIndexIsAllOrNothingForUnownedEntriesAndBounded() {
        // Both entries reference one physical object, but only entry 101 belongs to user 1.
        assertThrows(com.cloudchunk.common.exception.BizException.class, () ->
            service.requestIndexBatch(1, java.util.List.of("101", "102")));
        assertEquals(0, jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_document", Integer.class));
        assertThrows(com.cloudchunk.common.exception.BizException.class, () ->
            service.requestIndexBatch(1, java.util.List.of("101", "101")));
        assertThrows(com.cloudchunk.common.exception.BizException.class, () ->
            service.requestIndexBatch(1, java.util.List.of()));
        assertThrows(com.cloudchunk.common.exception.BizException.class, () ->
            service.requestIndexBatch(1, java.util.List.of("101", "-1")));
        assertThrows(com.cloudchunk.common.exception.BizException.class, () ->
            service.requestIndexBatch(1, java.util.List.of("9223372036854775808")));
        var result = service.requestIndexBatch(1, java.util.List.of("101"));
        assertEquals(1, result.accepted());
        assertEquals(8, result.maxBatchSize());
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM knowledge_document", Integer.class));
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
        when(gateway.embedWithUsage(anyString())).thenReturn(
            new com.cloudchunk.core.knowledge.EmbeddingGateway.Embedding(
                new double[]{1,0,0,0,0,0,0,0},
                com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
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
        when(gateway.embedWithUsage(anyString())).thenReturn(
            new com.cloudchunk.core.knowledge.EmbeddingGateway.Embedding(
                new double[]{1,0,0,0,0,0,0,0},
                com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
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
        guard.recordReported(7L,com.cloudchunk.core.knowledge.AiDailyCallBudget.Kind.CHAT,
            new com.cloudchunk.core.knowledge.ModelTokenUsage(120,30,true));
        guard.recordReported(7L,com.cloudchunk.core.knowledge.AiDailyCallBudget.Kind.EMBEDDING,
            new com.cloudchunk.core.knowledge.ModelTokenUsage(9,0,true));
        guard.recordReported(7L,com.cloudchunk.core.knowledge.AiDailyCallBudget.Kind.EMBEDDING,
            com.cloudchunk.core.knowledge.ModelTokenUsage.unknown());
        assertEquals(1,guard.snapshot(7).chat().reportedCalls());
        assertEquals(120,guard.snapshot(7).chat().inputTokens());
        assertEquals(30,guard.snapshot(7).chat().outputTokens());
        assertEquals(1,guard.snapshot(7).embedding().reportedCalls());
        assertEquals(9,guard.snapshot(7).embedding().inputTokens());
        assertNull(guard.snapshot(7).chat().estimatedUsd(),"without prices estimate is unknown");
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
        when(gateway.embedWithUsage(anyString())).thenReturn(
            new com.cloudchunk.core.knowledge.EmbeddingGateway.Embedding(
                new double[]{1,0,0,0,0,0,0,0},
                com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var semantic=new com.cloudchunk.core.knowledge.SemanticIndexService(
            jdbc,gateway,new com.fasterxml.jackson.databind.ObjectMapper(),
            new DataSourceTransactionManager(jdbc.getDataSource()),
            mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class));
        semantic.request(1,101,true);semantic.workOne();
        var store=mock(com.cloudchunk.core.knowledge.QdrantVectorStore.class);
        var mirror=new com.cloudchunk.core.knowledge.QdrantMirrorService(
            jdbc,gateway,store,new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals("MYSQL_ONLY",mirror.healthForUser(1).state());
        assertEquals(0,mirror.healthForUser(1).pendingDocuments());
        when(store.enabled()).thenReturn(true);
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
    @Test void aiRenameRequiresConsentAndSecondConfirmationAndLeavesAnAuditRow() {
        var c=claim();publish(c,"Content about uploading verified chunks");
        var agentGateway=mock(com.cloudchunk.core.knowledge.ChatGateway.class);
        when(agentGateway.configured()).thenReturn(true);
        when(agentGateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new com.cloudchunk.core.knowledge.ChatGateway.Completion(
                "安全上传说明.txt [S1]",com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        doAnswer(inv->{
            jdbc.update("UPDATE user_file SET file_name=? WHERE id=101 AND user_id=1",new Object[]{inv.getArgument(2)});
            return null;
        }).when(drive).rename(eq(101L),eq(1L),anyString());
        var agent=new com.cloudchunk.core.knowledge.AiRenameAgentService(
            service,drive,agentGateway,mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            jdbc,new org.springframework.mock.env.MockEnvironment()
                .withProperty("cloudchunk.ai.agent.rename-enabled","true"));
        var request=new com.cloudchunk.core.knowledge.AiRenameAgentService.SuggestRequest(
            new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef(
                "101",c.generation(),0),true);
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->agent.suggest(1,new com.cloudchunk.core.knowledge.AiRenameAgentService.SuggestRequest(
                request.source(),false)));
        verifyNoInteractions(drive);
        var plan=agent.suggest(1,request);
        assertEquals("Alice.txt",plan.oldName());
        assertEquals("安全上传说明.txt",plan.newName());
        assertEquals("PENDING",plan.status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_rename_proposal",Integer.class));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
            agent.apply(2,plan.id(),
                new com.cloudchunk.core.knowledge.AiRenameAgentService.ApplyRequest(true)));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
            agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiRenameAgentService.ApplyRequest(false)));
        verifyNoInteractions(drive);
        assertEquals("APPLIED",agent.apply(1,plan.id(),
            new com.cloudchunk.core.knowledge.AiRenameAgentService.ApplyRequest(true)).status());
        verify(drive,times(1)).rename(101,1,"安全上传说明.txt");
        assertEquals("安全上传说明.txt",jdbc.queryForObject(
            "SELECT file_name FROM user_file WHERE id=101",String.class));
        assertEquals("APPLIED",jdbc.queryForObject(
            "SELECT status FROM ai_rename_proposal WHERE id=?",String.class,plan.id()));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
            agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiRenameAgentService.ApplyRequest(true)));
        verify(drive,times(1)).rename(101,1,"安全上传说明.txt");
    }
    @Test void aiRenameRefusesChangedNamesAndRecycledSources() {
        var c=claim();publish(c,"About reliable transfer");
        var gateway=mock(com.cloudchunk.core.knowledge.ChatGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new com.cloudchunk.core.knowledge.ChatGateway.Completion(
                "传输可靠性.txt [S1]",com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        var agent=new com.cloudchunk.core.knowledge.AiRenameAgentService(
            service,drive,gateway,mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            jdbc,new org.springframework.mock.env.MockEnvironment()
                .withProperty("cloudchunk.ai.agent.rename-enabled","true"));
        var request=new com.cloudchunk.core.knowledge.AiRenameAgentService.SuggestRequest(
            new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef(
                "101",c.generation(),0),true);
        var plan=agent.suggest(1,request);
        jdbc.update("UPDATE user_file SET file_name='manually-renamed.txt' WHERE id=101");
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
            agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiRenameAgentService.ApplyRequest(true)));
        verifyNoInteractions(drive);
        jdbc.update("UPDATE user_file SET file_name='Alice.txt',status=1 WHERE id=101");
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
            agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiRenameAgentService.ApplyRequest(true)));
        verifyNoInteractions(drive);
    }
    @Test void aiMoveRestrictsOwnerCandidatesRequiresTwoStepsAndAppliesOnce() {
        var c=claim();publish(c,"File content about reliable chunk upload");
        jdbc.update("""
            INSERT INTO user_file(id,user_id,parent_id,file_name,is_dir,status)
            VALUES (201,1,0,'工作资料',1,0),(202,1,0,'开发文档',1,0),
                   (203,2,0,'另一个用户的私有目录',1,0)
            """);
        var gateway=mock(com.cloudchunk.core.knowledge.ChatGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new com.cloudchunk.core.knowledge.ChatGateway.Completion(
                "2 [S1]",com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        doAnswer(inv->{
            jdbc.update("UPDATE user_file SET parent_id=? WHERE id=101 AND user_id=1",
                new Object[]{inv.getArgument(2)});
            return null;
        }).when(drive).move(eq(101L),eq(1L),eq(202L));
        var agent=new com.cloudchunk.core.knowledge.AiMoveAgentService(
            jdbc,service,drive,gateway,mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new org.springframework.mock.env.MockEnvironment()
                .withProperty("cloudchunk.ai.agent.move-enabled","true"));
        var ref=new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef(
            "101",c.generation(),0);
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->agent.suggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(ref,false)));
        verify(gateway,never()).completeWithUsage(anyString(),anyList());
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->agent.suggest(2,new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(ref,true)));
        var choices=agent.folderChoices(1,101);
        assertEquals(2,choices.folders().size(),"only user's own active folders can be offered");
        assertTrue(choices.folders().stream().noneMatch(f->f.id()==203L));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->agent.folderChoices(2,101));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->agent.suggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(
                ref,true,java.util.List.of(203L))));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->agent.suggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(
                ref,true,java.util.List.of(201L,201L))));
        var plan=agent.suggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(
            ref,true,java.util.List.of(201L,202L)));
        assertEquals(202,plan.destinationId());
        assertEquals("开发文档",plan.destinationName());
        assertEquals("PENDING",plan.status());
        assertFalse(agent.recent(2).stream().anyMatch(p->p.id().equals(plan.id())));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->tx.execute(txStatus->agent.apply(2,plan.id(),
                new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true))));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->tx.execute(txStatus->agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(false))));
        verifyNoInteractions(drive);
        var applied=tx.execute(txStatus->agent.apply(1,plan.id(),
            new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true)));
        assertNotNull(applied);
        assertEquals("APPLIED",applied.status());
        verify(drive,times(1)).move(101L,1L,202L);
        assertEquals(202L,jdbc.queryForObject(
            "SELECT parent_id FROM user_file WHERE id=101",Long.class));
        assertEquals("APPLIED",jdbc.queryForObject(
            "SELECT status FROM ai_move_proposal WHERE id=?",String.class,plan.id()));
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->tx.execute(txStatus->agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true))));
        verify(drive,times(1)).move(101L,1L,202L);
    }
    @Test void aiMoveInvalidatesWhenFileOrDestinationChangesOrIsRecycled() {
        var c=claim();publish(c,"Content about safe file storage");
        jdbc.update("""
            INSERT INTO user_file(id,user_id,parent_id,file_name,is_dir,status)
            VALUES (201,1,0,'安全归档',1,0),(202,2,0,'别人的文件夹',1,0)
            """);
        var gateway=mock(com.cloudchunk.core.knowledge.ChatGateway.class);
        when(gateway.configured()).thenReturn(true);
        when(gateway.completeWithUsage(anyString(),anyList())).thenReturn(
            new com.cloudchunk.core.knowledge.ChatGateway.Completion(
                "1 [S1]",com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        var agent=new com.cloudchunk.core.knowledge.AiMoveAgentService(
            jdbc,service,drive,gateway,mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new org.springframework.mock.env.MockEnvironment()
                .withProperty("cloudchunk.ai.agent.move-enabled","true"));
        var request=new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(
            new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef(
                "101",c.generation(),0),true);
        var plan=agent.suggest(1,request);
        jdbc.update("UPDATE user_file SET file_name='用户更改名称.txt' WHERE id=101");
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->tx.execute(txStatus->agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true))));
        jdbc.update("UPDATE user_file SET file_name='Alice.txt' WHERE id=101");
        jdbc.update("UPDATE user_file SET file_name='已改名目录' WHERE id=201");
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->tx.execute(txStatus->agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true))));
        jdbc.update("UPDATE user_file SET status=1 WHERE id=201");
        assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->tx.execute(txStatus->agent.apply(1,plan.id(),
                new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true))));
        verifyNoInteractions(drive);
    }
    @Test void aiMoveRejectsSameNameFileAddedAfterSuggestion() {
        var c=claim();publish(c,"Plain text about upload integrity");
        jdbc.update("""
            INSERT INTO user_file(id,user_id,parent_id,file_name,is_dir,status)
            VALUES (201,1,0,'目标文件夹',1,0)
            """);
        var model=mock(com.cloudchunk.core.knowledge.ChatGateway.class);
        when(model.configured()).thenReturn(true);
        when(model.completeWithUsage(anyString(),anyList())).thenReturn(
            new com.cloudchunk.core.knowledge.ChatGateway.Completion(
                "1 [S1]",com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        var agent=new com.cloudchunk.core.knowledge.AiMoveAgentService(
            jdbc,service,drive,model,mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new org.springframework.mock.env.MockEnvironment()
                .withProperty("cloudchunk.ai.agent.move-enabled","true"));
        var source=new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("101",c.generation(),0);
        var request=new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(
            source,true,java.util.List.of(201L));
        var proposal=agent.suggest(1,request);
        // Another active sibling is created after user previews the proposed destination.
        jdbc.update("""
            INSERT INTO user_file(id,user_id,parent_id,file_id,file_name,is_dir,status)
            VALUES (204,1,201,?,'Alice.txt',0,0)
            """,FILE);
        var conflict=assertThrows(com.cloudchunk.common.exception.BizException.class,
            ()->tx.execute(status->agent.apply(1,proposal.id(),
                new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true))));
        assertEquals(com.cloudchunk.common.exception.ErrorCode.INVALID_PARAMETER,conflict.getErrorCode());
        verifyNoInteractions(drive);
        assertEquals("PENDING",jdbc.queryForObject(
            "SELECT status FROM ai_move_proposal WHERE id=?",String.class,proposal.id()));
        assertEquals(0L,jdbc.queryForObject("SELECT parent_id FROM user_file WHERE id=101",Long.class));
        // The model also cannot generate a fresh plan into an already conflicting folder.
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->agent.suggest(1,request));
        verifyNoInteractions(drive);
    }
    @Test void batchPreviewCreatesOnlyConsentedOwnerScopedSuggestions() {
        var c=claim();publish(c,"Organizing user files");
        jdbc.update("""
            INSERT INTO user_file(id,user_id,file_id,parent_id,file_name,is_dir,status)
            VALUES(103,1,?,0,'Second.txt',0,0)
            """,FILE);
        jdbc.update("""
            INSERT INTO user_file(id,user_id,parent_id,file_name,is_dir,status)
            VALUES(201,1,0,'目标目录',1,0)
            """);
        var model=mock(com.cloudchunk.core.knowledge.ChatGateway.class);
        when(model.configured()).thenReturn(true);
        when(model.completeWithUsage(anyString(),anyList())).thenReturn(
            new com.cloudchunk.core.knowledge.ChatGateway.Completion(
                "1 [S1]",com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        var agent=new com.cloudchunk.core.knowledge.AiMoveAgentService(
            jdbc,service,drive,model,mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new org.springframework.mock.env.MockEnvironment().withProperty("cloudchunk.ai.agent.move-enabled","true"));
        var ref1=new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("101",c.generation(),0);
        var ref2=new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("103",c.generation(),0);
        var other=new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("102",c.generation(),0);
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
            agent.batchSuggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.BatchSuggestRequest(
                java.util.List.of(ref1),java.util.List.of(201L),false)));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->
            agent.batchSuggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.BatchSuggestRequest(
                java.util.List.of(ref1,ref1),java.util.List.of(201L),true)));
        var result=agent.batchSuggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.BatchSuggestRequest(
            java.util.List.of(ref1,ref2,other),java.util.List.of(201L),true));
        assertEquals(2,result.proposed());
        assertEquals(1,result.skipped());
        assertEquals("SKIPPED",result.items().get(2).status());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ai_move_proposal",Integer.class));
        assertEquals(0L,jdbc.queryForObject("SELECT parent_id FROM user_file WHERE id=101",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT parent_id FROM user_file WHERE id=103",Long.class));
        verifyNoInteractions(drive);
    }

    @Test void undoChecksOwnerExpiryFileVersionAndRevertsOnlyOnce() {
        var c=claim();publish(c,"Approval and safe move");
        jdbc.update("""
            INSERT INTO user_file(id,user_id,parent_id,file_name,is_dir,status)
            VALUES (201,1,0,'目标目录',1,0),(202,1,0,'原目录',1,0)
            """);
        jdbc.update("UPDATE user_file SET parent_id=202 WHERE id=101");
        var model=mock(com.cloudchunk.core.knowledge.ChatGateway.class);
        when(model.configured()).thenReturn(true);
        when(model.completeWithUsage(anyString(),anyList())).thenReturn(
            new com.cloudchunk.core.knowledge.ChatGateway.Completion(
                "1 [S1]",com.cloudchunk.core.knowledge.ModelTokenUsage.unknown()));
        var drive=mock(com.cloudchunk.core.drive.service.UserFileService.class);
        doAnswer(inv->{
            jdbc.update("""
                UPDATE user_file SET parent_id=?,updated_at=DATE_ADD(updated_at,INTERVAL 1 SECOND)
                WHERE id=101 AND user_id=1
                """,new Object[]{inv.getArgument(2)});
            return null;
        }).when(drive).move(eq(101L),eq(1L),anyLong());
        var agent=new com.cloudchunk.core.knowledge.AiMoveAgentService(
            jdbc,service,drive,model,mock(com.cloudchunk.core.knowledge.AiDailyCallBudget.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new org.springframework.mock.env.MockEnvironment().withProperty("cloudchunk.ai.agent.move-enabled","true"));
        var ref=new com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef("101",c.generation(),0);
        var plan=agent.suggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(
            ref,true,java.util.List.of(201L)));
        tx.executeWithoutResult(t->agent.apply(1,plan.id(),
            new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true)));
        assertEquals("APPLIED",jdbc.queryForObject(
            "SELECT status FROM ai_move_proposal WHERE id=?",String.class,plan.id()));
        assertNotNull(jdbc.queryForObject(
            "SELECT applied_file_updated_at FROM ai_move_proposal WHERE id=?",java.sql.Timestamp.class,plan.id()));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->tx.execute(t->
            agent.undo(2,plan.id(),new com.cloudchunk.core.knowledge.AiMoveAgentService.UndoRequest(true))));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->tx.execute(t->
            agent.undo(1,plan.id(),new com.cloudchunk.core.knowledge.AiMoveAgentService.UndoRequest(false))));
        var restored=tx.execute(t->agent.undo(1,plan.id(),
            new com.cloudchunk.core.knowledge.AiMoveAgentService.UndoRequest(true)));
        assertNotNull(restored);
        assertEquals("REVERTED",restored.status());
        assertEquals(202L,jdbc.queryForObject("SELECT parent_id FROM user_file WHERE id=101",Long.class));
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->tx.execute(t->
            agent.undo(1,plan.id(),new com.cloudchunk.core.knowledge.AiMoveAgentService.UndoRequest(true))));
        verify(drive,times(2)).move(eq(101L),eq(1L),anyLong());
        // A later approved move is not undoable after another human modifies the name.
        var next=agent.suggest(1,new com.cloudchunk.core.knowledge.AiMoveAgentService.SuggestRequest(
            ref,true,java.util.List.of(201L)));
        tx.executeWithoutResult(t->agent.apply(1,next.id(),
            new com.cloudchunk.core.knowledge.AiMoveAgentService.ApplyRequest(true)));
        jdbc.update("UPDATE user_file SET file_name='人工改过.txt' WHERE id=101");
        assertThrows(com.cloudchunk.common.exception.BizException.class,()->tx.execute(t->
            agent.undo(1,next.id(),new com.cloudchunk.core.knowledge.AiMoveAgentService.UndoRequest(true))));
        assertEquals("APPLIED",jdbc.queryForObject(
            "SELECT status FROM ai_move_proposal WHERE id=?",String.class,next.id()));
        verify(drive,times(3)).move(eq(101L),eq(1L),anyLong());
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
