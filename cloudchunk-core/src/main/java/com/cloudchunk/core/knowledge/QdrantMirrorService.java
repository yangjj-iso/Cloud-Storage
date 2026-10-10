package com.cloudchunk.core.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/** Qdrant is a disposable derived mirror of authorized MySQL READY vectors. */
@Service
public class QdrantMirrorService {
    private static final Logger log=LoggerFactory.getLogger(QdrantMirrorService.class);
    private final JdbcTemplate jdbc;
    private final EmbeddingGateway embedding;
    private final QdrantVectorStore store;
    private final ObjectMapper mapper;
    private JsonNode scrollOffset;

    /** Reports only this authenticated user's derived index counts, never global file IDs. */
    public record MirrorHealth(String state,long readyDocuments,long mirroredDocuments,
                               long pendingDocuments,boolean qdrantReachable){}

    private record Job(long userId,String fileId,long generation,int chunks){}
    private record Row(int chunkIndex,String vectorJson){}

    public QdrantMirrorService(JdbcTemplate jdbc,EmbeddingGateway embedding,
                               QdrantVectorStore store,ObjectMapper mapper) {
        this.jdbc=jdbc;this.embedding=embedding;this.store=store;this.mapper=mapper;
    }
    public boolean enabled(){return store.enabled() && embedding.configured();}

