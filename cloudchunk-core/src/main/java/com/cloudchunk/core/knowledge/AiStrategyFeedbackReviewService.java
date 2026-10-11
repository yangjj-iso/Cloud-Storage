package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Two-person review of categorised user feedback only. Does not surface file
 * names, source pointers, raw model output, external account data, or change
 * the original immutable feedback. No AI calls or file operations.
 */
@Service
public class AiStrategyFeedbackReviewService {
    private static final Set<String> OUTCOMES = Set.of(
            "CONFIRMED", "FOLLOWUP_REQUIRED", "INSUFFICIENT_EVIDENCE");
    public record Confirm(boolean confirmed) {}
    public record Review(String id, String state, String feedbackReason,
                         String createdAt, String reviewCode, String reviewedAt) {}

    private final JdbcTemplate jdbc;
    public AiStrategyFeedbackReviewService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(rollbackFor=Exception.class)
    public Review request(long userId,String recommendationId,Confirm request) {
        AiWorkflowService.validateId(recommendationId);
        if(request==null || !request.confirmed())
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"explicit request required");
        // The requester must still own an active file reference at the original
        // knowledge-index generation; old V16 rows with NULL generation are inert.
        List<String> feedback = jdbc.query("""
            SELECT r.feedback_code FROM ai_strategy_recommendation r
            JOIN user_file u ON u.id=r.user_file_id AND u.user_id=r.user_id
                AND u.status=0 AND u.is_dir=0
            JOIN file_meta f ON f.file_id=u.file_id AND f.status=2
            JOIN knowledge_document k ON k.file_id=u.file_id
                AND k.generation=r.source_generation AND k.status IN ('READY','PARTIAL')
            WHERE r.id=? AND r.user_id=? AND r.status IN ('ACCEPTED','REJECTED')
                AND r.created_at >= NOW(3)-INTERVAL 30 DAY
            FOR UPDATE
            """,(rs,n)->rs.getString(1),recommendationId,userId);
        if(feedback.size()!=1) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO ai_strategy_feedback_review(id,recommendation_id,user_id)
            VALUES (?,?,?)
            """,id,recommendationId,userId);
        return own(userId,id);
    }

    @Transactional(readOnly=true)
    public List<Review> mine(long userId) {
        return jdbc.query("""
            SELECT v.id,v.status,r.feedback_code,v.created_at,v.review_code,v.reviewed_at
            FROM ai_strategy_feedback_review v
            JOIN ai_strategy_recommendation r ON r.id=v.recommendation_id AND r.user_id=v.user_id
            WHERE v.user_id=? ORDER BY v.created_at DESC,v.id DESC LIMIT 20
            """,(rs,n)->map(rs),userId);
    }

    /** Admin queue exposes fixed codes and timestamps only, no source/content. */
    @Transactional(readOnly=true)
    public List<Review> pending() {
        return jdbc.query("""
            SELECT v.id,v.status,r.feedback_code,v.created_at,v.review_code,v.reviewed_at
            FROM ai_strategy_feedback_review v
            JOIN ai_strategy_recommendation r ON r.id=v.recommendation_id
            WHERE v.status='REQUESTED'
            ORDER BY v.created_at,v.id LIMIT 20
            """,(rs,n)->map(rs));
    }

    @Transactional(rollbackFor=Exception.class)
    public Review resolve(long reviewerUserId,String caseId,String outcome,Confirm request) {
        AiWorkflowService.validateId(caseId);
        if(reviewerUserId<=0 || request==null || !request.confirmed()
                || !OUTCOMES.contains(outcome))
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"review approval required");
        int changed=jdbc.update("""
            UPDATE ai_strategy_feedback_review SET status='REVIEWED',review_code=?,
                reviewer_id=?,reviewed_at=NOW(3)
            WHERE id=? AND status='REQUESTED' AND user_id<>?
            """,outcome,reviewerUserId,caseId,reviewerUserId);
        if(changed!=1)throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        return adminOne(caseId);
    }

    private Review own(long userId,String caseId) {
        List<Review> rows=jdbc.query("""
            SELECT v.id,v.status,r.feedback_code,v.created_at,v.review_code,v.reviewed_at
            FROM ai_strategy_feedback_review v
            JOIN ai_strategy_recommendation r ON r.id=v.recommendation_id
            WHERE v.id=? AND v.user_id=? AND r.user_id=?
            """,(rs,n)->map(rs),caseId,userId,userId);
        if(rows.size()!=1)throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return rows.getFirst();
    }

    private Review adminOne(String caseId) {
        List<Review> rows=jdbc.query("""
            SELECT v.id,v.status,r.feedback_code,v.created_at,v.review_code,v.reviewed_at
            FROM ai_strategy_feedback_review v
            JOIN ai_strategy_recommendation r ON r.id=v.recommendation_id
            WHERE v.id=?
            """,(rs,n)->map(rs),caseId);
        if(rows.size()!=1)throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return rows.getFirst();
    }

    private static Review map(java.sql.ResultSet rs) throws java.sql.SQLException {
        var reviewed=rs.getTimestamp(6);
        return new Review(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getTimestamp(4).toLocalDateTime().toString(),rs.getString(5),
                reviewed==null?null:reviewed.toLocalDateTime().toString());
    }
}
