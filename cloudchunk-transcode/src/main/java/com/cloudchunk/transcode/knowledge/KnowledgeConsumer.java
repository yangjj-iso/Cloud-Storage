package com.cloudchunk.transcode.knowledge;

import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.contract.event.FileAvailable;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.core.knowledge.KnowledgeIndexStore;
import com.cloudchunk.core.knowledge.KnowledgePolicy;
import com.cloudchunk.infra.inbox.ConsumerEventKey;
import com.cloudchunk.infra.inbox.InboxService;
import com.cloudchunk.infra.tracing.IntegrationEventTracing;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRequest;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.io.InputStream;

@Component
@ConditionalOnProperty(name="cloudchunk.runtime.worker-enabled",havingValue="true",matchIfMissing=true)
@RocketMQMessageListener(topic=FileAvailable.TOPIC,consumerGroup="CG-knowledge-v1",consumeThreadNumber=2)
public class KnowledgeConsumer implements RocketMQListener<FileAvailable> {
    private final FileMetaService files;
    private final KnowledgeIndexStore index;
    private final KnowledgeExtractor extractor;
    private final StorageStrategyFactory storage;
    private final InboxService inbox;
    private final IntegrationEventTracing tracing;
    public KnowledgeConsumer(FileMetaService files, KnowledgeIndexStore index, KnowledgeExtractor extractor,
                             StorageStrategyFactory storage, InboxService inbox, IntegrationEventTracing tracing) {
        this.files=files; this.index=index; this.extractor=extractor; this.storage=storage;
        this.inbox=inbox; this.tracing=tracing;
    }

    @Override public void onMessage(FileAvailable event) {
        String key = ConsumerEventKey.of("knowledge-v1", event.getEventId());
        try (var trace = tracing.startConsumer(event, "knowledge-extract")) {
            InboxService.AcquireResult acquired = inbox.acquire(key, "FileAvailable:knowledge-v1");
            if (acquired == InboxService.AcquireResult.ALREADY_DONE) return;
            if (acquired == InboxService.AcquireResult.BUSY) throw new IllegalStateException("knowledge event busy");
            try {
                process(event.getFileId());
                inbox.markDone(key);
            } catch (RuntimeException error) {
                trace.error(error);
                // Keep potentially sensitive parser messages out of generic Inbox persistence as well.
                inbox.markFailed(key, new IllegalStateException("knowledge extraction failed"));
                throw error;
            }
        }
    }

    private void process(String fileId) {
        FileMeta meta = files.findById(fileId).orElse(null);
        if (meta == null || meta.getStatus() != FileStatus.AVAILABLE || !KnowledgePolicy.supported(meta.getMimeType())) return;
        KnowledgeIndexStore.Claim claim = index.claim(fileId);
        if (claim == null) return;
        try {
            if (meta.getFileSize() == null || meta.getFileSize() > KnowledgePolicy.MAX_BYTES) {
                index.publish(claim, "", "UNSUPPORTED", "文件超过 20 MB 的本地解析上限");
                return;
            }
            try (InputStream in = storage.byType(meta.getStorageType()).get(new GetRequest(meta.getBucket(),meta.getObjectKey()))) {
                KnowledgeExtractor.Extraction result = extractor.extract(in, meta.getMimeType());
                index.publish(claim, result.text(), result.status(), result.reason());
            }
        } catch (Exception error) {
            index.failed(claim);
            throw new IllegalStateException("knowledge extraction failed");
        }
    }
}
