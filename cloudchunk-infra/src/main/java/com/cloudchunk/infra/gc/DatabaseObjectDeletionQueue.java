package com.cloudchunk.infra.gc;

import com.cloudchunk.contract.storage.ObjectDeletionQueue;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DatabaseObjectDeletionQueue implements ObjectDeletionQueue {

    private final ObjectGcTaskMapper mapper;

    public DatabaseObjectDeletionQueue(ObjectGcTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void enqueue(String fileId, String bucket, String objectKey, boolean markFileDeleted) {
        if (bucket == null || objectKey == null) return;
        ObjectGcTask task = new ObjectGcTask();
        task.setFileId(fileId);
        task.setBucket(bucket);
        task.setObjectKey(objectKey);
        task.setMarkFileDeleted(markFileDeleted);
        task.setStatus(0);
        task.setAttemptCount(0);
        task.setNextAttemptAt(LocalDateTime.now());
        try {
            mapper.insert(task);
        } catch (DuplicateKeyException ignored) {
            // Same physical deletion is already pending or completed.
        }
    }
}
