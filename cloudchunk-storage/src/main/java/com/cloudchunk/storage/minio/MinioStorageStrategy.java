package com.cloudchunk.storage.minio;

import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.exception.StorageException;
import com.cloudchunk.storage.StorageProperties;
import com.cloudchunk.storage.StorageStrategy;
import com.cloudchunk.storage.model.ComposeRequest;
import com.cloudchunk.storage.model.ComposeResult;
import com.cloudchunk.storage.model.GetRangeRequest;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.storage.model.ObjectStat;
import com.cloudchunk.storage.model.PutRequest;
import com.cloudchunk.storage.model.PutResult;
import com.cloudchunk.storage.model.RangeStream;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MinioStorageStrategy implements StorageStrategy {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageStrategy.class);
    private static final long MIN_MULTIPART_COMPOSE_SOURCE_SIZE = 5L * 1024 * 1024;

    private final MinioClient client;
    private final StorageProperties properties;

    public MinioStorageStrategy(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
        // 存储策略创建时先确保默认 bucket 存在。
        // 上传请求本身不负责建桶，避免每个分片上传都做一次 bucket 检查。
        ensureBucket(properties.getDefaultBucket());
    }

    @Override
    public String type() {
        return "minio";
    }

    private void ensureBucket(String bucket) {
        try {
            // MinIO 的 object 必须落在 bucket 下。
            // 开发环境自动建桶可以减少首次启动后的手工准备步骤。
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("created minio bucket: {}", bucket);
            }
        } catch (Exception e) {
            throw new StorageException("ensureBucket failed: " + bucket, e);
        }
    }

    @Override
    public PutResult put(PutRequest req) {
        try {
            // PutRequest 是项目内部的存储写入模型；
            // 这里把它转换成 MinIO Java SDK 的 PutObjectArgs。
            PutObjectArgs.Builder b = PutObjectArgs.builder()
                    .bucket(req.bucket())
                    .object(req.objectKey())
                    .stream(req.stream(), req.size(), -1)
                    .contentType(req.contentType() == null ? "application/octet-stream" : req.contentType());
            if (!req.userMetadata().isEmpty()) {
                b.userMetadata(req.userMetadata());
            }
            // 真正的网络写入发生在 putObject。
            // SDK 会持续读取 req.stream()，直到写完整个对象或抛异常。
            ObjectWriteResponse resp = client.putObject(b.build());
            return new PutResult(req.objectKey(), resp.etag(), req.size());
        } catch (Exception e) {
            throw new StorageException("put failed: " + req.objectKey(), e);
        }
    }

    @Override
    public InputStream get(GetRequest req) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(req.bucket()).object(req.objectKey()).build());
        } catch (Exception e) {
            throw new StorageException("get failed: " + req.objectKey(), e);
        }
    }

    @Override
    public RangeStream getRange(GetRangeRequest req) {
        try {
            long total = req.totalSize() > 0 ? req.totalSize()
                    : client.statObject(StatObjectArgs.builder()
                            .bucket(req.bucket()).object(req.objectKey()).build()).size();
            long end = req.end() < 0 ? total - 1 : Math.min(req.end(), total - 1);
            long length = end - req.start() + 1;
            InputStream in = client.getObject(GetObjectArgs.builder()
                    .bucket(req.bucket())
                    .object(req.objectKey())
                    .offset(req.start())
                    .length(length)
                    .build());
            return new RangeStream(in, req.start(), end, total);
        } catch (Exception e) {
            throw new StorageException("getRange failed: " + req.objectKey(), e);
        }
    }

    @Override
    public ComposeResult compose(ComposeRequest req) {
        int batch = Math.max(2, properties.getComposeBatchSize());
        try {
            if (req.sourceKeys().isEmpty()) {
                throw new StorageException(ErrorCode.COMPOSE_FAILED, "compose source is empty: " + req.targetKey());
            }
            if (req.sourceKeys().size() == 1) {
                return copySingleSource(req.bucket(), req.sourceKeys().get(0), req.targetKey());
            }
            if (needsStreamCompose(req.bucket(), req.sourceKeys())) {
                log.info("compose fallback to stream merge: target={}, sources={}",
                        req.targetKey(), req.sourceKeys().size());
                return streamCompose(req.bucket(), req.targetKey(), req.sourceKeys());
            }
            if (req.sourceKeys().size() <= batch) {
                return doCompose(req.bucket(), req.targetKey(), req.sourceKeys());
            }
            // 分批合并：先合成中间对象，再合并为最终对象
            // MinIO composeObject 对 source 数量有约束时，分批可以避免一次合并源过多。
            List<String> intermediateKeys = new ArrayList<>();
            int idx = 0;
            List<List<String>> batches = new ArrayList<>();
            List<String> cur = new ArrayList<>();
            for (String k : req.sourceKeys()) {
                cur.add(k);
                if (cur.size() >= batch) {
                    batches.add(cur);
                    cur = new ArrayList<>();
                }
            }
            if (!cur.isEmpty()) batches.add(cur);
            for (List<String> b : batches) {
                String midKey = req.targetKey() + ".__tmp__." + String.format("%04d", idx++);
                doCompose(req.bucket(), midKey, b);
                intermediateKeys.add(midKey);
            }
            ComposeResult result = doCompose(req.bucket(), req.targetKey(), intermediateKeys);
            // 清理中间对象
            deleteBatch(req.bucket(), intermediateKeys);
            return result;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException(ErrorCode.COMPOSE_FAILED, "compose failed: " + req.targetKey(), e);
        }
    }

    private boolean needsStreamCompose(String bucket, List<String> sources) {
        try {
            for (int i = 0; i < sources.size() - 1; i++) {
                StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                        .bucket(bucket)
                        .object(sources.get(i))
                        .build());
                if (stat.size() < MIN_MULTIPART_COMPOSE_SOURCE_SIZE) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new StorageException(ErrorCode.COMPOSE_FAILED, "stat compose source failed", e);
        }
    }

    private ComposeResult copySingleSource(String bucket, String sourceKey, String targetKey) {
        try {
            client.copyObject(CopyObjectArgs.builder()
                    .bucket(bucket)
                    .object(targetKey)
                    .source(CopySource.builder()
                            .bucket(bucket)
                            .object(sourceKey)
                            .build())
                    .build());
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(targetKey).build());
            return new ComposeResult(targetKey, stat.etag(), stat.size());
        } catch (Exception e) {
            throw new StorageException(ErrorCode.COMPOSE_FAILED, "copy single source failed: " + targetKey, e);
        }
    }

    private ComposeResult streamCompose(String bucket, String targetKey, List<String> sources) {
        try {
            long total = 0;
            for (String source : sources) {
                StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                        .bucket(bucket)
                        .object(source)
                        .build());
                total += stat.size();
            }

            try (InputStream in = new MinioConcatInputStream(bucket, sources)) {
                ObjectWriteResponse resp = client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(targetKey)
                        .stream(in, total, -1)
                        .contentType("application/octet-stream")
                        .build());
                StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                        .bucket(bucket).object(targetKey).build());
                return new ComposeResult(targetKey, resp.etag(), stat.size());
            }
        } catch (Exception e) {
            throw new StorageException(ErrorCode.COMPOSE_FAILED, "stream compose failed: " + targetKey, e);
        }
    }

    private ComposeResult doCompose(String bucket, String target, List<String> sources) {
        try {
            List<ComposeSource> composeSources = new ArrayList<>(sources.size());
            for (String s : sources) {
                composeSources.add(ComposeSource.builder().bucket(bucket).object(s).build());
            }
            // 服务端合并：数据在 MinIO 内部组合，Java 进程不下载分片、不本地拼接。
            ObjectWriteResponse resp = client.composeObject(ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(target)
                    .sources(composeSources)
                    .build());
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(target).build());
            return new ComposeResult(target, resp.etag(), stat.size());
        } catch (Exception e) {
            throw new StorageException(ErrorCode.COMPOSE_FAILED, "compose failed: " + target, e);
        }
    }

    @Override
    public String presignDownload(String bucket, String objectKey, Duration ttl) {
        try {
            int seconds = (int) Math.min(ttl.toSeconds(), TimeUnit.DAYS.toSeconds(7));
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(seconds)
                    .build());
        } catch (Exception e) {
            throw new StorageException("presignDownload failed: " + objectKey, e);
        }
    }

    @Override
    public String presignUpload(String bucket, String objectKey, Duration ttl) {
        try {
            int seconds = (int) Math.min(ttl.toSeconds(), TimeUnit.DAYS.toSeconds(7));
            // 生成临时 PUT URL。前端拿到后可直接上传到 MinIO 的 objectKey。
            // 有效期受 MinIO 限制，这里最大不超过 7 天。
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(seconds)
                    .build());
        } catch (Exception e) {
            throw new StorageException("presignUpload failed: " + objectKey, e);
        }
    }

    @Override
    public void copy(String srcBucket, String srcKey, String dstBucket, String dstKey) {
        try {
            client.copyObject(CopyObjectArgs.builder()
                    .bucket(dstBucket)
                    .object(dstKey)
                    .source(CopySource.builder()
                            .bucket(srcBucket)
                            .object(srcKey)
                            .build())
                    .build());
        } catch (Exception e) {
            throw new StorageException("copy failed: " + srcKey + " → " + dstKey, e);
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new StorageException("delete failed: " + objectKey, e);
        }
    }

    @Override
    public void deleteBatch(String bucket, List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        try {
            List<DeleteObject> objs = new ArrayList<>(keys.size());
            for (String k : keys) objs.add(new DeleteObject(k));
            Iterable<Result<io.minio.messages.DeleteError>> results = client.removeObjects(
                    RemoveObjectsArgs.builder().bucket(bucket).objects(objs).build());
            // 强制遍历以触发删除
            int failures = 0;
            String firstFailure = null;
            for (Result<io.minio.messages.DeleteError> r : results) {
                try {
                    io.minio.messages.DeleteError err = r.get();
                    if (err != null) {
                        log.warn("deleteBatch error: {} -> {}", err.objectName(), err.message());
                        failures++;
                        if (firstFailure == null) {
                            firstFailure = err.objectName() + ": " + err.message();
                        }
                    }
                } catch (Exception e) {
                    failures++;
                    if (firstFailure == null) {
                        firstFailure = e.getMessage();
                    }
                }
            }
            if (failures > 0) {
                throw new StorageException("deleteBatch failed: " + failures + " objects"
                        + (firstFailure == null ? "" : ", first=" + firstFailure));
            }
        } catch (Exception e) {
            throw new StorageException("deleteBatch failed", e);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (io.minio.errors.ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw new StorageException("exists check failed: " + objectKey, e);
        }
    }

    @Override
    public ObjectStat stat(String bucket, String objectKey) {
        try {
            // confirmChunk 和进度重建依赖 statObject 判断对象是否真实存在。
            StatObjectResponse s = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            return new ObjectStat(
                    bucket, objectKey, s.size(), s.etag(),
                    s.lastModified().toInstant(),
                    s.contentType(),
                    s.userMetadata());
        } catch (Exception e) {
            throw new StorageException("stat failed: " + objectKey, e);
        }
    }

    @Override
    public List<ObjectStat> list(String bucket, String prefix, int maxKeys) {
        List<ObjectStat> out = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .maxKeys(maxKeys)
                    .recursive(true)
                    .build());
            for (Result<Item> r : results) {
                Item it = r.get();
                out.add(new ObjectStat(
                        bucket, it.objectName(), it.size(), it.etag(),
                        it.lastModified() == null ? null : it.lastModified().toInstant(),
                        null, null));
                if (out.size() >= maxKeys) break;
            }
            return out;
        } catch (Exception e) {
            throw new StorageException("list failed: " + prefix, e);
        }
    }

    private class MinioConcatInputStream extends InputStream {
        private final String bucket;
        private final List<String> sources;
        private int index;
        private InputStream current;

        MinioConcatInputStream(String bucket, List<String> sources) {
            this.bucket = bucket;
            this.sources = sources;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (true) {
                InputStream in = current();
                if (in == null) return -1;
                int n = in.read(b, off, len);
                if (n >= 0) return n;
                closeCurrent();
            }
        }

        @Override
        public void close() throws IOException {
            closeCurrent();
        }

        private InputStream current() throws IOException {
            if (current != null) return current;
            if (index >= sources.size()) return null;
            String source = sources.get(index++);
            try {
                current = client.getObject(GetObjectArgs.builder()
                        .bucket(bucket)
                        .object(source)
                        .build());
                return current;
            } catch (Exception e) {
                throw new IOException("open compose source failed: " + source, e);
            }
        }

        private void closeCurrent() throws IOException {
            if (current != null) {
                try {
                    current.close();
                } finally {
                    current = null;
                }
            }
        }
    }
}
