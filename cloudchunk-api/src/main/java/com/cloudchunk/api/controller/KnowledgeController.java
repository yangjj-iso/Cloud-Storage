package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import com.cloudchunk.core.knowledge.KnowledgeService;
import com.cloudchunk.core.knowledge.GroundedAnswerService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** No ownership, storage location or raw parser exception is accepted from the client or returned. */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeService service;
    private final GroundedAnswerService assistant;
    public KnowledgeController(KnowledgeService service, GroundedAnswerService assistant) {
        this.service = service;
        this.assistant = assistant;
    }
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
    @PostMapping("/assistant/answer")
    public ResponseEntity<R<AnswerResponse>> answer(@RequestBody AnswerRequest request) {
        return noStore(assistant.answer(UserContext.requireUserId(), request));
    }
    private static <T> ResponseEntity<R<T>> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(data));
    }
}
