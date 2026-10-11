package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.SourceRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * M4.3: recommend only from an explicitly owner-selected set of existing,
 * compatible organizing templates. Reuses the existing consented, budgeted,
 * strictly parsed and ACL-revalidated AiMoveAgentService suggestion.
 * Feedback is a preference record, never a move or a training job.
 */
@Service
public class AiStrategyRecommendationService {
    public record SuggestRequest(SourceRef source, List<String> templateIds, boolean consent) {}
    public record RateRequest(String decision, String reasonCode, boolean confirmed) {}
    public record Recommendation(String id, String templateId, String templateName,
                                 String destinationName, String userFileId, String fileName,
                                 String proposalId, String proposalExpiresAt, String state,
                                 String feedbackCode, String createdAt) {}
    public record Quality(long accepted, long rejected, long unrated, Double acceptanceRatio,
                          String window, String note) {}

    private record Template(String id,String name,String category,long folderId,
                            String folderName,long sourceParent) {}
    private record Source(String mime,long parentId) {}
    private record ProposalLink(String templateId,long userFileId) {}

    private static final Set<String> REASONS = Set.of(
            "WRONG_FOLDER","WRONG_CATEGORY","INSUFFICIENT_CONTEXT","OTHER");

    private final JdbcTemplate jdbc;
    private final KnowledgeService knowledge;
    private final AiMoveAgentService moves;

    public AiStrategyRecommendationService(JdbcTemplate jdbc,KnowledgeService knowledge,
                                           AiMoveAgentService moves) {
        this.jdbc=jdbc;this.knowledge=knowledge;this.moves=moves;
    }

