package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.drive.service.UserFileService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * M4.2 deterministic organizing templates. A preview stores an immutable short-lived
 * DB snapshot; each execution is explicitly confirmed, ACL-rechecked and delegated
 * to the existing business move service. No model requests or automatic batch actions.
 */
@Service
public class OrganizationTemplateService {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DECIMAL_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Set<String> TYPES = Set.of("ANY", "TEXT", "PDF", "IMAGE", "VIDEO");
    private static final Set<String> TERMINAL = Set.of("APPLIED", "SKIPPED");

    public record CreateTemplate(String name, String mimeCategory, String destinationId) {}
    public record Template(String id, String name, String mimeCategory, String destinationId,
                           String destinationName, String createdAt) {}
    public record SimulateRequest(String templateId, List<String> userFileIds) {}
    public record Confirm(boolean confirmed) {}
    public record Step(int number, String userFileId, String fileName,
                       String state, String originalParentId) {}
    public record Audit(int number, String action, String at) {}
    public record Run(String id, String templateId, String name, String mimeCategory,
                      String destinationId, String destinationName, String status,
                      String expiresAt, int matched, int blocked,
                      List<Step> steps, List<Audit> events) {}

    private record SavedTemplate(String id, String name, String type, long destination,
                                 LocalDateTime updatedAt, String status) {}
    private record Folder(String name, LocalDateTime updatedAt) {}
    private record Source(String name, long parentId, LocalDateTime updatedAt, String mime) {}
    private record SavedStep(int number,long fileId,String name,long from,
                             LocalDateTime sourceUpdatedAt,LocalDateTime destUpdatedAt,
                             String state) {}
    private record Plan(String templateId,LocalDateTime templateUpdatedAt,
                        LocalDateTime expiresAt,String state) {}

    private final JdbcTemplate jdbc;
    private final UserFileService drive;

    public OrganizationTemplateService(JdbcTemplate jdbc,UserFileService drive) {
        this.jdbc = jdbc;
        this.drive = drive;
    }

