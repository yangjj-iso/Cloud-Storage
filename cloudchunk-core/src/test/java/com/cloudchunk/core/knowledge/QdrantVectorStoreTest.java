package com.cloudchunk.core.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QdrantVectorStoreTest {
    private static final String KEY="a".repeat(64);
    private static final String FILE="b".repeat(32);

    @Test void createsMirrorsAndQueriesOnlyOwnMetadata() throws Exception {
        var mapper=new ObjectMapper();
        var created=new AtomicBoolean();
        var upsert=new AtomicReference<String>();
        var query=new AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            String root="/collections/cloudchunk_ai_"+KEY.substring(0,20);
            Object body;int status=200;
            if(path.equals(root) && method.equals("GET")) {
                if(!created.get()){status=404;body=Map.of("status","error");}
                else body=Map.of("status","ok","result",Map.of("config",
                    Map.of("params",Map.of("vectors",Map.of("size",8,"distance","Cosine")))));
            } else if(path.equals(root) && method.equals("PUT")) {
                created.set(true);body=Map.of("status","ok","result",true);
            } else if(path.endsWith("/points") && method.equals("PUT")) {
                upsert.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                body=Map.of("status","ok","result",true);
            } else if(path.endsWith("/points/query")) {
                query.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                body=Map.of("status","ok","result",Map.of("points",List.of(
                    Map.of("id",QdrantVectorStore.pointId(7,FILE,1,0,KEY),"score",0.92,
                        "payload",Map.of("user_id",7,"file_id",FILE,"generation",1,"chunk_index",0)),
                    Map.of("id",QdrantVectorStore.pointId(8,FILE,1,0,KEY),"score",0.95,
                        "payload",Map.of("user_id",8,"file_id",FILE,"generation",1,"chunk_index",0)))));
            } else body=Map.of("status","ok","result",true);
            byte[] data=mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(status,data.length);
            try(var out=exchange.getResponseBody()){out.write(data);}
        });
        server.start();
        try {
            Environment env=mock(Environment.class);
            when(env.getProperty("cloudchunk.ai.qdrant.enabled","false")).thenReturn("true");
            when(env.getProperty("cloudchunk.ai.qdrant.allow-http","false")).thenReturn("true");
            when(env.getProperty("cloudchunk.ai.qdrant.url",""))
                .thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
            when(env.getProperty("cloudchunk.ai.qdrant.api-key","")).thenReturn("");
            when(env.getProperty("cloudchunk.ai.qdrant.collection-prefix","cloudchunk_ai"))
                .thenReturn("cloudchunk_ai");
            var store=new QdrantVectorStore(mapper,env);
            double[] vec={1,0,0,0,0,0,0,0};
            store.upsert(KEY,8,List.of(new QdrantVectorStore.Point(7,FILE,1,0,vec)));
            assertTrue(created.get());
            var copied=mapper.readTree(upsert.get()).path("points").path(0);
            assertFalse(copied.path("payload").has("content"));
            assertEquals(7,copied.path("payload").path("user_id").asInt());
            assertEquals(1,store.query(KEY,7,vec,10).size());
            assertEquals(7,mapper.readTree(query.get()).path("filter").path("must")
                .path(0).path("match").path("value").asInt());
        } finally {server.stop(0);}
    }
}
