package com.cloudchunk.storage.model;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

public record PutRequest(
        // 目标 bucket，由 UploadSession 或配置决定。
        String bucket,
        // 目标对象 key。分片上传时是临时 partKey，转码缩略图等场景也会复用该模型。
        String objectKey,
        // 待上传内容流。调用方通常传请求体流或文件流，存储层负责消费它。
        InputStream stream,
        // 已知内容长度。MinIO putObject 需要它来正确处理流式上传。
        long size,
        // 对象 Content-Type，前端下载或浏览器预览时会用到。
        String contentType,
        // 用户自定义元数据，最终写入对象存储的 user metadata。
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
