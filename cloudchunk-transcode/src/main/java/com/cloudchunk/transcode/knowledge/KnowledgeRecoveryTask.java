package com.cloudchunk.transcode.knowledge;

import com.cloudchunk.core.knowledge.KnowledgeIndexStore;
import com.cloudchunk.core.knowledge.KnowledgeService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduler performs bounded DB/outbox work only; it never parses a file on the shared scheduling thread. */
@Component
@ConditionalOnProperty(name="cloudchunk.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
public class KnowledgeRecoveryTask {
    private final KnowledgeService service;
    private final KnowledgeIndexStore store;
    public KnowledgeRecoveryTask(KnowledgeService service, KnowledgeIndexStore store) {
        this.service=service; this.store=store;
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void recover() {
        service.recoverPending();
        store.cleanDeleted();
    }
}