    static long parseId(String raw) {
        if (raw == null || !DECIMAL_ID.matcher(raw).matches())
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid canonical file ID");
        try { return Long.parseLong(raw); }
        catch (NumberFormatException exc) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "file ID out of range");
        }
    }

    static List<Long> parseSelection(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 8)
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "choose 1-8 unique files");
        List<Long> result = new ArrayList<>();
        Set<Long> unique = new HashSet<>();
        for (String value : ids) {
            long id = parseId(value);
            if (!unique.add(id))
                throw BizException.of(ErrorCode.INVALID_PARAMETER, "duplicate file");
            result.add(id);
        }
        return List.copyOf(result);
    }

    static String checkedName(String raw) {
        if (raw == null) throw BizException.of(ErrorCode.INVALID_PARAMETER, "template name required");
        String name = raw.strip();
        if (name.isEmpty() || name.length() > 80 || name.codePoints().anyMatch(
                c -> Character.isISOControl(c) || c == '<' || c == '>'))
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid template name");
        return name;
    }

    private static void uuid(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches())
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid plan reference");
    }

    static boolean matches(String category,String mime) {
        if (mime == null) return false;
        String clean = mime.split(";",2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        return switch (category) {
            case "ANY" -> true;
            case "TEXT" -> clean.startsWith("text/");
            case "PDF" -> clean.equals("application/pdf");
            case "IMAGE" -> clean.startsWith("image/");
            case "VIDEO" -> clean.startsWith("video/");
            default -> false;
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public Template create(long userId,CreateTemplate req) {
        if (req == null || !TYPES.contains(req.mimeCategory()))
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid organizing category");
        String name = checkedName(req.name());
        long destination = parseId(req.destinationId());
        Folder folder = folder(userId,destination,true);
        if (folder == null) throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO ai_organize_template(id,user_id,display_name,mime_category,destination_id)
            VALUES (?,?,?,?,?)
            """,id,userId,name,req.mimeCategory(),destination);
        return new Template(id,name,req.mimeCategory(),Long.toString(destination),
                folder.name(),LocalDateTime.now().toString());
    }

    @Transactional(readOnly = true)
    public List<Template> templates(long userId) {
        return jdbc.query("""
            SELECT t.id,t.display_name,t.mime_category,t.destination_id,
                   COALESCE(d.file_name,'目录不可用'),t.created_at
            FROM ai_organize_template t LEFT JOIN user_file d
                ON d.id=t.destination_id AND d.user_id=t.user_id AND d.status=0 AND d.is_dir=1
            WHERE t.user_id=? AND t.status='ACTIVE'
            ORDER BY t.created_at DESC,t.id DESC LIMIT 30
            """,(rs,n)->new Template(rs.getString(1),rs.getString(2),rs.getString(3),
                Long.toString(rs.getLong(4)),rs.getString(5),
                rs.getTimestamp(6).toLocalDateTime().toString()),userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void archive(long userId,String templateId,Confirm request) {
        requireConfirm(request);
        uuid(templateId);
        if (jdbc.update("""
            UPDATE ai_organize_template SET status='ARCHIVED'
            WHERE id=? AND user_id=? AND status='ACTIVE'
            """,templateId,userId)!=1)
            throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        // Existing plans from the archived template cannot execute.
    }

    /**
     * Simulate ALL sources under live ownership before writing any run metadata.
     * Even blocked rows are intentionally recorded so users can see impact.
     * No file moves and no model calls occur here.
     */
    @Transactional(rollbackFor = Exception.class)
    public Run simulate(long userId,SimulateRequest req) {
        if (req == null) throw BizException.of(ErrorCode.INVALID_PARAMETER);
        uuid(req.templateId());
        List<Long> ids = parseSelection(req.userFileIds());
        SavedTemplate template = template(userId,req.templateId(),true);
        Folder dest = folder(userId,template.destination(),true);
        if (dest == null) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        List<Source> sources = new ArrayList<>();
        for (Long id : ids) {
            Source source = source(userId,id,true);
            if (source == null) throw BizException.of(ErrorCode.FILE_NOT_FOUND);
            sources.add(source);
        }
        List<String> outcomes = new ArrayList<>();
        for (int i=0;i<ids.size();i++) {
            Source source=sources.get(i);
            String state = !matches(template.type(),source.mime()) ? "NOT_MATCHED"
                    : source.parentId()==template.destination() ? "ALREADY_THERE"
                    : siblingConflict(userId,template.destination(),source.name(),ids.get(i))
                    ? "NAME_CONFLICT" : "READY";
            outcomes.add(state);
        }
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO ai_organize_run(id,user_id,template_id,template_updated_at,expires_at)
            VALUES (?,?,?,?,DATE_ADD(NOW(3),INTERVAL 10 MINUTE))
            """,id,userId,template.id(),template.updatedAt());
        for(int i=0;i<ids.size();i++){
            Source src=sources.get(i);
            jdbc.update("""
                INSERT INTO ai_organize_run_step
                (run_id,step_no,user_file_id,original_name,original_parent_id,
                 source_updated_at,dest_updated_at,status)
                VALUES (?,?,?,?,?,?,?,?)
                """,id,i+1,ids.get(i),src.name(),src.parentId(),src.updatedAt(),
                dest.updatedAt(),outcomes.get(i));
        }
        audit(userId,id,null,"SIMULATED");
        return detail(userId,id);
    }

    @Transactional(readOnly = true)
    public List<Run> recent(long userId) {
        List<String> ids=jdbc.query("""
            SELECT id FROM ai_organize_run WHERE user_id=?
            ORDER BY created_at DESC,id DESC LIMIT 12
            """,(rs,n)->rs.getString(1),userId);
        return ids.stream().map(id -> detail(userId,id)).toList();
    }

    @Transactional(readOnly = true)
    public Run detail(long userId,String id) {
        uuid(id);
        Plan plan=plan(userId,id,false);
        SavedTemplate policy=template(userId,plan.templateId(),false);
        Folder folder=folder(userId,policy.destination(),false);
        List<SavedStep> rows=steps(id);
        List<Step> shown=rows.stream().map(s->new Step(s.number(),Long.toString(s.fileId()),
                s.name(),s.state(),Long.toString(s.from()))).toList();
        List<Audit> events=jdbc.query("""
            SELECT COALESCE(step_no,0),action,created_at
            FROM ai_organize_run_event WHERE run_id=? AND user_id=?
            ORDER BY id DESC LIMIT 40
            """,(rs,n)->new Audit(rs.getInt(1),rs.getString(2),
                rs.getTimestamp(3).toLocalDateTime().toString()),id,userId);
        int matched=(int)rows.stream().filter(s->"READY".equals(s.state()) ||
                "APPROVED".equals(s.state()) || "APPLIED".equals(s.state())).count();
        int blocked=(int)rows.stream().filter(s->Set.of("NOT_MATCHED","ALREADY_THERE","NAME_CONFLICT")
                .contains(s.state())).count();
        boolean done=rows.stream().allMatch(s->TERMINAL.contains(s.state()));
        String state=!"ACTIVE".equals(plan.state()) ? "CANCELLED" :
                done ? "COMPLETED" : plan.expiresAt().isBefore(LocalDateTime.now()) ?
                "EXPIRED" : "ACTIVE";
        return new Run(id,policy.id(),policy.name(),policy.type(),Long.toString(policy.destination()),
                folder==null?"目录不可用":folder.name(),state,
                plan.expiresAt().toString(),matched,blocked,shown,events);
    }

    @Transactional(rollbackFor = Exception.class)
    public Run approve(long userId,String runId,int stepNo,Confirm confirmed) {
        requireConfirm(confirmed);
        Plan plan=activePlan(userId,runId);
        SavedStep step=eligible(steps(runId),stepNo);
        if (!"READY".equals(step.state())) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        update(runId,stepNo,"READY","APPROVED");
        audit(userId,runId,stepNo,"APPROVED");
        return detail(userId,runId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Run execute(long userId,String runId,int stepNo,Confirm confirmed) {
        requireConfirm(confirmed);
        Plan plan=activePlan(userId,runId);
        SavedStep step=eligible(steps(runId),stepNo);
        if (!"APPROVED".equals(step.state())) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        SavedTemplate policy=template(userId,plan.templateId(),true);
        if (!policy.updatedAt().equals(plan.templateUpdatedAt()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        Folder destination=folder(userId,policy.destination(),true);
        Source current=source(userId,step.fileId(),true);
        if(destination==null || current==null ||
                !destination.updatedAt().equals(step.destUpdatedAt()) ||
                !current.name().equals(step.name()) ||
                current.parentId()!=step.from() ||
                !current.updatedAt().equals(step.sourceUpdatedAt()) ||
                !matches(policy.type(),current.mime()) ||
                siblingConflict(userId,policy.destination(),current.name(),step.fileId()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        // Exactly one explicitly approved item; existing drive policy checks again.
        drive.move(step.fileId(),userId,policy.destination());
        update(runId,stepNo,"APPROVED","APPLIED");
        audit(userId,runId,stepNo,"APPLIED");
        return detail(userId,runId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Run skip(long userId,String runId,int stepNo,Confirm confirmed) {
        requireConfirm(confirmed);
        activePlan(userId,runId);
        SavedStep step=eligible(steps(runId),stepNo);
        if (TERMINAL.contains(step.state())) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        update(runId,stepNo,step.state(),"SKIPPED");
        audit(userId,runId,stepNo,"SKIPPED");
        return detail(userId,runId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Run cancel(long userId,String runId,Confirm confirmed) {
        requireConfirm(confirmed);
        Plan plan=activePlan(userId,runId);
        if (steps(runId).stream().allMatch(s->TERMINAL.contains(s.state())))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        if (jdbc.update("""
            UPDATE ai_organize_run SET status='CANCELLED'
            WHERE id=? AND user_id=? AND status='ACTIVE'
            """,runId,userId)!=1) throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        audit(userId,runId,null,"CANCELLED");
        return detail(userId,runId);
    }

    private Plan activePlan(long userId,String id) {
        Plan plan=plan(userId,id,true);
        if (!"ACTIVE".equals(plan.state()) ||
                !plan.expiresAt().isAfter(LocalDateTime.now()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        return plan;
    }

    private Plan plan(long userId,String id,boolean lock) {
        uuid(id);
        List<Plan> found=jdbc.query("""
            SELECT template_id,template_updated_at,expires_at,status
            FROM ai_organize_run WHERE id=? AND user_id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->new Plan(rs.getString(1),
                rs.getTimestamp(2).toLocalDateTime(),
                rs.getTimestamp(3).toLocalDateTime(),rs.getString(4)),id,userId);
        if (found.size()!=1) throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return found.getFirst();
    }

    private SavedTemplate template(long userId,String id,boolean lock) {
        uuid(id);
        List<SavedTemplate> found=jdbc.query("""
            SELECT id,display_name,mime_category,destination_id,updated_at,status
            FROM ai_organize_template WHERE id=? AND user_id=?
            """+(lock?" FOR UPDATE":""),(rs,n)->new SavedTemplate(
                rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                rs.getTimestamp(5).toLocalDateTime(),rs.getString(6)),id,userId);
        if (found.size()!=1) throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        if (lock && !"ACTIVE".equals(found.getFirst().status()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        return found.getFirst();
    }

    private Folder folder(long userId,long id,boolean lock) {
        List<Folder> rows=jdbc.query("""
            SELECT file_name,updated_at FROM user_file
            WHERE id=? AND user_id=? AND status=0 AND is_dir=1
            """+(lock?" FOR UPDATE":""),(rs,n)->new Folder(rs.getString(1),
                rs.getTimestamp(2).toLocalDateTime()),id,userId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private Source source(long userId,long id,boolean lock) {
        List<Source> rows=jdbc.query("""
            SELECT u.file_name,u.parent_id,u.updated_at,f.mime_type FROM user_file u
            JOIN file_meta f ON f.file_id=u.file_id AND f.status=2
            WHERE u.id=? AND u.user_id=? AND u.status=0 AND u.is_dir=0
            """+(lock?" FOR UPDATE":""),(rs,n)->new Source(rs.getString(1),rs.getLong(2),
                rs.getTimestamp(3).toLocalDateTime(),rs.getString(4)),id,userId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private boolean siblingConflict(long userId,long destination,String name,long exclude) {
        List<Long> found=jdbc.query("""
            SELECT id FROM user_file
            WHERE user_id=? AND parent_id=? AND status=0 AND file_name=? AND id<>?
            LIMIT 1
            """,(rs,n)->rs.getLong(1),userId,destination,name,exclude);
        return !found.isEmpty();
    }

    private List<SavedStep> steps(String id) {
        return jdbc.query("""
            SELECT step_no,user_file_id,original_name,original_parent_id,
                   source_updated_at,dest_updated_at,status
            FROM ai_organize_run_step WHERE run_id=? ORDER BY step_no
            """,(rs,n)->new SavedStep(rs.getInt(1),rs.getLong(2),rs.getString(3),
                rs.getLong(4),rs.getTimestamp(5).toLocalDateTime(),
                rs.getTimestamp(6).toLocalDateTime(),rs.getString(7)),id);
    }

    /** Only after all previous steps are applied or explicitly skipped. */
    private static SavedStep eligible(List<SavedStep> steps,int stepNo) {
        if (stepNo<1 || stepNo>steps.size())
            throw BizException.of(ErrorCode.INVALID_PARAMETER);
        for(int i=0;i<stepNo-1;i++)
            if (!TERMINAL.contains(steps.get(i).state()))
                throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        return steps.get(stepNo-1);
    }

    private void update(String id,int stepNo,String from,String to) {
        if (jdbc.update("""
            UPDATE ai_organize_run_step SET status=?
            WHERE run_id=? AND step_no=? AND status=?
            """,to,id,stepNo,from)!=1)
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
    }

    private static void requireConfirm(Confirm request) {
        if (request==null || !request.confirmed())
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"explicit confirmation required");
    }

    private void audit(long userId,String run,Integer step,String action) {
        jdbc.update("""
            INSERT INTO ai_organize_run_event(run_id,user_id,step_no,action)
            VALUES (?,?,?,?)
            """,run,userId,step,action);
    }
}
