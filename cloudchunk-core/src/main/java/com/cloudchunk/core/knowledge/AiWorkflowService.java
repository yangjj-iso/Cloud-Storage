package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Owner-scoped, durable, sequential execution plans for EXISTING reviewed
 * AI move proposals. No batch execution, no model invocation, no bypass of
 * AiMoveAgentService's current ACL/version/conflict/undo validation.
 */
@Service
public class AiWorkflowService {
    private static final Pattern UUID_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public record CreateRequest(List<String> moveProposalIds, boolean confirmed) {}
    public record ConfirmRequest(boolean confirmed) {}
    public record Summary(String id, String status, int total, int applied, int skipped,
                          int pending, String createdAt) {}
    public record Step(int stepNo, String moveProposalId, String userFileId,
                       String fileName, String destinationName, String expiresAt,
                       String state) {}
    public record Event(int stepNo, String action, String recordedAt) {}
    public record Workflow(Summary summary, List<Step> steps, List<Event> events) {}

    private record Plan(String status, LocalDateTime createdAt) {}
    private record Candidate(long userFileId, String status, LocalDateTime expiresAt) {}

    private final JdbcTemplate jdbc;
    private final AiMoveAgentService moves;

    public AiWorkflowService(JdbcTemplate jdbc, AiMoveAgentService moves) {
        this.jdbc = jdbc;
        this.moves = moves;
    }

    static void validateId(String value) {
        if (value == null || !UUID_ID.matcher(value).matches())
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid workflow reference");
    }

