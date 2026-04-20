package com.cloudchunk.storage;

import com.cloudchunk.storage.model.ComposeRequest;
import com.cloudchunk.storage.model.ComposeResult;
import com.cloudchunk.storage.model.GetRangeRequest;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.storage.model.ObjectStat;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.storage.model.PutResult;
import com.cloudchunk.storage.model.RangeStream;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 统一存储抽象：对接 MinIO / 本地磁盘 / 阿里云 OSS。
 */
public interface StorageStrategy {

    /** 存储类型标识：minio / local / oss */
    String type();

    PutResult put(PutRequest request);

    InputStream get(GetRequest request);

    RangeStream getRange(GetRangeRequest request);

    ComposeResult compose(ComposeRequest request);

    String presignDownload(String bucket, String objectKey, Duration ttl);

    default String presignUpload(String bucket, String objectKey, Duration ttl) {
        throw new UnsupportedOperationException("presignUpload not supported by " + type());
    }

    /** 服务端对象拷贝（chunk dedup 用：相同内容分片零网络开销复制） */
    default void copy(String srcBucket, String srcKey, String dstBucket, String dstKey) {
        throw new UnsupportedOperationException("copy not supported by " + type());
    }

    void delete(String bucket, String objectKey);

    void deleteBatch(String bucket, List<String> keys);

    boolean exists(String bucket, String objectKey);

    ObjectStat stat(String bucket, String objectKey);

    List<ObjectStat> list(String bucket, String prefix, int maxKeys);
}
