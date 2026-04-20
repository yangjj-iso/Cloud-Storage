package com.cloudchunk.storage.model;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

public record ObjectStat(
        String bucket,
        String objectKey,
        long size,
        String etag,
        Instant lastModified,
        String contentType,
        Map<String, String> userMetadata
) {
    public ObjectStat {
        if (userMetadata == null) {
            userMetadata = Collections.emptyMap();
        }
    }
}
