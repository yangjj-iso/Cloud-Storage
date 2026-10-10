package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.knowledge.KnowledgeModels.Evidence;
import com.cloudchunk.core.knowledge.KnowledgeModels.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small-corpus exact+semantic retrieval. The SQL prefilters by *live* user_file ACL,
 * generation and per-user opt-in; final candidates are reauthorized before returning.
 */
@Service
public class HybridSearchService {
    private static final int MAX_CANDIDATES=2000;
    private final KnowledgeService knowledge;
    private final JdbcTemplate jdbc;
    private final EmbeddingGateway provider;
    private final AiDailyCallBudget budget;
    private final ObjectMapper mapper;
    private final QdrantVectorStore qdrant;
    private final QdrantMirrorService mirror;
    private record VectorCandidate(Evidence evidence,double[] vector){}
    private record Scored(Evidence evidence,double score){}
    public HybridSearchService(KnowledgeService knowledge,JdbcTemplate jdbc,
                               EmbeddingGateway provider,ObjectMapper mapper,AiDailyCallBudget budget,
                               QdrantVectorStore qdrant,QdrantMirrorService mirror) {
        this.knowledge=knowledge;this.jdbc=jdbc;this.provider=provider;this.mapper=mapper;
        this.budget=budget;this.qdrant=qdrant;this.mirror=mirror;
    }
    public SearchResult search(long userId,String query,boolean consent) {
        if(query==null || query.strip().length()<2 || query.strip().length()>100)
            throw BizException.of(ErrorCode.INVALID_PARAMETER,"请输入 2–100 字符的查询");
        if(!provider.configured())return fallback(userId,query,"SEMANTIC_DISABLED");
        if(!consent)throw BizException.of(ErrorCode.INVALID_PARAMETER,"请确认将查询发送至配置的模型服务");
        SearchResult lexical=knowledge.search(userId,query);
        boolean remote=qdrant.enabled() && mirror.readyForUser(userId);
        // Fall back to the existing MySQL path until every authorized document is mirrored.
        List<VectorCandidate> candidates=remote ? List.of() : loadLocal(userId);
        if(!remote && candidates.isEmpty())
            return new SearchResult(lexical.evidence(),lexical.hasMore(),"SEMANTIC_NOT_INDEXED");
        double[] question;
        try{
            budget.reserve(userId,AiDailyCallBudget.Kind.EMBEDDING);
            EmbeddingGateway.Embedding embedded=provider.embedWithUsage(query.strip());
            budget.recordReported(userId,AiDailyCallBudget.Kind.EMBEDDING,embedded.usage());
            question=embedded.vector();
        }
        catch(BizException ex) {
            if(ex.getErrorCode()!=ErrorCode.AI_PROVIDER_UNAVAILABLE)throw ex;
            return fallback(userId,query,"KEYWORD_FALLBACK");
        }
        if(question.length!=provider.dimensions())return fallback(userId,query,"KEYWORD_FALLBACK");
        List<Scored> vectorMatches=new ArrayList<>();
        String mode=qdrant.enabled()?"QDRANT_WARMING":"HYBRID_RRF_LIMITED";
        if(remote) {
            try {
                for(QdrantVectorStore.Hit hit:qdrant.query(provider.modelKey(),userId,question,100)) {
                    // Qdrant's owner payload is NOT a security boundary.
                    List<Long> ids=jdbc.query("""
                        SELECT u.id FROM user_file u
                        JOIN knowledge_embedding_job j ON j.file_id=u.file_id
                          AND j.user_id=u.user_id AND j.generation=? AND j.model_key=?
                          AND j.status='READY'
                        JOIN knowledge_vector v ON v.file_id=j.file_id AND v.generation=j.generation
                          AND v.model_key=j.model_key AND v.chunk_index=?
                        WHERE u.user_id=? AND u.file_id=? AND u.status=0 AND u.is_dir=0
                        ORDER BY u.id LIMIT 1
                        """,(rs,n)->rs.getLong(1),hit.generation(),provider.modelKey(),
                        hit.chunkIndex(),userId,hit.fileId());
                    if(ids.isEmpty())continue;
                    Evidence evidence=knowledge.source(userId,ids.getFirst(),hit.generation(),hit.chunkIndex());
                    if(evidence!=null)vectorMatches.add(new Scored(evidence,hit.score()));
                }
                // Empty or lost Qdrant collection must not silently remove lexical/mirrored matches.
                if(vectorMatches.isEmpty())throw new QdrantVectorStore.Unavailable("no valid mirrored points");
                mode="QDRANT_HYBRID_RRF_LIMITED";
            } catch(QdrantVectorStore.Unavailable unavailable) {
                candidates=loadLocal(userId);
                mode="QDRANT_FALLBACK";
            }
        }
        if(!remote || "QDRANT_FALLBACK".equals(mode)) {
            for(VectorCandidate candidate:candidates.stream().limit(MAX_CANDIDATES).toList()) {
                double score=cosine(question,candidate.vector());
                if(score>=0.20)vectorMatches.add(new Scored(candidate.evidence(),score));
            }
        }
        vectorMatches.sort(Comparator.comparingDouble(Scored::score).reversed());
        Map<String,Double> rrf=new HashMap<>();
        Map<String,Evidence> originals=new HashMap<>();
        for(int i=0;i<lexical.evidence().size();i++) {
            Evidence e=lexical.evidence().get(i);String key=key(e);
            rrf.merge(key,1.2/(60+i+1),Double::sum);originals.putIfAbsent(key,e);
        }
        for(int i=0;i<vectorMatches.size();i++) {
            Evidence e=vectorMatches.get(i).evidence();String key=key(e);
            rrf.merge(key,1.0/(60+i+1),Double::sum);originals.putIfAbsent(key,e);
        }
        List<Evidence> ranked=new ArrayList<>();
        for(String key:rrf.keySet())ranked.add(originals.get(key));
        ranked.sort(Comparator.comparingDouble((Evidence e)->rrf.get(key(e))).reversed()
            .thenComparing(Evidence::userFileId).thenComparingInt(Evidence::chunkIndex));
        List<Evidence> allowed=new ArrayList<>();
        for(Evidence e:ranked) {
            if(allowed.size()==21)break;
            Evidence current=knowledge.source(userId,Long.parseLong(e.userFileId()),e.generation(),e.chunkIndex());
            if(current!=null && current.content().equals(e.content()))allowed.add(current);
        }
        boolean more=allowed.size()>20 || candidates.size()>MAX_CANDIDATES || lexical.hasMore();
        return new SearchResult(allowed.stream().limit(20).toList(),more,mode);
    }
    private List<VectorCandidate> loadLocal(long userId) {
        return jdbc.query("""
            SELECT u.id,u.file_name,k.generation,c.chunk_index,c.start_offset,c.end_offset,
              c.content,k.status,v.vector_json FROM knowledge_vector v
            JOIN knowledge_chunk c ON c.file_id=v.file_id AND c.generation=v.generation
              AND c.chunk_index=v.chunk_index
            JOIN knowledge_document k ON k.file_id=v.file_id AND k.generation=v.generation
            JOIN file_meta f ON f.file_id=v.file_id
            JOIN user_file u ON u.file_id=v.file_id AND u.user_id=? AND u.status=0 AND u.is_dir=0
            JOIN knowledge_embedding_job j ON j.file_id=v.file_id AND j.generation=v.generation
              AND j.model_key=v.model_key AND j.user_id=u.user_id AND j.status='READY'
            WHERE v.model_key=? AND v.dimensions=? AND f.status=2
              AND k.status IN ('READY','PARTIAL')
            ORDER BY u.updated_at DESC,u.id,c.chunk_index LIMIT 2001
            """,(rs,n)->{
                Evidence e=new Evidence(rs.getString("id"),rs.getString("file_name"),
                    rs.getLong("generation"),rs.getInt("chunk_index"),
                    rs.getInt("start_offset"),rs.getInt("end_offset"),
                    rs.getString("content"),rs.getString("status"));
                try {
                    return new VectorCandidate(e,mapper.readValue(rs.getString("vector_json"),double[].class));
                } catch(Exception ex){throw new IllegalStateException("invalid stored vector");}
            },userId,provider.modelKey(),provider.dimensions());
    }
    private SearchResult fallback(long userId,String query,String mode) {
        SearchResult plain=knowledge.search(userId,query);
        return new SearchResult(plain.evidence(),plain.hasMore(),mode);
    }
    private static String key(Evidence e) {
        return e.userFileId()+":"+e.generation()+":"+e.chunkIndex();
    }
    static double cosine(double[] a,double[] b) {
        if(a.length!=b.length || a.length==0)return -1;
        double dot=0,na=0,nb=0;
        for(int i=0;i<a.length;i++) {
            if(!Double.isFinite(a[i]) || !Double.isFinite(b[i]))return -1;
            dot+=a[i]*b[i];na+=a[i]*a[i];nb+=b[i]*b[i];
        }
        if(na<=0 || nb<=0 || !Double.isFinite(dot))return -1;
        return dot/Math.sqrt(na*nb);
    }
}
