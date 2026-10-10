package com.cloudchunk.core.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Worker-only; upload NEVER silently calls an embedding provider. */
@Component
@ConditionalOnProperty(name="cloudchunk.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
public class SemanticIndexWorker {
    private final SemanticIndexService service;
    public SemanticIndexWorker(SemanticIndexService service){this.service=service;}
    @Scheduled(fixedDelayString="${cloudchunk.ai.embedding.job-interval-ms:10000}",initialDelay=12000)
    public void tick(){service.workOne();}
}
