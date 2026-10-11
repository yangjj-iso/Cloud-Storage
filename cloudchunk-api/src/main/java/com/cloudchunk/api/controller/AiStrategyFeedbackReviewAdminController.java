package com.cloudchunk.api.controller;

import com.cloudchunk.common.model.R;
import com.cloudchunk.common.trace.UserContext;
import com.cloudchunk.core.knowledge.AiStrategyFeedbackReviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Access restricted by existing /api/v1/admin/** ADMIN policy. */
@RestController
@RequestMapping("/api/v1/admin/strategy-feedback-reviews")
public class AiStrategyFeedbackReviewAdminController {
    private final AiStrategyFeedbackReviewService reviews;
    public AiStrategyFeedbackReviewAdminController(AiStrategyFeedbackReviewService reviews) {
        this.reviews=reviews;
    }

    @GetMapping
    public ResponseEntity<R<List<AiStrategyFeedbackReviewService.Review>>> pending() {
        UserContext.requireUserId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(reviews.pending()));
    }

    public record ResolveRequest(String reviewCode,boolean confirmed) {}

    @PostMapping("/{id}/resolve")
    public ResponseEntity<R<AiStrategyFeedbackReviewService.Review>> resolve(
            @PathVariable String id,@RequestBody ResolveRequest req) {
        var confirmation = req==null ? null : new AiStrategyFeedbackReviewService.Confirm(req.confirmed());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(reviews.resolve(UserContext.requireUserId(),id,
                        req==null ? null : req.reviewCode(),confirmation)));
    }
}
