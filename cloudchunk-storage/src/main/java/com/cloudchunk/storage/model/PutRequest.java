package com.cloudchunk.storage.model;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

public record PutRequest(
        String bucket,
        String objectKey,
        InputStream stream,
        long size,
        String contentType,
        Map<String, String> userMetadata
) {
    public PutRequest {
        if (userMetadata == null) {
            userMetadata = Collections.emptyMap();
        }
    }

    public static PutRequest of(String bucket, String key, InputStream in, long size, String contentType) {
        return new PutRequest(bucket, key, in, size, contentType, Collections.emptyMap());
    }
}
