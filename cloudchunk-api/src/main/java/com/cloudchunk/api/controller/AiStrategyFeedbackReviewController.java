package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.AiStrategyFeedbackReviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** User-submitted review requests, private and without file or prompt content. */
@RestController
@RequestMapping("/api/v1/knowledge/assistant/strategy")
public class AiStrategyFeedbackReviewController {
    private final AiStrategyFeedbackReviewService reviews;
    public AiStrategyFeedbackReviewController(AiStrategyFeedbackReviewService reviews) {
        this.reviews=reviews;
    }

    @PostMapping("/recommendations/{id}/review")
    public ResponseEntity<R<AiStrategyFeedbackReviewService.Review>> request(
            @PathVariable String id,@RequestBody AiStrategyFeedbackReviewService.Confirm req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(reviews.request(UserContext.requireUserId(),id,req)));
    }

    @GetMapping("/feedback-reviews")
    public ResponseEntity<R<List<AiStrategyFeedbackReviewService.Review>>> mine() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(reviews.mine(UserContext.requireUserId())));
    }
}
