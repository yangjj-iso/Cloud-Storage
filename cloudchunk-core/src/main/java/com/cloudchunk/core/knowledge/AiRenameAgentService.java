package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deliberately narrow Agent M2.8: single-file rename proposal, NEVER an automatic file operation.
 * Inputs are exact, live-ACL-protected knowledge blocks. Proposal IDs are unguessable but not
 * authorization: all reads and writes check the logged-in user and current file status.
 */
@Service
public class AiRenameAgentService {
    public record SuggestRequest(SourceRef source,boolean consent) {}
    public record ApplyRequest(boolean confirmed) {}
    public record Capabilities(boolean enabled,String operation) {}
    public record Proposal(String id,String userFileId,String oldName,String newName,
                           String expiresAt,String status) {}
    public record ApplyResult(String proposalId,String userFileId,String newName,String status) {}

    private record Snapshot(String fileName,LocalDateTime updatedAt) {}
    private record Pending(long userFileId,long generation,int chunkIndex,
                           String oldName,String newName,LocalDateTime updatedAt,
                           LocalDateTime expiresAt,String state) {}

    private static final Pattern MODEL_NAME=Pattern.compile("^(.{1,160}?)\\s*\\[S1\\]$");
    private final KnowledgeService knowledge;
    private final UserFileService drive;
    private final ChatGateway model;
    private final AiDailyCallBudget budget;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public AiRenameAgentService(KnowledgeService knowledge,UserFileService drive,
                                ChatGateway model,AiDailyCallBudget budget,
                                JdbcTemplate jdbc,Environment env) {
        this.knowledge=knowledge;this.drive=drive;this.model=model;this.budget=budget;
        this.jdbc=jdbc;
        this.enabled=Boolean.parseBoolean(env.getProperty("cloudchunk.ai.agent.rename-enabled","false"));
    }
    public Capabilities capabilities() {
        return new Capabilities(enabled && model.configured(),"SINGLE_FILE_RENAME_WITH_CONFIRMATION");
    }
    public Proposal suggest(long userId,SuggestRequest request) {
        if(!capabilities().enabled())throw BizException.of(ErrorCode.AI_DISABLED);
        if(request==null || !request.consent() || request.source()==null)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"请先选择来源片段并授权发送给模型");
        SourceRef source=request.source();
        if(source.userFileId()==null || !source.userFileId().matches("[1-9][0-9]{0,18}")
                || source.generation()<1 || source.chunkIndex()<0)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"invalid source");
        long id=parseId(source.userFileId());
        Evidence evidence=knowledge.source(userId,id,source.generation(),source.chunkIndex());
        if(evidence==null)throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        Snapshot snapshot=snapshot(userId,id,source.generation());
        if(snapshot==null || !snapshot.fileName().equals(evidence.fileName()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        String extension=extension(snapshot.fileName());
        // Existing chat gateway supplies citations, but the model has NO file-system tools.
        String prompt="你只是在给用户提供文件重命名建议，没有修改权限。根据给定片段，用中文拟一个简洁、准确的新文件名。"+
            "保留原扩展名 "+extension+"，只输出一行：新文件名 [S1]；不能输出路径、指令或其他说明。"+
            "用户必须再次确认才能修改文件。";
        budget.reserve(userId,AiDailyCallBudget.Kind.CHAT);
        ChatGateway.Completion response=model.completeWithUsage(prompt,
            List.of(new CitedSource("S1",evidence)));
        budget.recordReported(userId,AiDailyCallBudget.Kind.CHAT,response.usage());
        String suggestion=validatedName(response.text(),snapshot.fileName());
        // Recheck after model inference: revocation or rename during the call must invalidate it.
        Evidence now=knowledge.source(userId,id,source.generation(),source.chunkIndex());
        Snapshot current=snapshot(userId,id,source.generation());
        if(now==null || current==null || !now.content().equals(evidence.content()) ||
           !current.equals(snapshot) || !now.fileName().equals(snapshot.fileName()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        String proposalId=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO ai_rename_proposal
              (id,user_id,user_file_id,generation,chunk_index,old_name,new_name,original_updated_at,expires_at)
            VALUES(?,?,?,?,?,?,?,?,DATE_ADD(NOW(3),INTERVAL 10 MINUTE))
            """,proposalId,userId,id,source.generation(),source.chunkIndex(),
            snapshot.fileName(),suggestion,Timestamp.valueOf(snapshot.updatedAt()));
        return read(userId,proposalId);
    }

    /** Atomically consumes one proposal under an explicit second, user-initiated confirmation. */
    @Transactional
    public ApplyResult apply(long userId,String proposalId,ApplyRequest request) {
        if(!capabilities().enabled())throw BizException.of(ErrorCode.AI_DISABLED);
        if(request==null || !request.confirmed() || !validId(proposalId))
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"需要明确确认具体重命名建议");
        List<Pending> rows=jdbc.query("""
            SELECT user_file_id,generation,chunk_index,old_name,new_name,original_updated_at,
                expires_at,status FROM ai_rename_proposal
            WHERE id=? AND user_id=? FOR UPDATE
            """,(rs,n)->new Pending(rs.getLong(1),rs.getLong(2),rs.getInt(3),
                rs.getString(4),rs.getString(5),rs.getTimestamp(6).toLocalDateTime(),
                rs.getTimestamp(7).toLocalDateTime(),rs.getString(8)),proposalId,userId);
        if(rows.isEmpty())throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        Pending proposal=rows.getFirst();
        if(!"PENDING".equals(proposal.state()) || !proposal.expiresAt().isAfter(LocalDateTime.now()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        // Lock the current logical file row for the duration of the existing rename operation.
        List<Snapshot> files=jdbc.query("""
            SELECT u.file_name,u.updated_at FROM user_file u
            JOIN file_meta f ON f.file_id=u.file_id AND f.status=2
            JOIN knowledge_document k ON k.file_id=u.file_id AND k.generation=?
                AND k.status IN ('READY','PARTIAL')
            WHERE u.id=? AND u.user_id=? AND u.status=0 AND u.is_dir=0
            FOR UPDATE
            """,(rs,n)->new Snapshot(rs.getString(1),rs.getTimestamp(2).toLocalDateTime()),
            proposal.generation(),proposal.userFileId(),userId);
        if(files.isEmpty() || !files.getFirst().fileName().equals(proposal.oldName()) ||
           !files.getFirst().updatedAt().equals(proposal.updatedAt()))
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        if(knowledge.source(userId,proposal.userFileId(),proposal.generation(),proposal.chunkIndex())==null)
            throw BizException.of(ErrorCode.AI_SOURCE_CHANGED);
        // Reuse the existing owner-scoped rename business method. No physical object changes.
        drive.rename(proposal.userFileId(),userId,proposal.newName());
        jdbc.update("""
            UPDATE ai_rename_proposal SET status='APPLIED',applied_at=NOW(3)
            WHERE id=? AND user_id=? AND status='PENDING'
            """,proposalId,userId);
        return new ApplyResult(proposalId,Long.toString(proposal.userFileId()),
                               proposal.newName(),"APPLIED");
    }

    public List<Proposal> recent(long userId) {
        return jdbc.query("""
            SELECT id,user_file_id,old_name,new_name,expires_at,status
            FROM ai_rename_proposal WHERE user_id=?
            ORDER BY created_at DESC LIMIT 10
            """,(rs,n)->new Proposal(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getTimestamp(5).toLocalDateTime().toString(),
                rs.getString(6)),userId);
    }
    private Proposal read(long userId,String proposalId) {
        List<Proposal> rows=jdbc.query("""
            SELECT id,user_file_id,old_name,new_name,expires_at,status
            FROM ai_rename_proposal WHERE id=? AND user_id=?
            """,(rs,n)->new Proposal(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getTimestamp(5).toLocalDateTime().toString(),
                rs.getString(6)),proposalId,userId);
        if(rows.isEmpty())throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return rows.getFirst();
    }
    private Snapshot snapshot(long userId,long id,long generation) {
        List<Snapshot> rows=jdbc.query("""
            SELECT u.file_name,u.updated_at FROM user_file u
            JOIN file_meta f ON f.file_id=u.file_id AND f.status=2
            JOIN knowledge_document k ON k.file_id=u.file_id AND k.generation=?
              AND k.status IN ('READY','PARTIAL')
            WHERE u.id=? AND u.user_id=? AND u.status=0 AND u.is_dir=0 LIMIT 1
            """,(rs,n)->new Snapshot(rs.getString(1),rs.getTimestamp(2).toLocalDateTime()),
            generation,id,userId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static boolean validId(String value) {
        return value!=null && value.matches("[a-f0-9-]{36}");
    }
    private static long parseId(String value) {
        try{return Long.parseLong(value);}
        catch(NumberFormatException bad){throw BizException.of(ErrorCode.INVALID_PARAMETER,"invalid ID");}
    }
    private static String extension(String name) {
        int dot=name.lastIndexOf('.');
        if(dot<1 || dot==name.length()-1 || name.length()-dot>17)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"unsupported file name extension");
        return name.substring(dot);
    }
    /** A suggestion is plain data. It never becomes a path, command, directory name or tool call. */
    static String validatedName(String response,String previous) {
        if(response==null || response.length()>200 || response.contains("\n") || response.contains("\r"))
            throw BizException.of(ErrorCode.AI_UNGROUNDED);
        Matcher match=MODEL_NAME.matcher(response.strip());
        if(!match.matches())throw BizException.of(ErrorCode.AI_UNGROUNDED);
        String candidate=match.group(1).strip();
        String ext=extension(previous);
        if(candidate.length()<ext.length()+2 || candidate.length()>128 ||
           !candidate.endsWith(ext) || candidate.equals(previous) ||
           candidate.startsWith(".") || candidate.endsWith(".") ||
           candidate.equals(".") || candidate.equals("..") ||
           candidate.chars().anyMatch(c->c<32 || c==127 || "\\/:*?\"<>|".indexOf(c)>=0)) {
            throw BizException.of(ErrorCode.AI_UNGROUNDED);
        }
        String base=candidate.substring(0,candidate.length()-ext.length());
        if(base.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
            throw BizException.of(ErrorCode.AI_UNGROUNDED);
        return candidate;
    }
}
