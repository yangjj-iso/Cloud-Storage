package com.cloudchunk.core.file.task;

import com.cloudchunk.common.util.IdUtils;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.infra.gc.ObjectGcTask;
import com.cloudchunk.infra.gc.ObjectGcTaskMapper;
import com.cloudchunk.storage.StorageStrategyFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(name = "cloudchunk.runtime.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ObjectGcWorker {

    private static final Logger log = LoggerFactory.getLogger(ObjectGcWorker.class);
    private final String workerId = "gc-" + IdUtils.uuid32().substring(0, 8);
    private final ObjectGcTaskMapper mapper;
    private final StorageStrategyFactory storageFactory;
    private final FileMetaService fileMetaService;
    private final Counter completed;
    private final Counter failed;

    public ObjectGcWorker(ObjectGcTaskMapper mapper,
                          StorageStrategyFactory storageFactory,
                          FileMetaService fileMetaService,
                          MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.storageFactory = storageFactory;
        this.fileMetaService = fileMetaService;
        this.completed = Counter.builder("cloudchunk.object_gc.completed")
                .description("Storage objects durably deleted by the GC worker")
                .register(meterRegistry);
        this.failed = Counter.builder("cloudchunk.object_gc.failures")
                .description("Object GC attempts scheduled for retry")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${cloudchunk.gc.poll-interval-ms:5000}", initialDelay = 10000)
    public void collect() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusHours(1);
        List<ObjectGcTask> tasks = mapper.findReady(now, staleBefore, 100);
        for (ObjectGcTask task : tasks) {
            if (mapper.claim(task.getId(), workerId, now, staleBefore) <= 0) continue;
            try {
                storageFactory.current().delete(task.getBucket(), task.getObjectKey());
                if (Boolean.TRUE.equals(task.getMarkFileDeleted()) && task.getFileId() != null) {
                    fileMetaService.markDeleted(task.getFileId());
                }
                mapper.markDone(task.getId(), workerId, LocalDateTime.now());
                completed.increment();
            } catch (Exception e) {
                failed.increment();
                int attempt = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
                long delay = Math.min(3600, 10L << Math.min(attempt, 8));
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                mapper.markRetry(task.getId(), workerId, LocalDateTime.now().plusSeconds(delay),
                        error.substring(0, Math.min(error.length(), 1024)));
                log.warn("object GC failed: fileId={}, key={}, attempt={}",
                        task.getFileId(), task.getObjectKey(), attempt, e);
            }
        }
    }
}
