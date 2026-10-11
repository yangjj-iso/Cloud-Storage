package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.contract.event.FileAvailable;
import com.cloudchunk.contract.event.IntegrationEventPublisher;
import com.cloudchunk.core.knowledge.KnowledgeModels.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** All read paths join live user_file ownership; public shares and physical file IDs are not authority. */
@Service
public class KnowledgeService {
    private static final int PAGE_SIZE = 20;
    private static final String DOCUMENTS = """
        FROM user_file u JOIN file_meta f ON f.file_id=u.file_id
        LEFT JOIN knowledge_document k ON k.file_id=u.file_id
        WHERE u.user_id=? AND u.status=0 AND u.is_dir=0 AND f.status=2
        AND LOWER(SUBSTRING_INDEX(f.mime_type,';',1)) IN
        ('text/plain','text/markdown','text/x-markdown','application/pdf',
         'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
        """;
    private static final String EVIDENCE = """
        SELECT u.id,u.file_name,k.generation,c.chunk_index,c.start_offset,c.end_offset,c.content,k.status
        FROM user_file u JOIN file_meta f ON f.file_id=u.file_id
        JOIN knowledge_document k ON k.file_id=u.file_id
        JOIN knowledge_chunk c ON c.file_id=k.file_id AND c.generation=k.generation
        WHERE u.user_id=? AND u.status=0 AND u.is_dir=0 AND f.status=2
        AND k.status IN ('READY','PARTIAL')
        """;
    private final JdbcTemplate jdbc;
    private final IntegrationEventPublisher events;
    public KnowledgeService(JdbcTemplate jdbc, IntegrationEventPublisher events) {
        // Do not mutate the shared JdbcTemplate's timeout for unrelated upload/storage queries.
        this.jdbc = new JdbcTemplate(jdbc.getDataSource());
        this.jdbc.setQueryTimeout(5);
        this.events = events;
    }

    public Library library(long userId, int page) {
        int offset = offset(page);
        List<Document> rows = jdbc.query("""
            SELECT u.id,u.file_name,f.mime_type,COALESCE(k.status,'NOT_INDEXED') AS status,
            COALESCE(k.generation,0) AS generation,COALESCE(k.content_chars,0) AS content_chars,
            COALESCE(k.chunk_count,0) AS chunk_count,k.reason
            """ + DOCUMENTS + " ORDER BY u.updated_at DESC,u.id DESC LIMIT 21 OFFSET ?",
            (rs, n) -> new Document(rs.getString("id"), rs.getString("file_name"), rs.getString("mime_type"),
                rs.getString("status"), rs.getLong("generation"), rs.getInt("content_chars"),
                rs.getInt("chunk_count"), rs.getString("reason")), userId, offset);
        return new Library(firstPage(rows), rows.size() > PAGE_SIZE, page);
    }

