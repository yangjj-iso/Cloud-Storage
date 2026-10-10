package com.cloudchunk.core.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** API role reads Qdrant; only the Worker role performs asynchronous mirror maintenance. */
@Component
@ConditionalOnProperty(name="cloudchunk.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
public class QdrantMirrorWorker {
    private final QdrantMirrorService mirror;
    public QdrantMirrorWorker(QdrantMirrorService mirror){this.mirror=mirror;}

    @Scheduled(fixedDelayString="${cloudchunk.ai.qdrant.sync-interval-ms:15000}",initialDelay=20000)
    public void sync(){mirror.syncTick();}

    @Scheduled(fixedDelayString="${cloudchunk.ai.qdrant.prune-interval-ms:90000}",initialDelay=45000)
    public void prune(){mirror.pruneTick();}
}