    public MirrorHealth healthForUser(long userId) {
        if(!embedding.configured())return new MirrorHealth("EMBEDDING_DISABLED",0,0,0,false);
        long[] counters=jdbc.queryForObject("""
            SELECT COUNT(*),
              COALESCE(SUM(IF(j.qdrant_synced_at IS NOT NULL AND
                 j.qdrant_synced_at >= DATE_SUB(NOW(6),INTERVAL 1 DAY),1,0)),0)
            FROM knowledge_embedding_job j
            JOIN knowledge_document k ON k.file_id=j.file_id AND k.generation=j.generation
            JOIN file_meta f ON f.file_id=j.file_id AND f.status=2
            WHERE j.user_id=? AND j.model_key=? AND j.status='READY'
              AND k.status IN ('READY','PARTIAL')
              AND EXISTS (SELECT 1 FROM user_file u WHERE u.file_id=j.file_id
                AND u.user_id=j.user_id AND u.status=0 AND u.is_dir=0)
            """,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},userId,embedding.modelKey());
        long total=counters==null?0:counters[0];
        long synced=counters==null?0:counters[1];
        if(!store.enabled())return new MirrorHealth("MYSQL_ONLY",total,0,0,false);
        if(total==0)return new MirrorHealth("NO_INDEX",0,0,0,false);
        if(synced<total)return new MirrorHealth("SYNCING",total,synced,total-synced,false);
        boolean reachable=store.collectionAvailable(embedding.modelKey(),embedding.dimensions());
        return new MirrorHealth(reachable?"READY":"DEGRADED",total,synced,0,reachable);
    }

    /** Require every current, opted-in READY job to be mirrored before fast-path querying. */
    public boolean readyForUser(long userId) {
        if(!enabled())return false;
        List<long[]> rows=jdbc.query("""
            SELECT COUNT(*),
              COALESCE(SUM(IF(j.qdrant_synced_at IS NULL OR
                       j.qdrant_synced_at < DATE_SUB(NOW(6),INTERVAL 1 DAY),1,0)),0)
            FROM knowledge_embedding_job j
            JOIN knowledge_document k ON k.file_id=j.file_id AND k.generation=j.generation
            JOIN file_meta f ON f.file_id=j.file_id AND f.status=2
            WHERE j.user_id=? AND j.model_key=? AND j.status='READY'
              AND k.status IN ('READY','PARTIAL')
              AND EXISTS (SELECT 1 FROM user_file u WHERE u.file_id=j.file_id
                AND u.user_id=j.user_id AND u.status=0 AND u.is_dir=0)
            """,(rs,n)->new long[]{rs.getLong(1),rs.getLong(2)},userId,embedding.modelKey());
        return !rows.isEmpty() && rows.getFirst()[0]>0 && rows.getFirst()[1]==0;
    }
    public void syncTick() {
        if(!enabled())return;
        try{syncOne();}
        catch(Exception e){log.warn("qdrant mirror sync deferred: {}",e.getClass().getSimpleName());}
    }
    public void syncOne() throws Exception {
        if(!enabled())return;
        List<Job> jobs=jdbc.query("""
            SELECT j.user_id,j.file_id,j.generation,k.chunk_count
            FROM knowledge_embedding_job j
            JOIN knowledge_document k ON k.file_id=j.file_id AND k.generation=j.generation
            JOIN file_meta f ON f.file_id=j.file_id AND f.status=2
            WHERE j.status='READY' AND j.model_key=? AND k.status IN ('READY','PARTIAL')
              AND (j.qdrant_synced_at IS NULL OR
                   j.qdrant_synced_at < DATE_SUB(NOW(6),INTERVAL 1 DAY))
              AND EXISTS (SELECT 1 FROM user_file u WHERE u.file_id=j.file_id
                AND u.user_id=j.user_id AND u.status=0 AND u.is_dir=0)
            ORDER BY j.qdrant_synced_at,j.updated_at LIMIT 1
            """,(rs,n)->new Job(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getInt(4)),
            embedding.modelKey());
        if(jobs.isEmpty())return;
        Job job=jobs.getFirst();
        if(job.chunks()<1 || job.chunks()>64)return;
        List<Row> vectors=jdbc.query("""
            SELECT chunk_index,vector_json FROM knowledge_vector
            WHERE file_id=? AND generation=? AND model_key=? AND dimensions=?
            ORDER BY chunk_index
            """,(rs,n)->new Row(rs.getInt(1),rs.getString(2)),job.fileId(),job.generation(),
            embedding.modelKey(),embedding.dimensions());
        if(vectors.size()!=job.chunks())return;
        List<QdrantVectorStore.Point> points=new ArrayList<>();
        int index=0;
        for(Row v:vectors) {
            if(v.chunkIndex()!=index++)return;
            points.add(new QdrantVectorStore.Point(job.userId(),job.fileId(),job.generation(),
                v.chunkIndex(),mapper.readValue(v.vectorJson(),double[].class)));
        }
        store.upsert(embedding.modelKey(),embedding.dimensions(),points);
        // Concurrent revoke/reindex makes this update a no-op; stale Qdrant point is pruned.
        jdbc.update("""
            UPDATE knowledge_embedding_job SET qdrant_synced_at=NOW(6)
            WHERE user_id=? AND file_id=? AND generation=? AND model_key=? AND status='READY'
              AND EXISTS (SELECT 1 FROM user_file u WHERE u.file_id=? AND u.user_id=?
                          AND u.status=0 AND u.is_dir=0)
            """,job.userId(),job.fileId(),job.generation(),embedding.modelKey(),
            job.fileId(),job.userId());
    }
    /** Eventually purge Qdrant vectors for revoked/deleted/stale documents. */
    public void pruneTick() {
        if(!enabled())return;
        try{pruneOne();}
        catch(Exception e) {
            scrollOffset=null;
            log.warn("qdrant mirror prune deferred: {}",e.getClass().getSimpleName());
        }
    }
    public void pruneOne() {
        if(!enabled())return;
        QdrantVectorStore.Page page=store.scroll(embedding.modelKey(),scrollOffset,40);
        List<String> orphanIds=new ArrayList<>();
        for(QdrantVectorStore.Hit hit:page.hits()) {
            if(!hit.id().equals(QdrantVectorStore.pointId(hit.userId(),hit.fileId(),
                    hit.generation(),hit.chunkIndex(),embedding.modelKey())))continue;
            Integer live=jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_embedding_job j
                JOIN knowledge_document k ON k.file_id=j.file_id AND k.generation=j.generation
                JOIN file_meta f ON f.file_id=j.file_id AND f.status=2
                JOIN knowledge_vector v ON v.file_id=j.file_id AND v.generation=j.generation
                    AND v.model_key=j.model_key AND v.chunk_index=?
                WHERE j.user_id=? AND j.file_id=? AND j.generation=?
                  AND j.model_key=? AND j.status='READY'
                  AND k.status IN ('READY','PARTIAL')
                  AND EXISTS (SELECT 1 FROM user_file u WHERE u.file_id=j.file_id
                    AND u.user_id=j.user_id AND u.status=0 AND u.is_dir=0)
                """,Integer.class,hit.chunkIndex(),hit.userId(),hit.fileId(),
                hit.generation(),embedding.modelKey());
            if(live==null || live==0)orphanIds.add(hit.id());
        }
        if(!orphanIds.isEmpty())store.delete(embedding.modelKey(),orphanIds);
        scrollOffset=page.offset()!=null && !page.offset().isNull()?page.offset():null;
    }
}