    /** Fully bounded and format-checked before reading any personal evidence. */
    static void validate(SuggestRequest request) {
        if (request==null || !request.consent() || request.source()==null ||
                request.templateIds()==null || request.templateIds().isEmpty() ||
                request.templateIds().size()>8)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"select and consent to 1-8 templates");
        var source=request.source();
        OrganizationTemplateService.parseId(source.userFileId());
        if (source.generation()<1 || source.chunkIndex()<0 || source.chunkIndex()>=64)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"invalid source pointer");
        Set<String> seen=new HashSet<>();
        for(String id:request.templateIds()) {
            AiWorkflowService.validateId(id);
            if(!seen.add(id)) throw BizException.of(ErrorCode.INVALID_PARAMETER,"duplicate template");
        }
    }

    /**
     * A successful recommendation is merely an already-owned move proposal;
     * it is NOT approved and no file changes occur.
     */
    public Recommendation suggest(long userId,SuggestRequest request) {
        validate(request);
        if (!moves.capabilities().enabled()) throw BizException.of(ErrorCode.AI_DISABLED);
        long sourceId=OrganizationTemplateService.parseId(request.source().userFileId());
        var evidence=knowledge.source(userId,sourceId,
                request.source().generation(),request.source().chunkIndex());
        if(evidence==null) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        Source src=source(userId,sourceId,request.source().generation());
        if(src==null) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        List<Template> candidates=candidates(userId,request.templateIds());
        // Refuse the whole submission for any template of another owner or inactive folder.
        if(candidates.size()!=request.templateIds().size())
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        Set<Long> destinations=new HashSet<>();
        for(Template candidate:candidates) {
            if(!destinations.add(candidate.folderId()))
                throw BizException.of(ErrorCode.INVALID_PARAMETER,
                        "select only one template per destination");
            if(!OrganizationTemplateService.matches(candidate.category(),src.mime()) ||
                    candidate.folderId()==src.parentId())
                throw BizException.of(ErrorCode.INVALID_PARAMETER,
                        "template does not match this file");
        }

        // The existing agent performs strict model-response parsing, budget reservation,
        // a bounded prompt with real server evidence, and post-inference rechecks.
        List<Long> folderIds=candidates.stream().map(Template::folderId).toList();
        var proposal=moves.suggest(userId,new AiMoveAgentService.SuggestRequest(
                request.source(),true,folderIds));
        Template selected=candidates.stream().filter(t->
                Long.toString(t.folderId()).equals(Long.toString(proposal.destinationId())))
                .findFirst().orElseThrow(()->BizException.of(ErrorCode.AI_SOURCE_CHANGED));

        // Templates may have been archived / moved while the model was running.
        List<Template> after=candidates(userId,request.templateIds());
        if(after.size()!=candidates.size() || !after.equals(candidates) ||
                knowledge.source(userId,sourceId,request.source().generation(),
                        request.source().chunkIndex())==null)
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO ai_strategy_recommendation
             (id,user_id,template_id,move_proposal_id,user_file_id)
            VALUES (?,?,?,?,?)
            """,id,userId,selected.id(),proposal.id(),sourceId);
        return one(userId,id);
    }

    /** Feedback is owner-scoped, one-shot and categorized; no model requests. */
    @Transactional(rollbackFor=Exception.class)
    public Recommendation rate(long userId,String id,RateRequest request) {
        AiWorkflowService.validateId(id);
        if(request==null || !request.confirmed())
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"explicit feedback required");
        boolean accepted="ACCEPTED".equals(request.decision());
        boolean rejected="REJECTED".equals(request.decision());
        if(!accepted && !rejected)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"invalid feedback decision");
        if(accepted ? !"USEFUL".equals(request.reasonCode()) :
                !REASONS.contains(request.reasonCode()))
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"invalid reason category");
        int changed=jdbc.update("""
            UPDATE ai_strategy_recommendation SET status=?,feedback_code=?,feedback_at=NOW(3)
            WHERE id=? AND user_id=? AND status='PENDING'
              AND created_at >= NOW(3) - INTERVAL 24 HOUR
            """,request.decision(),request.reasonCode(),id,userId);
        if(changed!=1)throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        return one(userId,id);
    }

    @Transactional(readOnly=true)
    public List<Recommendation> recent(long userId) {
        List<String> ids=jdbc.query("""
            SELECT r.id FROM ai_strategy_recommendation r
            JOIN user_file u ON u.id=r.user_file_id AND u.user_id=r.user_id
                AND u.status=0 AND u.is_dir=0
            JOIN file_meta f ON f.file_id=u.file_id AND f.status=2
            WHERE r.user_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT 15
            """,(rs,n)->rs.getString(1),userId);
        return ids.stream().map(id->one(userId,id)).toList();
    }

    @Transactional(readOnly=true)
    public Quality quality(long userId) {
        long[] counts=jdbc.queryForObject("""
            SELECT COALESCE(SUM(status='ACCEPTED'),0),
                   COALESCE(SUM(status='REJECTED'),0),
                   COALESCE(SUM(status='PENDING'),0)
            FROM ai_strategy_recommendation
            WHERE user_id=? AND created_at >= NOW(3) - INTERVAL 30 DAY
            """,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3)},userId);
        if(counts==null)throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        long rated=counts[0]+counts[1];
        return new Quality(counts[0],counts[1],counts[2],
                rated>=5?(double)counts[0]/rated:null,"30d",
                "当前账号主动反馈的接受占比；样本少于5次不展示比例，不是模型准确率或自动训练");
    }

    private Recommendation one(long userId,String id) {
        AiWorkflowService.validateId(id);
        List<Recommendation> records=jdbc.query("""
            SELECT r.id,r.template_id,t.display_name,u.file_name,
                   COALESCE(folder.file_name,'目录已变更'),r.user_file_id,
                   r.move_proposal_id,p.expires_at,r.status,r.feedback_code,r.created_at
            FROM ai_strategy_recommendation r
            JOIN ai_organize_template t ON t.id=r.template_id AND t.user_id=r.user_id
            JOIN ai_move_proposal p ON p.id=r.move_proposal_id AND p.user_id=r.user_id
            JOIN user_file u ON u.id=r.user_file_id AND u.user_id=r.user_id
                AND u.status=0 AND u.is_dir=0
            JOIN file_meta f ON f.file_id=u.file_id AND f.status=2
            LEFT JOIN user_file folder ON folder.id=t.destination_id
                AND folder.user_id=r.user_id AND folder.status=0 AND folder.is_dir=1
            WHERE r.id=? AND r.user_id=?
            """,(rs,n)->new Recommendation(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(5),Long.toString(rs.getLong(6)),
                rs.getString(4),rs.getString(7),rs.getTimestamp(8).toLocalDateTime().toString(),
                rs.getString(9),rs.getString(10),
                rs.getTimestamp(11).toLocalDateTime().toString()),id,userId);
        if(records.size()!=1)throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return records.getFirst();
    }

    private Source source(long userId,long id,long generation) {
        List<Source> result=jdbc.query("""
            SELECT f.mime_type,u.parent_id FROM user_file u
            JOIN file_meta f ON f.file_id=u.file_id AND f.status=2
            JOIN knowledge_document k ON k.file_id=u.file_id
                AND k.generation=? AND k.status IN ('READY','PARTIAL')
            WHERE u.id=? AND u.user_id=? AND u.status=0 AND u.is_dir=0
            LIMIT 1
            """,(rs,n)->new Source(rs.getString(1),rs.getLong(2)),
                generation,id,userId);
        return result.isEmpty()?null:result.getFirst();
    }

    private List<Template> candidates(long userId,List<String> ids) {
        List<Template> result=new ArrayList<>();
        for(String id:ids) {
            List<Template> templates=jdbc.query("""
                SELECT t.id,t.display_name,t.mime_category,t.destination_id,
                       d.file_name,d.parent_id
                FROM ai_organize_template t
                JOIN user_file d ON d.id=t.destination_id AND d.user_id=t.user_id
                    AND d.status=0 AND d.is_dir=1
                WHERE t.id=? AND t.user_id=? AND t.status='ACTIVE'
                LIMIT 1
                """,(rs,n)->new Template(rs.getString(1),rs.getString(2),
                    rs.getString(3),rs.getLong(4),rs.getString(5),rs.getLong(6)),
                id,userId);
            if(templates.size()==1)result.add(templates.getFirst());
        }
        return List.copyOf(result);
    }
}