    public SearchResult search(long userId, String query) {
        if (query == null || query.strip().length() < 2 || query.strip().length() > 100) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "请输入 2 到 100 个字符的关键词");
        }
        // Literal substring baseline: %, _, quotes and backslashes are data, never SQL wildcards.
        List<Evidence> rows = jdbc.query(EVIDENCE + " AND LOCATE(LOWER(?),LOWER(c.content))>0"
                + " ORDER BY u.updated_at DESC,u.id,c.chunk_index LIMIT 21",
                (rs, n) -> evidence(rs), userId, query.strip());
        return new SearchResult(firstPage(rows), rows.size() > PAGE_SIZE, "LITERAL_KEYWORD");
    }

    /** Read exactly one evidence span under live ACL; never trust browser-supplied content. */
    public Evidence source(long userId, long userFileId, long generation, int chunkIndex) {
        if (generation < 1 || chunkIndex < 0) return null;
        List<Evidence> rows = jdbc.query(EVIDENCE
                + " AND u.id=? AND k.generation=? AND c.chunk_index=? LIMIT 1",
                (rs, n) -> evidence(rs), userId, userFileId, generation, chunkIndex);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public BlockPage blocks(long userId, long userFileId, long generation, int page) {
        authorize(userId, userFileId);
        List<Evidence> rows = jdbc.query(EVIDENCE + " AND u.id=? AND k.generation=?"
                + " ORDER BY c.chunk_index LIMIT 21 OFFSET ?", (rs, n) -> evidence(rs),
                userId, userFileId, generation, offset(page));
        // Empty also means a stale generation. The UI does not silently switch evidence versions.
        return new BlockPage(firstPage(rows), rows.size() > PAGE_SIZE, page);
    }

    @Transactional
    public void requestIndex(long userId, long userFileId) {
        String fileId = authorize(userId, userFileId);
        int inserted = jdbc.update("INSERT IGNORE INTO knowledge_document(file_id) VALUES (?)", fileId);
        if (inserted == 0) {
            int reset = jdbc.update("""
                UPDATE knowledge_document SET status='PENDING',attempt_count=0,reason=NULL,updated_at=NOW(6)
                WHERE file_id=? AND status='FAILED' AND updated_at < DATE_SUB(NOW(6),INTERVAL 1 MINUTE)
                """, fileId);
            if (reset == 0) return;
        }
        events.enqueue(FileAvailable.TOPIC, fileId, FileAvailable.of(fileId));
    }

    /**
     * All-or-nothing authorization BEFORE queueing any extraction event. This
     * operation only submits existing, AVAILABLE files owned by the current user.
     * Semantic embedding requires a distinct, per-file consented action.
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchIndexResult requestIndexBatch(long userId, List<Long> userFileIds) {
        if (userFileIds == null || userFileIds.isEmpty() || userFileIds.size() > 8
                || userFileIds.stream().anyMatch(id -> id == null || id <= 0)
                || userFileIds.stream().distinct().count() != userFileIds.size()) {
            throw BizException.of(ErrorCode.INVALID_PARAMETER, "select 1-8 unique document entries");
        }
        // Reject the entire request before any database write or outbox enqueue.
        for (Long id : userFileIds) authorize(userId, id);
        for (Long id : userFileIds) requestIndex(userId, id);
        return new BatchIndexResult(userFileIds.size(), 8);
    }

    @Transactional
    public void recoverPending() {
        jdbc.update("""
            UPDATE knowledge_document SET status='FAILED',claim_token=NULL,lease_until=NULL,
                reason='解析重试已耗尽，请检查文件后手动重试',updated_at=NOW(6)
            WHERE status='EXTRACTING' AND lease_until < NOW(6) AND attempt_count >= ?
            """, KnowledgePolicy.MAX_ATTEMPTS);
        List<String> ids = jdbc.query("""
            SELECT file_id FROM knowledge_document WHERE attempt_count < ? AND
              ((status IN ('PENDING','FAILED') AND updated_at < DATE_SUB(NOW(6),INTERVAL 1 MINUTE))
               OR (status='EXTRACTING' AND lease_until < NOW(6))) ORDER BY updated_at LIMIT 20
            """, (rs,n) -> rs.getString(1), KnowledgePolicy.MAX_ATTEMPTS);
        for (String id : ids) {
            int changed = jdbc.update("""
                UPDATE knowledge_document SET updated_at=NOW(6)
                WHERE file_id=? AND updated_at < DATE_SUB(NOW(6),INTERVAL 1 MINUTE)
                AND attempt_count < ? AND (status IN ('PENDING','FAILED')
                    OR (status='EXTRACTING' AND lease_until < NOW(6)))
                """, id, KnowledgePolicy.MAX_ATTEMPTS);
            if (changed > 0) events.enqueue(FileAvailable.TOPIC, id, FileAvailable.of(id));
        }
    }

    private String authorize(long userId, long userFileId) {
        List<String> files = jdbc.query("SELECT u.file_id " + DOCUMENTS + " AND u.id=?",
                (rs, n) -> rs.getString(1), userId, userFileId);
        if (files.isEmpty()) throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return files.getFirst();
    }
    private static Evidence evidence(ResultSet rs) throws SQLException {
        return new Evidence(rs.getString("id"), rs.getString("file_name"), rs.getLong("generation"),
            rs.getInt("chunk_index"), rs.getInt("start_offset"), rs.getInt("end_offset"),
            rs.getString("content"), rs.getString("status"));
    }
    private static int offset(int page) {
        if (page < 1 || page > 10000) throw BizException.of(ErrorCode.INVALID_PARAMETER, "invalid page");
        return (page - 1) * PAGE_SIZE;
    }
    private static <T> List<T> firstPage(List<T> rows) { return rows.stream().limit(PAGE_SIZE).toList(); }
}
