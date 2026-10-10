package com.cloudchunk.knowledge;

import com.cloudchunk.core.knowledge.QdrantVectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Qdrant 1.15 container contract; not a fake REST server.
 * Exercises create/validate, batch upsert, filtered top-k, scrolling, deletion and dimension guard.
 */
@Testcontainers(disabledWithoutDocker = true)
class QdrantRealContainerIT {
    private static final String MODEL="c".repeat(64);
    private static final String FILE_A="a".repeat(32);
    private static final String FILE_B="b".repeat(32);
    private static final String FILE_C="d".repeat(32);
    private static final String FILE_OTHER="f".repeat(32);

    @Container
    static final GenericContainer<?> QDRANT =
        new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.15.0"))
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/healthz").forPort(6333).forStatusCode(200));

    private QdrantVectorStore client() {
        var env=new MockEnvironment()
            .withProperty("cloudchunk.ai.qdrant.enabled","true")
            .withProperty("cloudchunk.ai.qdrant.allow-http","true")
            .withProperty("cloudchunk.ai.qdrant.url",
                "http://"+QDRANT.getHost()+":"+QDRANT.getMappedPort(6333))
            .withProperty("cloudchunk.ai.qdrant.collection-prefix","cc_it");
        return new QdrantVectorStore(new ObjectMapper(),env);
    }

    @Test void realEngineRespectsUserFilterRankingAndPhysicalDeletion() {
        var store=client();
        assertTrue(store.enabled());
        assertFalse(store.collectionAvailable(MODEL,8));

        double[] x={1,0,0,0,0,0,0,0};
        double[] y={0,1,0,0,0,0,0,0};
        double[] z={0,0,1,0,0,0,0,0};
        // Another user's duplicate-content point must never enter this user's search.
        store.upsert(MODEL,8,List.of(
            new QdrantVectorStore.Point(11,FILE_A,1,0,x),
            new QdrantVectorStore.Point(11,FILE_B,1,0,y),
            new QdrantVectorStore.Point(11,FILE_C,1,0,z),
            new QdrantVectorStore.Point(22,FILE_OTHER,1,0,x)
        ));
        assertTrue(store.collectionAvailable(MODEL,8));
        assertFalse(store.collectionAvailable(MODEL,16));
        assertThrows(QdrantVectorStore.Unavailable.class,
            () -> store.ensureCollection(MODEL,16));

        // Synthetic retrieval-quality fixture: Recall@1 and MRR must be exactly 1.
        Map<String,double[]> probes=Map.of(FILE_A,x,FILE_B,y,FILE_C,z);
        double mrr=0;
        int hits=0;
        for(var item:probes.entrySet()) {
            var ranked=store.query(MODEL,11,item.getValue(),4);
            assertFalse(ranked.isEmpty());
            assertTrue(ranked.stream().allMatch(hit -> hit.userId()==11));
            for(int rank=0;rank<ranked.size();rank++) {
                if(ranked.get(rank).fileId().equals(item.getKey())) {
                    mrr+=1.0/(rank+1);
                    if(rank==0)hits++;
                    break;
                }
            }
        }
        assertEquals(1.0,hits/3.0,0.00001,"synthetic Recall@1");
        assertEquals(1.0,mrr/3,0.00001,"synthetic MRR");
        assertEquals(1,store.query(MODEL,22,x,4).size());
        assertEquals(FILE_OTHER,store.query(MODEL,22,x,4).getFirst().fileId());

        var page=store.scroll(MODEL,null,10);
        assertEquals(4,page.hits().size());
        store.delete(MODEL,List.of(QdrantVectorStore.pointId(11,FILE_A,1,0,MODEL)));
        assertTrue(store.query(MODEL,11,x,3).stream().noneMatch(hit -> hit.fileId().equals(FILE_A)));
        assertEquals(FILE_OTHER,store.query(MODEL,22,x,2).getFirst().fileId());
    }
}
