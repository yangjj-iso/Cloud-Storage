package com.cloudchunk.contract.storage;

/** Durable queue used instead of deleting storage objects inside DB transactions. */
public interface ObjectDeletionQueue {

    void enqueue(String fileId, String bucket, String objectKey, boolean markFileDeleted);
}
