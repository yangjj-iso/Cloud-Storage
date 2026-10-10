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
    public KnowledgeController(KnowledgeService service, GroundedAnswerService assistant,
                               SemanticIndexService semantic, HybridSearchService hybrid,
                               AutoAnswerService autoAnswer, AiDailyCallBudget budget) {
        this.service = service;
        this.assistant = assistant;
        this.semantic = semantic;
        this.hybrid = hybrid;
        this.autoAnswer = autoAnswer;
        this.budget = budget;
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
        return noStore(service.search(UserContext.requireUserId(), request.query()));
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
    @GetMapping("/semantic/capabilities")
    public ResponseEntity<R<SemanticIndexService.Capabilities>> semanticCapabilities() {
        UserContext.requireUserId();
        return noStore(semantic.capabilities());
    }
    @PostMapping("/semantic/search")
    public ResponseEntity<R<SearchResult>> semanticSearch(@RequestBody SemanticSearchRequest request) {
        return noStore(hybrid.search(UserContext.requireUserId(),request.query(),request.consent()));
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
