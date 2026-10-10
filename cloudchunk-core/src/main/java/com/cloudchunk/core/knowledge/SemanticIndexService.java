package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;

/** Explicit per-user indexing permission. The model sees one chunk at a time, off-request. */
@Service
public class SemanticIndexService {
    public record Capabilities(boolean enabled,String retrievalMode){}
    public record Status(String status,int completed,int total){}
    private record Document(String fileId,long generation,int total){}
    private record Chunk(String content,int total){}
    private record Job(String fileId,long generation,String modelKey,long userId,int offset,String token){}
    private static final int MAX_CHUNKS=64;
    private final JdbcTemplate jdbc;
    private final EmbeddingGateway provider;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public SemanticIndexService(JdbcTemplate jdbc,EmbeddingGateway provider,ObjectMapper mapper,
                                PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.provider=provider;this.mapper=mapper;
        this.transaction=new TransactionTemplate(manager);
    }
    public Capabilities capabilities() {
        return new Capabilities(provider.configured(),"BOUNDED_MYSQL_COSINE_RRF");
    }
    public Status request(long userId,long userFileId,boolean consent) {
        if(!provider.configured())throw BizException.of(ErrorCode.AI_DISABLED);
        if(!consent)throw BizException.of(ErrorCode.INVALID_PARAMETER,"请明确同意发送所选文档的正文片段");
        List<Document> docs=jdbc.query("""
            SELECT k.file_id,k.generation,k.chunk_count FROM user_file u
            JOIN file_meta f ON f.file_id=u.file_id JOIN knowledge_document k ON k.file_id=u.file_id
            WHERE u.id=? AND u.user_id=? AND u.status=0 AND u.is_dir=0 AND f.status=2
              AND k.status IN ('READY','PARTIAL')
            """,(rs,n)->new Document(rs.getString(1),rs.getLong(2),rs.getInt(3)),userFileId,userId);
        if(docs.isEmpty())throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        Document doc=docs.getFirst();
        if(doc.total()<1 || doc.total()>MAX_CHUNKS)throw BizException.of(ErrorCode.INVALID_PARAMETER,
            "当前语义索引支持 1–64 个片段的文档");
        return transaction.execute(s->{
            jdbc.update("""
                INSERT INTO knowledge_embedding_job(file_id,generation,model_key,user_id,status)
                VALUES (?,?,?,?,'PENDING')
                ON DUPLICATE KEY UPDATE
                  status=IF(status IN ('FAILED','STALE'),'PENDING',status),
                  attempts=IF(status IN ('FAILED','STALE'),0,attempts),
                  next_index=IF(status IN ('FAILED','STALE'),0,next_index),
                  updated_at=NOW(6)
                """,doc.fileId(),doc.generation(),provider.modelKey(),userId);
            return statusByKey(doc,userId);
        });
    }
    public Status status(long userId,long userFileId) {
        List<Document> docs=jdbc.query("""
            SELECT k.file_id,k.generation,k.chunk_count FROM user_file u
            JOIN knowledge_document k ON k.file_id=u.file_id
            WHERE u.id=? AND u.user_id=? AND u.status=0 AND u.is_dir=0
            """,(rs,n)->new Document(rs.getString(1),rs.getLong(2),rs.getInt(3)),userFileId,userId);
        if(docs.isEmpty())throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        return statusByKey(docs.getFirst(),userId);
    }
    public void revoke(long userId,long userFileId) {
        List<String> ids=jdbc.query("SELECT file_id FROM user_file WHERE id=? AND user_id=? AND is_dir=0",
            (rs,n)->rs.getString(1),userFileId,userId);
        if(ids.isEmpty())throw BizException.of(ErrorCode.FILE_NOT_FOUND);
        transaction.executeWithoutResult(s->{
            jdbc.update("DELETE FROM knowledge_embedding_job WHERE file_id=? AND user_id=?",ids.getFirst(),userId);
            jdbc.update("""
                DELETE v FROM knowledge_vector v WHERE v.file_id=? AND NOT EXISTS
                  (SELECT 1 FROM knowledge_embedding_job j WHERE j.file_id=v.file_id
                    AND j.generation=v.generation AND j.model_key=v.model_key)
                """,ids.getFirst());
        });
    }
    public void workOne() {
        if(!provider.configured())return;
        List<Job> list=jdbc.query("""
            SELECT file_id,generation,model_key,user_id,next_index FROM knowledge_embedding_job
            WHERE model_key=? AND attempts<3
              AND (status='PENDING' OR (status='RUNNING' AND lease_until<NOW(6)))
            ORDER BY updated_at LIMIT 4
            """,(rs,n)->new Job(rs.getString(1),rs.getLong(2),rs.getString(3),
                rs.getLong(4),rs.getInt(5),null),provider.modelKey());
        for(Job next:list) {
            String token=UUID.randomUUID().toString();
            int accepted=jdbc.update("""
                UPDATE knowledge_embedding_job SET status='RUNNING',claim_token=?,
                  lease_until=DATE_ADD(NOW(6),INTERVAL 60 SECOND),
                  attempts=attempts+1,updated_at=NOW(6)
                WHERE file_id=? AND generation=? AND model_key=? AND user_id=? AND attempts<3
                  AND (status='PENDING' OR (status='RUNNING' AND lease_until<NOW(6)))
                """,token,next.fileId(),next.generation(),next.modelKey(),next.userId());
            if(accepted==0)continue;
            Job job=new Job(next.fileId(),next.generation(),next.modelKey(),
                next.userId(),next.offset(),token);
            try{embedOne(job);}catch(Exception ignored){
                jdbc.update("""
                    UPDATE knowledge_embedding_job SET status=IF(attempts>=3,'FAILED','PENDING'),
                      claim_token=NULL,lease_until=NULL,updated_at=NOW(6)
                    WHERE file_id=? AND generation=? AND model_key=? AND user_id=? AND claim_token=?
                    """,job.fileId(),job.generation(),job.modelKey(),job.userId(),job.token());
            }
            return;
        }
    }
    private void embedOne(Job job) {
        List<Chunk> chunks=jdbc.query("""
            SELECT c.content,k.chunk_count FROM knowledge_chunk c
            JOIN knowledge_document k ON k.file_id=c.file_id AND k.generation=c.generation
            JOIN file_meta f ON f.file_id=c.file_id AND f.status=2
            JOIN user_file u ON u.file_id=c.file_id AND u.user_id=? AND u.status=0 AND u.is_dir=0
            WHERE c.file_id=? AND c.generation=? AND c.chunk_index=?
              AND k.status IN ('READY','PARTIAL') LIMIT 1
            """,(rs,n)->new Chunk(rs.getString(1),rs.getInt(2)),
            job.userId(),job.fileId(),job.generation(),job.offset());
        if(chunks.isEmpty() || chunks.getFirst().total()>MAX_CHUNKS){stale(job);return;}
        Chunk chunk=chunks.getFirst();
        List<Integer> existing=jdbc.query("""
            SELECT dimensions FROM knowledge_vector WHERE file_id=? AND generation=?
              AND chunk_index=? AND model_key=?
            """,(rs,n)->rs.getInt(1),job.fileId(),job.generation(),job.offset(),job.modelKey());
        // Shared content can reuse vectors, but only after this user opted in.
        double[] vector=(existing.size()==1 && existing.getFirst()==provider.dimensions())
            ?null:provider.embed(chunk.content());
        if(vector!=null && vector.length!=provider.dimensions())throw new IllegalArgumentException("wrong dimension");
        transaction.executeWithoutResult(s->publish(job,chunk.total(),vector));
    }
    private void publish(Job job,int total,double[] vector) {
        List<Integer> rows=jdbc.query("""
            SELECT next_index FROM knowledge_embedding_job WHERE file_id=? AND generation=?
              AND model_key=? AND user_id=? AND claim_token=? AND status='RUNNING'
              AND lease_until>NOW(6) FOR UPDATE
            """,(rs,n)->rs.getInt(1),job.fileId(),job.generation(),job.modelKey(),job.userId(),job.token());
        if(rows.isEmpty() || rows.getFirst()!=job.offset())return;
        Integer canRead=jdbc.queryForObject("""
            SELECT COUNT(*) FROM knowledge_document k JOIN file_meta f ON f.file_id=k.file_id
            WHERE k.file_id=? AND k.generation=? AND f.status=2 AND k.status IN ('READY','PARTIAL')
              AND EXISTS(SELECT 1 FROM user_file u WHERE u.file_id=f.file_id
                AND u.user_id=? AND u.status=0 AND u.is_dir=0)
            """,Integer.class,job.fileId(),job.generation(),job.userId());
        if(canRead==null || canRead==0){stale(job);return;}
        if(vector!=null) {
            try {
                jdbc.update("""
                    INSERT INTO knowledge_vector(file_id,generation,chunk_index,model_key,dimensions,vector_json)
                    VALUES (?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE dimensions=VALUES(dimensions),vector_json=VALUES(vector_json)
                    """,job.fileId(),job.generation(),job.offset(),job.modelKey(),
                    provider.dimensions(),mapper.writeValueAsString(vector));
            } catch(Exception e){throw new IllegalStateException("vector persistence failed");}
        }
        int next=job.offset()+1;
        jdbc.update("""
            UPDATE knowledge_embedding_job SET status=?,next_index=?,attempts=0,
              claim_token=NULL,lease_until=NULL,updated_at=NOW(6)
            WHERE file_id=? AND generation=? AND model_key=? AND user_id=? AND claim_token=?
            """,next>=total?"READY":"PENDING",next,
            job.fileId(),job.generation(),job.modelKey(),job.userId(),job.token());
    }
    private void stale(Job job) {
        jdbc.update("""
            UPDATE knowledge_embedding_job SET status='STALE',claim_token=NULL,lease_until=NULL,
              updated_at=NOW(6) WHERE file_id=? AND generation=? AND model_key=? AND user_id=? AND claim_token=?
            """,job.fileId(),job.generation(),job.modelKey(),job.userId(),job.token());
    }
    private Status statusByKey(Document doc,long userId) {
        List<Status> rows=jdbc.query("""
            SELECT status,next_index FROM knowledge_embedding_job
            WHERE file_id=? AND generation=? AND model_key=? AND user_id=?
            """,(rs,n)->new Status(rs.getString(1),rs.getInt(2),doc.total()),
            doc.fileId(),doc.generation(),provider.modelKey(),userId);
        return rows.isEmpty()?new Status("NOT_REQUESTED",0,doc.total()):rows.getFirst();
    }
}
