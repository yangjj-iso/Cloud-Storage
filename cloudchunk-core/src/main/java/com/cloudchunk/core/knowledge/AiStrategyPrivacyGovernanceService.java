package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Metadata-only consent receipts and manual, bounded, opt-in retention. */
@Service
public class AiStrategyPrivacyGovernanceService {
    private static final Pattern TICKET=Pattern.compile("[A-Z0-9][A-Z0-9_-]{7,63}");
    public record ConsentReceipt(String event,int candidateCount,String recordedAt) {}
    public record RetentionPreview(long expiredRecommendations,long expiredConsentEvents,
                                   int maxRowsPerTable,boolean applyEnabled) {}
    public record CleanupRequest(boolean confirmed,String changeTicket) {}
    public record CleanupReceipt(String receiptId,String changeTicket,
                                 int recommendationsPurged,int consentEventsPurged) {}
    private final JdbcTemplate jdbc;
    private final boolean applyEnabled;

    public AiStrategyPrivacyGovernanceService(JdbcTemplate jdbc,
             @Value("${cloudchunk.ai.strategy-retention.apply-enabled:false}") boolean applyEnabled) {
        this.jdbc=jdbc;this.applyEnabled=applyEnabled;
    }
    @Transactional(readOnly=true)
    public List<ConsentReceipt> history(long userId) {
        return jdbc.query("""
            SELECT event_code,candidate_count,created_at FROM ai_strategy_consent_audit
            WHERE user_id=? ORDER BY id DESC LIMIT 30
            """,(rs,n)->new ConsentReceipt(rs.getString(1),rs.getInt(2),
                rs.getTimestamp(3).toLocalDateTime().toString()),userId);
    }
    /** Never record a source pointer, model response, endpoint or raw error. */
    public void record(long userId,String event,int count) {
        if(userId<=0 || count<1 || count>8 || !("CONSENT_GRANTED".equals(event) ||
                "RECOMMENDED".equals(event) || "REQUEST_FAILED".equals(event)))
            throw BizException.of(ErrorCode.INVALID_PARAMETER);
        jdbc.update("""
            INSERT INTO ai_strategy_consent_audit(user_id,event_code,candidate_count)
            VALUES (?,?,?)
            """,userId,event,count);
    }
    @Transactional(readOnly=true)
    public RetentionPreview preview() {
        Long rec=jdbc.queryForObject("""
            SELECT COUNT(*) FROM ai_strategy_recommendation
            WHERE created_at < NOW(3)-INTERVAL 90 DAY
            """,Long.class);
        Long audit=jdbc.queryForObject("""
            SELECT COUNT(*) FROM ai_strategy_consent_audit
            WHERE created_at < NOW(3)-INTERVAL 180 DAY
            """,Long.class);
        if(rec==null || audit==null)throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        return new RetentionPreview(rec,audit,100,applyEnabled);
    }

    /**
     * No scheduler. ADMIN role, explicit feature flag and change-ticket required.
     * Only purge old derived recommendation links and consent receipts, never
     * actual files, move proposals, workflow state or budget history.
     */
    @Transactional(rollbackFor=Exception.class)
    public CleanupReceipt purge(long operatorUserId,CleanupRequest req) {
        if(!applyEnabled || operatorUserId<=0 || req==null || !req.confirmed() ||
                req.changeTicket()==null || !TICKET.matcher(req.changeTicket()).matches())
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"retention approval required");
        int rec=jdbc.update("""
            DELETE FROM ai_strategy_recommendation
            WHERE created_at < NOW(3)-INTERVAL 90 DAY
            ORDER BY created_at,id LIMIT 100
            """);
        int audit=jdbc.update("""
            DELETE FROM ai_strategy_consent_audit
            WHERE created_at < NOW(3)-INTERVAL 180 DAY
            ORDER BY created_at,id LIMIT 100
            """);
        if(rec<0 || rec>100 || audit<0 || audit>100)
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO ai_strategy_retention_run
              (id,operator_user_id,change_ticket,expired_recommendations,expired_consent_events)
            VALUES (?,?,?,?,?)
            """,id,operatorUserId,req.changeTicket(),rec,audit);
        return new CleanupReceipt(id,req.changeTicket(),rec,audit);
    }
}