    static void validateCreate(CreateRequest request) {
        if (request == null || !request.confirmed() || request.moveProposalIds() == null
                || request.moveProposalIds().isEmpty() || request.moveProposalIds().size() > 4)
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "confirm 1-4 move proposals");
        Set<String> unique = new HashSet<>();
        for (String id : request.moveProposalIds()) {
            validateId(id);
            if (!unique.add(id))
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "duplicate move proposal");
        }
    }

    /** A plan contains already generated, user-approved previews; no model calls. */
    @Transactional(rollbackFor = Exception.class)
    public Workflow create(long userId, CreateRequest request) {
        validateCreate(request);
        Set<Long> logicalFileIds = new HashSet<>();
        // Lock/check the WHOLE set before writing a plan.
        for (String proposalId : request.moveProposalIds()) {
            List<Candidate> rows = jdbc.query("""
                SELECT user_file_id,status,expires_at FROM ai_move_proposal
                WHERE id=? AND user_id=? FOR UPDATE
                """, (rs,n) -> new Candidate(rs.getLong(1),rs.getString(2),
                    rs.getTimestamp(3).toLocalDateTime()),proposalId,userId);
            if (rows.size() != 1 || !"PENDING".equals(rows.getFirst().status())
                    || !rows.getFirst().expiresAt().isAfter(LocalDateTime.now()))
                throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
            if (!logicalFileIds.add(rows.getFirst().userFileId()))
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "same file selected twice");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_agent_workflow(id,user_id,status) VALUES (?,?,'ACTIVE')",id,userId);
        int index = 1;
        for (String proposalId : request.moveProposalIds()) {
            jdbc.update("""
                INSERT INTO ai_agent_workflow_step(workflow_id,step_no,move_proposal_id,status)
                VALUES (?,?,?,'PENDING')
                """,id,index++,proposalId);
        }
        audit(userId,id,null,"CREATED");
        return detail(userId,id);
    }

    /** The owner may see only up to the newest 20 plans and their coarse state. */
    @Transactional(readOnly = true)
    public List<Summary> recent(long userId) {
        List<String> ids = jdbc.query("""
            SELECT id FROM ai_agent_workflow WHERE user_id=?
            ORDER BY created_at DESC,id DESC LIMIT 20
            """,(rs,n) -> rs.getString(1),userId);
        return ids.stream().map(id -> detail(userId,id).summary()).toList();
    }

    @Transactional(readOnly = true)
    public Workflow detail(long userId,String id) {
        validateId(id);
        Plan plan = findPlan(userId,id,false);
        List<Step> steps = steps(id);
        List<Event> events = jdbc.query("""
            SELECT COALESCE(step_no,0),action,created_at
            FROM ai_agent_workflow_event WHERE workflow_id=? AND user_id=?
            ORDER BY id DESC LIMIT 40
            """,(rs,n) -> new Event(rs.getInt(1),rs.getString(2),
                rs.getTimestamp(3).toLocalDateTime().toString()),id,userId);
        int applied=0,skipped=0,pending=0;
        for (Step s : steps) {
            if ("APPLIED".equals(s.state())) applied++;
            else if ("SKIPPED".equals(s.state())) skipped++;
            else if ("REVERTED".equals(s.state())) skipped++;
            else pending++;
        }
        String state = "CANCELLED".equals(plan.status()) ? "CANCELLED" :
            pending == 0 ? "COMPLETED" : "ACTIVE";
        return new Workflow(new Summary(id,state,steps.size(),applied,skipped,pending,
                plan.createdAt().toString()),List.copyOf(steps),List.copyOf(events));
    }

    @Transactional(rollbackFor = Exception.class)
    public Workflow approve(long userId,String id,int number,ConfirmRequest request) {
        requireConfirm(request);
        transition(userId,id,number,"PENDING","APPROVED",true);
        return detail(userId,id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Workflow skip(long userId,String id,int number,ConfirmRequest request) {
        requireConfirm(request);
        Plan plan=findPlan(userId,id,true);
        ensureActive(plan);
        List<Step> rows=steps(id);
        Step step=eligible(rows,number);
        if (!"PENDING".equals(step.state()) && !"APPROVED".equals(step.state()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        update(id,number,step.state(),"SKIPPED");
        audit(userId,id,number,"SKIPPED");
        return detail(userId,id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Workflow execute(long userId,String id,int number,ConfirmRequest request) {
        requireConfirm(request);
        Plan plan=findPlan(userId,id,true);
        ensureActive(plan);
        Step step=eligible(steps(id),number);
        if (!"APPROVED".equals(step.state()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        // The existing move service locks & rechecks ACL, generation, destination,
        // collision, expiration and the one-shot proposal status.
        moves.apply(userId,step.moveProposalId(),new AiMoveAgentService.ApplyRequest(true));
        update(id,number,"APPROVED","APPLIED");
        audit(userId,id,number,"APPLIED");
        return detail(userId,id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Workflow undo(long userId,String id,int number,ConfirmRequest request) {
        requireConfirm(request);
        findPlan(userId,id,true);  // cancelled plans may still undo a past move
        List<Step> rows=steps(id);
        if (number<1 || number>rows.size() || !"APPLIED".equals(rows.get(number-1).state()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        // Exactly the previous short-window, no-newer-mutation, owner-scoped undo.
        moves.undo(userId,rows.get(number-1).moveProposalId(),
                new AiMoveAgentService.UndoRequest(true));
        update(id,number,"APPLIED","REVERTED");
        audit(userId,id,number,"REVERTED");
        return detail(userId,id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Workflow cancel(long userId,String id,ConfirmRequest request) {
        requireConfirm(request);
        Plan plan=findPlan(userId,id,true);
        ensureActive(plan);
        if (jdbc.update("""
            UPDATE ai_agent_workflow SET status='CANCELLED'
            WHERE id=? AND user_id=? AND status='ACTIVE'
            """,id,userId)!=1) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        audit(userId,id,null,"CANCELLED");
        // Does NOT undo previously applied moves, nor delete proposals or files.
        return detail(userId,id);
    }

    private void transition(long userId,String id,int number,String from,String to,boolean active) {
        Plan plan=findPlan(userId,id,true);
        if (active) ensureActive(plan);
        Step step=eligible(steps(id),number);
        if (!from.equals(step.state())) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        update(id,number,from,to);
        audit(userId,id,number,to);
    }

    /** Only the next pending/approved step can be executed, approved or skipped. */
    static Step eligible(List<Step> steps,int number) {
        if (number < 1 || number > steps.size())
            throw BizException.of(ErrorCode.INVALID_PARAMETER);
        for (int i=0;i<number-1;i++) {
            String state=steps.get(i).state();
            if (!"APPLIED".equals(state) && !"REVERTED".equals(state) && !"SKIPPED".equals(state))
                throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        }
        return steps.get(number-1);
    }

    private static void ensureActive(Plan plan) {
        if (!"ACTIVE".equals(plan.status())) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
    }

    private static void requireConfirm(ConfirmRequest request) {
        if (request==null || !request.confirmed()) throw BizException.of(ErrorCode.INVALID_PARAMETER);
    }

    private Plan findPlan(long userId,String id,boolean lock) {
        validateId(id);
        List<Plan> rows=jdbc.query("""
            SELECT status,created_at FROM ai_agent_workflow WHERE id=? AND user_id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->new Plan(
                rs.getString(1),rs.getTimestamp(2).toLocalDateTime()),id,userId);
        if (rows.size()!=1) throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return rows.getFirst();
    }

    private List<Step> steps(String id) {
        return jdbc.query("""
            SELECT s.step_no,s.move_proposal_id,p.user_file_id,p.original_name,
                   p.destination_name,p.expires_at,s.status
            FROM ai_agent_workflow_step s JOIN ai_move_proposal p ON p.id=s.move_proposal_id
            WHERE s.workflow_id=? ORDER BY s.step_no
            """,(rs,n)->new Step(rs.getInt(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getTimestamp(6).toLocalDateTime().toString(),
                rs.getString(7)),id);
    }

    private void update(String id,int number,String from,String to) {
        if (jdbc.update("""
            UPDATE ai_agent_workflow_step SET status=?
            WHERE workflow_id=? AND step_no=? AND status=?
            """,to,id,number,from)!=1) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
    }

    private void audit(long userId,String id,Integer number,String action) {
        jdbc.update("""
            INSERT INTO ai_agent_workflow_event(workflow_id,user_id,step_no,action)
            VALUES (?,?,?,?)
            """,id,userId,number,action);
    }
}
