package com.cloudchunk.storage.model;

/**
 * Range 下载请求；start/end 均为闭区间字节偏移，end = -1 表示到末尾。
 */
public record GetRangeRequest(String bucket, String objectKey, long start, long end) {}
