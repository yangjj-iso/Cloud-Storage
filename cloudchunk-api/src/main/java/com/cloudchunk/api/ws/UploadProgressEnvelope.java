package com.cloudchunk.api.ws;

/**
 * 上传进度跨节点扇出的信封。
 *
 * @param fileId  路由目标：只有持有该 fileId 连接的节点会真正推送
 * @param payload 已序列化的进度 JSON，接收端直接透传给浏览器，不做二次反序列化
 */
public record UploadProgressEnvelope(String fileId, String payload) {

    public UploadProgressEnvelope {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId is required");
        }
    }
}
