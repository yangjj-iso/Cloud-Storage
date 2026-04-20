package com.cloudchunk.storage.local;

import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.exception.StorageException;
import com.cloudchunk.common.util.Md5Utils;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于本地文件系统的存储策略，主要用于开发调试 / 单机部署。
 * Compose 使用文件流追加实现；Range 通过 RandomAccessFile 定位。
 */
public class LocalStorageStrategy implements StorageStrategy {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageStrategy.class);

    private final Path root;
    private final StorageProperties properties;

    public LocalStorageStrategy(StorageProperties properties) {
        this.properties = properties;
        this.root = Path.of(properties.getLocal().getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            log.info("local storage root: {}", this.root);
        } catch (IOException e) {
            throw new StorageException("init local root failed: " + this.root, e);
        }
    }

    @Override
    public String type() {
        return "local";
    }

    private Path resolve(String bucket, String objectKey) {
        Path p = root.resolve(bucket).resolve(objectKey).normalize();
        if (!p.startsWith(root)) {
            throw new StorageException("path escapes root: " + objectKey);
        }
        return p;
    }

    @Override
    public PutResult put(PutRequest req) {
        Path target = resolve(req.bucket(), req.objectKey());
        try {
            Files.createDirectories(target.getParent());
            long size = Files.copy(req.stream(), target, StandardCopyOption.REPLACE_EXISTING);
            String etag = computeEtag(target);
            return new PutResult(req.objectKey(), etag, size);
        } catch (IOException e) {
            throw new StorageException("put failed: " + req.objectKey(), e);
        }
    }

    @Override
    public InputStream get(GetRequest req) {
        Path p = resolve(req.bucket(), req.objectKey());
        if (!Files.isRegularFile(p)) {
            throw new StorageException(ErrorCode.FILE_NOT_FOUND, req.objectKey());
        }
        try {
            return new BufferedInputStream(Files.newInputStream(p));
        } catch (IOException e) {
            throw new StorageException("get failed: " + req.objectKey(), e);
        }
    }

    @Override
    public RangeStream getRange(GetRangeRequest req) {
        Path p = resolve(req.bucket(), req.objectKey());
        if (!Files.isRegularFile(p)) {
            throw new StorageException(ErrorCode.FILE_NOT_FOUND, req.objectKey());
        }
        try {
            long total = Files.size(p);
            long end = req.end() < 0 ? total - 1 : Math.min(req.end(), total - 1);
            long length = end - req.start() + 1;
            RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r");
            raf.seek(req.start());
            InputStream in = new BoundedInputStream(java.nio.channels.Channels.newInputStream(raf.getChannel()), length) {
                @Override
                public void close() throws IOException {
                    super.close();
                    raf.close();
                }
            };
            return new RangeStream(in, req.start(), end, total);
        } catch (IOException e) {
            throw new StorageException("getRange failed: " + req.objectKey(), e);
        }
    }

    @Override
    public ComposeResult compose(ComposeRequest req) {
        Path target = resolve(req.bucket(), req.targetKey());
        try {
            Files.createDirectories(target.getParent());
            long total = 0;
            try (OutputStream out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[1024 * 1024];
                for (String srcKey : req.sourceKeys()) {
                    Path src = resolve(req.bucket(), srcKey);
                    if (!Files.isRegularFile(src)) {
                        throw new StorageException(ErrorCode.COMPOSE_FAILED,
                                "source not found: " + srcKey);
                    }
                    try (InputStream in = Files.newInputStream(src)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            total += n;
                        }
                    }
                }
            }
            return new ComposeResult(req.targetKey(), computeEtag(target), total);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.COMPOSE_FAILED, "compose failed: " + req.targetKey(), e);
        }
    }

    @Override
    public String presignDownload(String bucket, String objectKey, Duration ttl) {
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        String encoded = URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
        return properties.getLocal().getBaseUrl()
                + "/_local/" + bucket + "/" + encoded
                + "?exp=" + expireAt;
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            Files.deleteIfExists(resolve(bucket, objectKey));
        } catch (IOException e) {
            throw new StorageException("delete failed: " + objectKey, e);
        }
    }

    @Override
    public void deleteBatch(String bucket, List<String> keys) {
        if (keys == null) return;
        for (String k : keys) {
            try {
                Files.deleteIfExists(resolve(bucket, k));
            } catch (IOException e) {
                log.warn("delete failed (batch): {}: {}", k, e.getMessage());
            }
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        return Files.isRegularFile(resolve(bucket, objectKey));
    }

    @Override
    public ObjectStat stat(String bucket, String objectKey) {
        Path p = resolve(bucket, objectKey);
        if (!Files.isRegularFile(p)) {
            throw new StorageException(ErrorCode.FILE_NOT_FOUND, objectKey);
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            return new ObjectStat(bucket, objectKey, attrs.size(), computeEtag(p),
                    attrs.lastModifiedTime().toInstant(),
                    Files.probeContentType(p),
                    null);
        } catch (IOException e) {
            throw new StorageException("stat failed: " + objectKey, e);
        }
    }

    @Override
    public List<ObjectStat> list(String bucket, String prefix, int maxKeys) {
        Path base = resolve(bucket, prefix);
        List<ObjectStat> out = new ArrayList<>();
        Path dir = Files.isDirectory(base) ? base : base.getParent();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .limit(maxKeys)
                    .forEach(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            String rel = root.resolve(bucket).relativize(p).toString().replace('\\', '/');
                            out.add(new ObjectStat(bucket, rel, attrs.size(), null,
                                    attrs.lastModifiedTime().toInstant(),
                                    null, null));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            throw new StorageException("list failed: " + prefix, e);
        }
        return out;
    }

    private String computeEtag(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            return Md5Utils.md5(in);
        } catch (IOException e) {
            return "";
        }
    }

    /** 用于 Range 读取时裁剪长度的简易 BoundedInputStream（避免额外依赖 apache commons-io）。 */
    private static class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        BoundedInputStream(InputStream in, long max) {
            this.in = in;
            this.remaining = max;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int v = in.read();
            if (v >= 0) remaining--;
            return v;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int actual = (int) Math.min(len, remaining);
            int n = in.read(b, off, actual);
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

}
