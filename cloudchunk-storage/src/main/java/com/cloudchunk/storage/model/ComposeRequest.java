package com.cloudchunk.storage.model;

import java.util.List;

/**
 * 服务端合并请求。sourceKeys 必须有序。
 */
public record ComposeRequest(String bucket, String targetKey, List<String> sourceKeys) {}
