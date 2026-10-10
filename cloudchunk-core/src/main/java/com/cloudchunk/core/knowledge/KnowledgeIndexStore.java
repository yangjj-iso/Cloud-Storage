package com.cloudchunk.core.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Claim fencing and atomic publication keep duplicated or late workers from overwriting a newer run. */
@Repository
public class KnowledgeIndexStore {
    public record Claim(String fileId, long generation, String token) {}
    private final JdbcTemplate jdbc;
    public KnowledgeIndexStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Claim claim(String fileId) {
        jdbc.update("INSERT IGNORE INTO knowledge_document(file_id) VALUES (?)", fileId);
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("""
            UPDATE knowledge_document SET status='EXTRACTING', generation=generation+1,
                attempt_count=attempt_count+1, claim_token=?, lease_until=DATE_ADD(NOW(6), INTERVAL 30 MINUTE),
                updated_at=NOW(6), reason=NULL
            WHERE file_id=? AND attempt_count < ? AND
                (status IN ('PENDING','FAILED') OR (status='EXTRACTING' AND lease_until < NOW(6)))
            """, token, fileId, KnowledgePolicy.MAX_ATTEMPTS);
        if (changed == 0) {
            List<String> states = jdbc.query("SELECT status FROM knowledge_document WHERE file_id=?",
                    (rs, n) -> rs.getString(1), fileId);
            if (states.contains("EXTRACTING")) throw new IllegalStateException("index is busy");
            return null;
        }
        Long generation = jdbc.queryForObject("SELECT generation FROM knowledge_document WHERE file_id=?",
                Long.class, fileId);
        return new Claim(fileId, generation, token);
    }

    @Transactional
    public void publish(Claim claim, String text, String state, String reason) {
        List<String> tokens = jdbc.query("SELECT claim_token FROM knowledge_document WHERE file_id=? FOR UPDATE",
                (rs, n) -> rs.getString(1), claim.fileId());
        if (tokens.isEmpty() || !claim.token().equals(tokens.getFirst())) return;
        // Authoritative availability is checked again before publishing any derived content.
        Integer active = jdbc.queryForObject("""
            SELECT COUNT(*) FROM file_meta f WHERE f.file_id=? AND f.status=2
            AND EXISTS (SELECT 1 FROM user_file u WHERE u.file_id=f.file_id AND u.status=0)
            """, Integer.class, claim.fileId());
        if (active == null || active == 0) {
            jdbc.update("DELETE FROM knowledge_document WHERE file_id=?", claim.fileId());
            return;
        }
        List<TextChunker.Chunk> chunks = TextChunker.split(text, 1200);
        jdbc.update("DELETE FROM knowledge_chunk WHERE file_id=?", claim.fileId());
        jdbc.batchUpdate("""
            INSERT INTO knowledge_chunk(file_id,generation,chunk_index,start_offset,end_offset,content)
            VALUES (?,?,?,?,?,?)
            """, chunks, 100, (ps, c) -> {
                ps.setString(1, claim.fileId()); ps.setLong(2, claim.generation());
                ps.setInt(3, c.index()); ps.setInt(4, c.startOffset());
                ps.setInt(5, c.endOffset()); ps.setString(6, c.content());
            });
        jdbc.update("""
            UPDATE knowledge_document SET status=?, content_sha256=?, content_chars=?, chunk_count=?,
                parser_version=?, reason=?, claim_token=NULL, lease_until=NULL, updated_at=NOW(6)
            WHERE file_id=? AND claim_token=?
            """, state, sha256(text), text.length(), chunks.size(), KnowledgePolicy.PARSER_VERSION,
                reason, claim.fileId(), claim.token());
    }

    public void failed(Claim claim) {
        // No raw exception, document content, object key or credential is persisted in the user-visible reason.
        jdbc.update("""
            UPDATE knowledge_document SET status='FAILED', reason='解析失败，可重试；加密或损坏文件请先修复',
                claim_token=NULL, lease_until=NULL, updated_at=NOW(6)
            WHERE file_id=? AND claim_token=?
            """, claim.fileId(), claim.token());
    }

    public List<String> retryableFiles() {
        return jdbc.query("""
            SELECT file_id FROM knowledge_document WHERE attempt_count < ? AND
              ((status IN ('PENDING','FAILED') AND updated_at < DATE_SUB(NOW(6), INTERVAL 1 MINUTE))
               OR (status='EXTRACTING' AND lease_until < NOW(6)))
            ORDER BY updated_at LIMIT 20
            """, (rs, n) -> rs.getString(1), KnowledgePolicy.MAX_ATTEMPTS);
    }

    public void cleanDeleted() {
        // Bound each run. Recycled files retain their index for restore, but every read rejects them.
        List<String> ids = jdbc.query("""
            SELECT k.file_id FROM knowledge_document k LEFT JOIN file_meta f ON f.file_id=k.file_id
            WHERE f.file_id IS NULL OR f.status IN (4,5) LIMIT 100
            """, (rs, n) -> rs.getString(1));
        for (String id : ids) jdbc.update("DELETE FROM knowledge_document WHERE file_id=?", id);
    }

    private static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
