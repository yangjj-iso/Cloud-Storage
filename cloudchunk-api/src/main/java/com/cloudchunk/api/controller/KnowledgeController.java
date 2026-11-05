package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import com.cloudchunk.core.knowledge.KnowledgeService;
import com.cloudchunk.core.knowledge.ResearchBriefService;
import com.cloudchunk.core.knowledge.GroundedAnswerService;
import com.cloudchunk.core.knowledge.SemanticIndexService;
import com.cloudchunk.core.knowledge.HybridSearchService;
import com.cloudchunk.core.knowledge.AutoAnswerService;
import com.cloudchunk.core.knowledge.AiDailyCallBudget;
import com.cloudchunk.core.knowledge.QdrantMirrorService;
import com.cloudchunk.core.knowledge.AiRenameAgentService;
import com.cloudchunk.core.knowledge.AiMoveAgentService;
import com.cloudchunk.core.knowledge.AiWorkflowService;
import com.cloudchunk.api.monitoring.KnowledgeRetrievalMetrics;
import com.cloudchunk.api.monitoring.BusinessSliMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** No ownership, storage location or raw parser exception is accepted from the client or returned. */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    @Autowired private ResearchBriefService researchBrief;
    private final GroundedAnswerService assistant;
    private final SemanticIndexService semantic;
    private final HybridSearchService hybrid;
    private final AutoAnswerService autoAnswer;
    private final AiDailyCallBudget budget;
    private final QdrantMirrorService mirror;
    private final AiRenameAgentService renameAgent;
    private final AiMoveAgentService moveAgent;
    @org.springframework.beans.factory.annotation.Autowired private AiWorkflowService workflows;
    private final KnowledgeRetrievalMetrics retrievalMetrics;
    @Autowired(required = false) private BusinessSliMetrics businessSli;
    public KnowledgeController(KnowledgeService service, GroundedAnswerService assistant,
                               SemanticIndexService semantic, HybridSearchService hybrid,
                               AutoAnswerService autoAnswer, AiDailyCallBudget budget,
                               QdrantMirrorService mirror,KnowledgeRetrievalMetrics retrievalMetrics,
                               AiRenameAgentService renameAgent,AiMoveAgentService moveAgent) {
        this.service = service;
        this.assistant = assistant;
        this.semantic = semantic;
        this.hybrid = hybrid;
        this.autoAnswer = autoAnswer;
        this.budget = budget;
        this.mirror = mirror;
        this.retrievalMetrics=retrievalMetrics;
        this.renameAgent=renameAgent;
        this.moveAgent=moveAgent;
    }
    public record SemanticSearchRequest(String query,boolean consent) {}
    public record SemanticIndexRequest(boolean consent) {}
    public record SearchRequest(String query) {}
    public record BatchIndexRequest(java.util.List<String> userFileIds) {}

    @GetMapping("/documents")
    public ResponseEntity<R<Library>> library(@RequestParam(defaultValue="1") int page) {
        return noStore(service.library(UserContext.requireUserId(), page));
    }
    public record ResearchBriefRequest(java.util.List<SourceRef> sources) {}

    @PostMapping("/research/brief")
    public ResponseEntity<R<ResearchBriefService.Brief>> researchBrief(@RequestBody ResearchBriefRequest request) {
        if (request == null) throw com.cloudchunk.common.exception.BizException.of(
                com.cloudchunk.common.exception.ErrorCode.INVALID_PARAMETER);
        return noStore(researchBrief.prepare(UserContext.requireUserId(), request.sources()));
    }

    @PostMapping("/documents/index/batch")
    public ResponseEntity<R<BatchIndexResult>> indexBatch(@RequestBody BatchIndexRequest request) {
        if (request == null) throw com.cloudchunk.common.exception.BizException.of(
                com.cloudchunk.common.exception.ErrorCode.INVALID_PARAMETER);
        return noStore(service.requestIndexBatch(UserContext.requireUserId(), request.userFileIds()));
    }

    @PostMapping("/search")
    public ResponseEntity<R<SearchResult>> search(@RequestBody SearchRequest request) {
        long userId=UserContext.requireUserId();
        return noStore(retrievalMetrics.measure("keyword",()->service.search(userId,request.query())));
    }
    @GetMapping("/documents/{id}/blocks")
    public ResponseEntity<R<BlockPage>> blocks(@PathVariable long id, @RequestParam long generation,
                                              @RequestParam(defaultValue="1") int page) {
        return noStore(service.blocks(UserContext.requireUserId(), id, generation, page));
    }
    @PostMapping("/documents/{id}/index")
    public ResponseEntity<R<Void>> index(@PathVariable long id) {
        service.requestIndex(UserContext.requireUserId(), id);
        return noStore(null);
    }
    @GetMapping("/assistant/capabilities")
    public ResponseEntity<R<AssistantCapabilities>> capabilities() {
        UserContext.requireUserId();
        return noStore(assistant.capabilities());
    }
    @GetMapping("/assistant/usage")
    public ResponseEntity<R<AiDailyCallBudget.Snapshot>> usage() {
        return noStore(budget.snapshot(UserContext.requireUserId()));
    }
    @PostMapping("/assistant/answer")
    public ResponseEntity<R<AnswerResponse>> answer(@RequestBody AnswerRequest request) {
        return noStore(assistant.answer(UserContext.requireUserId(), request));
    }
    @PostMapping("/assistant/ask")
    public ResponseEntity<R<AutoAskResponse>> autoAsk(@RequestBody AutoAskRequest request) {
        AutoAskResponse response = autoAnswer.ask(UserContext.requireUserId(), request);
        if (businessSli != null) businessSli.aiRetrieval(response.retrievalMode());
        return noStore(response);
    }
    @GetMapping("/assistant/rename/capabilities")
    public ResponseEntity<R<AiRenameAgentService.Capabilities>> renameCapabilities() {
        UserContext.requireUserId();
        return noStore(renameAgent.capabilities());
    }
    @PostMapping("/assistant/rename/suggest")
    public ResponseEntity<R<AiRenameAgentService.Proposal>> suggestRename(
            @RequestBody AiRenameAgentService.SuggestRequest request) {
        return noStore(renameAgent.suggest(UserContext.requireUserId(),request));
    }
    @PostMapping("/assistant/rename/{id}/apply")
    public ResponseEntity<R<AiRenameAgentService.ApplyResult>> applyRename(
            @PathVariable String id,@RequestBody AiRenameAgentService.ApplyRequest request) {
        return noStore(renameAgent.apply(UserContext.requireUserId(),id,request));
    }
    @GetMapping("/assistant/rename/recent")
    public ResponseEntity<R<java.util.List<AiRenameAgentService.Proposal>>> recentRenames() {
        return noStore(renameAgent.recent(UserContext.requireUserId()));
    }
    @GetMapping("/assistant/move/capabilities")
    public ResponseEntity<R<AiMoveAgentService.Capabilities>> moveCapabilities() {
        UserContext.requireUserId();
        return noStore(moveAgent.capabilities());
    }
    @GetMapping("/assistant/move/folders")
    public ResponseEntity<R<AiMoveAgentService.FolderChoices>> folderChoices(
            @RequestParam long userFileId) {
        return noStore(moveAgent.folderChoices(UserContext.requireUserId(),userFileId));
    }
    @PostMapping("/assistant/workflows")
    public ResponseEntity<R<AiWorkflowService.Workflow>> createWorkflow(
            @RequestBody AiWorkflowService.CreateRequest request) {
        return noStore(workflows.create(UserContext.requireUserId(),request));
    }
    @GetMapping("/assistant/workflows")
    public ResponseEntity<R<java.util.List<AiWorkflowService.Summary>>> recentWorkflows() {
        return noStore(workflows.recent(UserContext.requireUserId()));
    }
    @GetMapping("/assistant/workflows/{id}")
    public ResponseEntity<R<AiWorkflowService.Workflow>> workflow(@PathVariable String id) {
        return noStore(workflows.detail(UserContext.requireUserId(),id));
    }
    @PostMapping("/assistant/workflows/{id}/steps/{stepNo}/approve")
    public ResponseEntity<R<AiWorkflowService.Workflow>> approveWorkflowStep(
            @PathVariable String id,@PathVariable int stepNo,
            @RequestBody AiWorkflowService.ConfirmRequest request) {
        return noStore(workflows.approve(UserContext.requireUserId(),id,stepNo,request));
    }
    @PostMapping("/assistant/workflows/{id}/steps/{stepNo}/skip")
    public ResponseEntity<R<AiWorkflowService.Workflow>> skipWorkflowStep(
            @PathVariable String id,@PathVariable int stepNo,
            @RequestBody AiWorkflowService.ConfirmRequest request) {
        return noStore(workflows.skip(UserContext.requireUserId(),id,stepNo,request));
    }
    @PostMapping("/assistant/workflows/{id}/steps/{stepNo}/execute")
    public ResponseEntity<R<AiWorkflowService.Workflow>> executeWorkflowStep(
            @PathVariable String id,@PathVariable int stepNo,
            @RequestBody AiWorkflowService.ConfirmRequest request) {
        return noStore(workflows.execute(UserContext.requireUserId(),id,stepNo,request));
    }
    @PostMapping("/assistant/workflows/{id}/steps/{stepNo}/undo")
    public ResponseEntity<R<AiWorkflowService.Workflow>> undoWorkflowStep(
            @PathVariable String id,@PathVariable int stepNo,
            @RequestBody AiWorkflowService.ConfirmRequest request) {
        return noStore(workflows.undo(UserContext.requireUserId(),id,stepNo,request));
    }
    @PostMapping("/assistant/workflows/{id}/cancel")
    public ResponseEntity<R<AiWorkflowService.Workflow>> cancelWorkflow(
            @PathVariable String id,@RequestBody AiWorkflowService.ConfirmRequest request) {
        return noStore(workflows.cancel(UserContext.requireUserId(),id,request));
    }

    @PostMapping("/assistant/move/batch/suggest")
    public ResponseEntity<R<AiMoveAgentService.BatchPreview>> batchSuggestMoves(
            @RequestBody AiMoveAgentService.BatchSuggestRequest request) {
        return noStore(moveAgent.batchSuggest(UserContext.requireUserId(),request));
    }
    @PostMapping("/assistant/move/{id}/undo")
    public ResponseEntity<R<AiMoveAgentService.UndoResult>> undoMove(
            @PathVariable String id,@RequestBody AiMoveAgentService.UndoRequest request) {
        return noStore(moveAgent.undo(UserContext.requireUserId(),id,request));
    }
    @PostMapping("/assistant/move/suggest")
    public ResponseEntity<R<AiMoveAgentService.Proposal>> suggestMove(
            @RequestBody AiMoveAgentService.SuggestRequest request) {
        return noStore(moveAgent.suggest(UserContext.requireUserId(),request));
    }
    @PostMapping("/assistant/move/{id}/apply")
    public ResponseEntity<R<AiMoveAgentService.ApplyResult>> applyMove(
            @PathVariable String id,@RequestBody AiMoveAgentService.ApplyRequest request) {
        return noStore(moveAgent.apply(UserContext.requireUserId(),id,request));
    }
    @GetMapping("/assistant/move/recent")
    public ResponseEntity<R<java.util.List<AiMoveAgentService.Proposal>>> recentMoves() {
        return noStore(moveAgent.recent(UserContext.requireUserId()));
    }
    @GetMapping("/semantic/health")
    public ResponseEntity<R<QdrantMirrorService.MirrorHealth>> semanticHealth() {
        return noStore(mirror.healthForUser(UserContext.requireUserId()));
    }
    @GetMapping("/semantic/capabilities")
    public ResponseEntity<R<SemanticIndexService.Capabilities>> semanticCapabilities() {
        UserContext.requireUserId();
        return noStore(semantic.capabilities());
    }
    @PostMapping("/semantic/search")
    public ResponseEntity<R<SearchResult>> semanticSearch(@RequestBody SemanticSearchRequest request) {
        long userId=UserContext.requireUserId();
        SearchResult response = retrievalMetrics.measure("hybrid",
            ()->hybrid.search(userId,request.query(),request.consent()));
        if (businessSli != null) businessSli.aiRetrieval(response.mode());
        return noStore(response);
    }
    @PostMapping("/documents/{id}/semantic-index")
    public ResponseEntity<R<SemanticIndexService.Status>> semanticIndex(@PathVariable long id,
                                                                       @RequestBody SemanticIndexRequest request) {
        return noStore(semantic.request(UserContext.requireUserId(),id,request.consent()));
    }
    @GetMapping("/documents/{id}/semantic-index")
    public ResponseEntity<R<SemanticIndexService.Status>> semanticStatus(@PathVariable long id) {
        return noStore(semantic.status(UserContext.requireUserId(),id));
    }
    @DeleteMapping("/documents/{id}/semantic-index")
    public ResponseEntity<R<Void>> semanticRevoke(@PathVariable long id) {
        semantic.revoke(UserContext.requireUserId(),id);
        return noStore(null);
    }
    private static <T> ResponseEntity<R<T>> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(data));
    }
}
