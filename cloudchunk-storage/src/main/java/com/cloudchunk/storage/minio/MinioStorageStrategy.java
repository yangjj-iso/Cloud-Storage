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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MinioStorageStrategy implements StorageStrategy {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageStrategy.class);

    private final MinioClient client;
    private final StorageProperties properties;

    public MinioStorageStrategy(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
        ensureBucket(properties.getDefaultBucket());
    }

    @Override
    public String type() {
        return "minio";
    }

    private void ensureBucket(String bucket) {
        try {
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
            PutObjectArgs.Builder b = PutObjectArgs.builder()
                    .bucket(req.bucket())
                    .object(req.objectKey())
                    .stream(req.stream(), req.size(), -1)
                    .contentType(req.contentType() == null ? "application/octet-stream" : req.contentType());
            if (!req.userMetadata().isEmpty()) {
                b.userMetadata(req.userMetadata());
            }
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
            StatObjectResponse stat = client.statObject(StatObjectArgs.builder()
                    .bucket(req.bucket()).object(req.objectKey()).build());
            long total = stat.size();
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
            if (req.sourceKeys().size() <= batch) {
                return doCompose(req.bucket(), req.targetKey(), req.sourceKeys());
            }
            // 分批合并：先合成中间对象，再合并为最终对象
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

    private ComposeResult doCompose(String bucket, String target, List<String> sources) {
        try {
            List<ComposeSource> composeSources = new ArrayList<>(sources.size());
            for (String s : sources) {
                composeSources.add(ComposeSource.builder().bucket(bucket).object(s).build());
            }
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
            for (Result<io.minio.messages.DeleteError> r : results) {
                try {
                    io.minio.messages.DeleteError err = r.get();
                    if (err != null) {
                        log.warn("deleteBatch error: {} -> {}", err.objectName(), err.message());
                    }
                } catch (Exception ignored) {
                    // 单个失败不阻塞批量
                }
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
}
