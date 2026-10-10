package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import com.cloudchunk.core.knowledge.KnowledgeService;
import com.cloudchunk.core.knowledge.GroundedAnswerService;
import com.cloudchunk.core.knowledge.SemanticIndexService;
import com.cloudchunk.core.knowledge.HybridSearchService;
import com.cloudchunk.core.knowledge.AutoAnswerService;
import com.cloudchunk.core.knowledge.AiDailyCallBudget;
import com.cloudchunk.core.knowledge.QdrantMirrorService;
import com.cloudchunk.core.knowledge.AiRenameAgentService;
import com.cloudchunk.api.monitoring.KnowledgeRetrievalMetrics;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** No ownership, storage location or raw parser exception is accepted from the client or returned. */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    private final GroundedAnswerService assistant;
    private final SemanticIndexService semantic;
    private final HybridSearchService hybrid;
    private final AutoAnswerService autoAnswer;
    private final AiDailyCallBudget budget;
    private final QdrantMirrorService mirror;
    private final AiRenameAgentService renameAgent;
    private final KnowledgeRetrievalMetrics retrievalMetrics;
    public KnowledgeController(KnowledgeService service, GroundedAnswerService assistant,
                               SemanticIndexService semantic, HybridSearchService hybrid,
                               AutoAnswerService autoAnswer, AiDailyCallBudget budget,
                               QdrantMirrorService mirror,KnowledgeRetrievalMetrics retrievalMetrics,
                               AiRenameAgentService renameAgent) {
        this.service = service;
        this.assistant = assistant;
        this.semantic = semantic;
        this.hybrid = hybrid;
        this.autoAnswer = autoAnswer;
        this.budget = budget;
        this.mirror = mirror;
        this.retrievalMetrics=retrievalMetrics;
        this.renameAgent=renameAgent;
    }
    public record SemanticSearchRequest(String query,boolean consent) {}
    public record SemanticIndexRequest(boolean consent) {}
    public record SearchRequest(String query) {}

    @GetMapping("/documents")
    public ResponseEntity<R<Library>> library(@RequestParam(defaultValue="1") int page) {
        return noStore(service.library(UserContext.requireUserId(), page));
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
        return noStore(autoAnswer.ask(UserContext.requireUserId(), request));
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
        return noStore(retrievalMetrics.measure("hybrid",
            ()->hybrid.search(userId,request.query(),request.consent())));
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
