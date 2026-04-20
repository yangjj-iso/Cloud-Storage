package com.cloudchunk.storage.model;

/**
 * Range 下载请求；start/end 均为闭区间字节偏移，end = -1 表示到末尾。
 * totalSize > 0 时策略层直接使用，省去一次 stat 网络调用。
 */
public record GetRangeRequest(String bucket, String objectKey, long start, long end, long totalSize) {

    public static GetRangeRequest of(String bucket, String objectKey, long start, long end, long totalSize) {
        return new GetRangeRequest(bucket, objectKey, start, end, totalSize);
    }
}
