package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.AiStrategyRecommendationService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Owner-only recommendations and feedback, no file mutation or model training action. */
@RestController
@RequestMapping("/api/v1/knowledge/assistant/strategy")
public class AiStrategyRecommendationController {
    private final AiStrategyRecommendationService service;

    public AiStrategyRecommendationController(AiStrategyRecommendationService service) {
        this.service = service;
    }

    private static <T> ResponseEntity<R<T>> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(data));
    }

    @PostMapping("/recommend")
    public ResponseEntity<R<AiStrategyRecommendationService.Recommendation>> suggest(
            @RequestBody AiStrategyRecommendationService.SuggestRequest request) {
        return noStore(service.suggest(UserContext.requireUserId(),request));
    }

    @PostMapping("/recommendations/{id}/feedback")
    public ResponseEntity<R<AiStrategyRecommendationService.Recommendation>> rate(
            @PathVariable String id,
            @RequestBody AiStrategyRecommendationService.RateRequest request) {
        return noStore(service.rate(UserContext.requireUserId(),id,request));
    }

    @GetMapping("/recommendations")
    public ResponseEntity<R<List<AiStrategyRecommendationService.Recommendation>>> recent() {
        return noStore(service.recent(UserContext.requireUserId()));
    }

    @GetMapping("/quality")
    public ResponseEntity<R<AiStrategyRecommendationService.Quality>> quality() {
        return noStore(service.quality(UserContext.requireUserId()));
    }
}
