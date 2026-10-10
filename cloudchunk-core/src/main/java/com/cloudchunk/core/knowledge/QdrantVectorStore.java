package com.cloudchunk.core.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional Qdrant REST adapter. Only numeric vectors and bounded identifiers leave the service;
 * never store document text or grant Qdrant authority to decide file permissions.
 */
@Service
public class QdrantVectorStore {
    public record Point(long userId,String fileId,long generation,int chunkIndex,double[] vector) {}
    public record Hit(String id,long userId,String fileId,long generation,int chunkIndex,double score) {}
    public record Page(List<Hit> hits, JsonNode offset) {}
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message){super(message);}
        public Unavailable(String message,Throwable cause){super(message,cause);}
    }

    private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final String apiKey;
    private final String prefix;
    private final boolean enabled;

    public QdrantVectorStore(ObjectMapper mapper,Environment env) {
        this.mapper=mapper;
        String url=env.getProperty("cloudchunk.ai.qdrant.url","").strip();
        this.apiKey=env.getProperty("cloudchunk.ai.qdrant.api-key","");
        this.prefix=env.getProperty("cloudchunk.ai.qdrant.collection-prefix","cloudchunk_ai");
        boolean configured=Boolean.parseBoolean(env.getProperty("cloudchunk.ai.qdrant.enabled","false"));
        boolean allowHttp=Boolean.parseBoolean(env.getProperty("cloudchunk.ai.qdrant.allow-http","false"));
        URI parsed=null;
        try {
            if(!url.isBlank()) {
                URI candidate=URI.create(url);
                boolean scheme="https".equalsIgnoreCase(candidate.getScheme()) ||
                    (allowHttp && "http".equalsIgnoreCase(candidate.getScheme()));
                if(!scheme || candidate.getHost()==null || candidate.getRawUserInfo()!=null ||
                   candidate.getRawQuery()!=null || candidate.getRawFragment()!=null ||
                   (candidate.getRawPath()!=null && !candidate.getRawPath().matches("/?")))
                    throw new IllegalArgumentException("invalid vector endpoint");
                parsed=candidate;
            }
        } catch(IllegalArgumentException ignored) { parsed=null; }
        this.base=parsed;
        this.enabled=configured && parsed!=null && prefix.matches("[a-zA-Z][a-zA-Z0-9_-]{0,36}");
    }
    public boolean enabled(){return enabled;}
    public String collection(String modelKey) {
        if(modelKey==null || !modelKey.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid embedding model key");
        return prefix+"_"+modelKey.substring(0,20);
    }
    public static String pointId(long userId,String fileId,long generation,int chunkIndex,String modelKey) {
        String basis=userId+"|"+fileId+"|"+generation+"|"+chunkIndex+"|"+modelKey;
        return UUID.nameUUIDFromBytes(basis.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private String url(String path){return base.toString().replaceAll("/$","")+path;}

    private JsonNode call(String method,String path,Object body) {
        if(!enabled)throw new Unavailable("Qdrant disabled");
        try {
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(8)).header("Accept","application/json");
            if(!apiKey.isBlank())builder.header("api-key",apiKey);
            if(body!=null) {
                builder.header("Content-Type","application/json");
                builder.method(method,HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8));
            } else builder.method(method,HttpRequest.BodyPublishers.noBody());
            HttpResponse<InputStream> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=response.body()) {
                byte[] raw=in.readNBytes(262145);
                if(raw.length>262144)throw new Unavailable("Qdrant response too large");
                if(response.statusCode()==404)return null;
                if(response.statusCode()<200 || response.statusCode()>=300)
                    throw new Unavailable("Qdrant HTTP "+response.statusCode());
                JsonNode json=mapper.readTree(raw);
                if(!"ok".equalsIgnoreCase(json.path("status").asText()))
                    throw new Unavailable("Qdrant operation failed");
                return json;
            }
        }catch(Unavailable e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new Unavailable("Qdrant interrupted",e);}
        catch(Exception e){throw new Unavailable("Qdrant unavailable",e);}
    }
    /** Create if missing, and verify existing collection size and cosine distance before writing. */
    public void ensureCollection(String modelKey,int dimensions) {
        if(dimensions<8 || dimensions>2048)throw new Unavailable("invalid vector dimensions");
        String path="/collections/"+collection(modelKey);
        JsonNode current=call("GET",path,null);
        if(current==null) {
            call("PUT",path,Map.of("vectors",Map.of("size",dimensions,"distance","Cosine")));
            current=call("GET",path,null);
        }
        if(current==null)throw new Unavailable("Qdrant collection not found");
        JsonNode vectors=current.path("result").path("config").path("params").path("vectors");
        if(vectors.path("size").asInt(-1)!=dimensions ||
           !"Cosine".equalsIgnoreCase(vectors.path("distance").asText()))
            throw new Unavailable("Qdrant collection dimension/distance mismatch");
    }
    public void upsert(String modelKey,int dimensions,List<Point> points) {
        if(points.isEmpty())return;
        if(points.size()>64)throw new IllegalArgumentException("too many vectors");
        ensureCollection(modelKey,dimensions);
        var payload=new ArrayList<Map<String,Object>>();
        for(Point p:points){
            if(p.vector()==null || p.vector().length!=dimensions)throw new IllegalArgumentException("bad vector");
            double norm=0;
            for(double n:p.vector()) {if(!Double.isFinite(n))throw new IllegalArgumentException("nonfinite vector");norm+=n*n;}
            if(norm<=0)throw new IllegalArgumentException("zero vector");
            payload.add(Map.of(
                "id",pointId(p.userId(),p.fileId(),p.generation(),p.chunkIndex(),modelKey),
                "vector",p.vector(),
                "payload",Map.of("user_id",p.userId(),"file_id",p.fileId(),
                    "generation",p.generation(),"chunk_index",p.chunkIndex())
            ));
        }
        call("PUT","/collections/"+collection(modelKey)+"/points?wait=true",Map.of("points",payload));
    }
    public List<Hit> query(String modelKey,long userId,double[] vector,int limit) {
        if(vector==null || vector.length==0 || limit<1 || limit>100)throw new IllegalArgumentException("bad Qdrant query");
        JsonNode response=call("POST","/collections/"+collection(modelKey)+"/points/query",
            Map.of("query",vector,"limit",limit,"score_threshold",0.2,
                "with_payload",true,"with_vector",false,
                "filter",Map.of("must",List.of(Map.of("key","user_id",
                    "match",Map.of("value",userId))))));
        if(response==null)throw new Unavailable("Qdrant collection missing");
        JsonNode rows=response.path("result").path("points");
        if(!rows.isArray())throw new Unavailable("invalid Qdrant result");
        List<Hit> hits=new ArrayList<>();
        for(JsonNode row:rows) {
            JsonNode payload=row.path("payload");
            String fileId=payload.path("file_id").asText("");
            String id=row.path("id").asText("");
            long fromUser=payload.path("user_id").asLong(-1);
            long generation=payload.path("generation").asLong(-1);
            int chunk=payload.path("chunk_index").asInt(-1);
            double score=row.path("score").asDouble(Double.NaN);
            if(fromUser!=userId || !fileId.matches("[a-zA-Z0-9_-]{1,64}") ||
               !id.matches("[a-f0-9-]{36}") || generation<1 || chunk<0 ||
               !Double.isFinite(score) || score<0.2)continue;
            if(!id.equals(pointId(userId,fileId,generation,chunk,modelKey)))continue;
            hits.add(new Hit(id,userId,fileId,generation,chunk,score));
        }
        return hits;
    }
    public Page scroll(String modelKey,JsonNode offset,int limit) {
        if(limit<1 || limit>100)throw new IllegalArgumentException("invalid page size");
        Map<String,Object> body=new java.util.LinkedHashMap<>();
        body.put("limit",limit); body.put("with_payload",true);body.put("with_vector",false);
        if(offset!=null && !offset.isNull())body.put("offset",offset);
        JsonNode response=call("POST","/collections/"+collection(modelKey)+"/points/scroll",body);
        if(response==null)throw new Unavailable("Qdrant collection missing");
        JsonNode rows=response.path("result").path("points");
        if(!rows.isArray())throw new Unavailable("invalid Qdrant page");
        List<Hit> hits=new ArrayList<>();
        for(JsonNode row:rows) {
            JsonNode payload=row.path("payload");
            String fileId=payload.path("file_id").asText("");
            String id=row.path("id").asText("");
            long user=payload.path("user_id").asLong(-1);
            long generation=payload.path("generation").asLong(-1);
            int chunk=payload.path("chunk_index").asInt(-1);
            if(user<=0 || !fileId.matches("[a-zA-Z0-9_-]{1,64}") ||
               generation<1 || chunk<0 || !id.matches("[a-f0-9-]{36}"))continue;
            hits.add(new Hit(id,user,fileId,generation,chunk,0));
        }
        return new Page(hits,response.path("result").path("next_page_offset"));
    }
    public void delete(String modelKey,List<String> ids) {
        if(ids.isEmpty())return;
        if(ids.size()>100 || ids.stream().anyMatch(x->!x.matches("[a-f0-9-]{36}")))
            throw new IllegalArgumentException("invalid point ids");
        call("POST","/collections/"+collection(modelKey)+"/points/delete?wait=true",Map.of("points",ids));
    }
}
