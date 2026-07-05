package com.cloudchunk.core.download.service;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.common.io.RateLimitedInputStream;
import com.cloudchunk.core.drive.service.UserFileService;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.service.FileMetaService;
import com.cloudchunk.storage.StorageStrategy;
import com.cloudchunk.storage.StorageStrategyFactory;
import com.cloudchunk.storage.model.GetRangeRequest;
import com.cloudchunk.storage.model.GetRequest;
import com.cloudchunk.storage.model.RangeSpec;
import com.cloudchunk.storage.model.RangeStream;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;

@Service
public class DownloadService {

    private final FileMetaService fileMetaService;
    private final StorageStrategyFactory storageFactory;
    private final UserFileService userFileService;

    /** 全局下载限速（字节/秒），0 = 不限速。由管理端 download_speed_limit 设置驱动。 */
    private volatile long speedLimit = 0;

    public DownloadService(FileMetaService fileMetaService,
                           StorageStrategyFactory storageFactory,
                           UserFileService userFileService) {
        this.fileMetaService = fileMetaService;
        this.storageFactory = storageFactory;
        this.userFileService = userFileService;
    }

    public void setSpeedLimit(long bytesPerSec) {
        this.speedLimit = Math.max(0, bytesPerSec);
    }

    public long getSpeedLimit() {
        return speedLimit;
    }

    private InputStream throttle(InputStream in) {
        long limit = speedLimit;
        return limit > 0 ? new RateLimitedInputStream(in, limit) : in;
    }

    /** 生成（或获取缓存的）预签名 URL */
    public PresignedUrl presign(String fileId, Duration ttl, long userId) {
        FileMeta meta = fileMetaService.getAvailableForUserOrThrow(fileId, userId);
        requireActiveEntry(fileId, userId);
        String cached = fileMetaService.getCachedUrl(fileId);
        if (cached != null && !cached.isBlank()) {
            Duration remaining = fileMetaService.getCachedUrlTtl(fileId);
            if (remaining != null && !remaining.isNegative()
                    && !remaining.isZero() && remaining.compareTo(ttl) <= 0) {
                return new PresignedUrl(cached, remaining);
            }
        }
        StorageStrategy storage = storageFactory.current();
        String url = storage.presignDownload(meta.getBucket(), meta.getObjectKey(), ttl);
        // 缓存略短于 TTL，避免返回已过期 URL
        Duration cacheTtl = ttl.compareTo(Duration.ofMinutes(5)) > 0
                ? ttl.minusMinutes(1) : ttl;
        fileMetaService.cacheUrl(fileId, url, cacheTtl);
        return new PresignedUrl(url, cacheTtl);
    }

    /** 读取文件流（全部或 Range） */
    public DownloadStream open(String fileId, String rangeHeader, long userId) {
        FileMeta meta = fileMetaService.getAvailableForUserOrThrow(fileId, userId);
        requireActiveEntry(fileId, userId);
        long total = meta.getFileSize();
        RangeSpec spec = RangeSpec.parse(rangeHeader, total);
        if (!spec.valid()) {
            throw BizException.of(ErrorCode.RANGE_NOT_SATISFIABLE, rangeHeader);
        }
        StorageStrategy storage = storageFactory.current();
        if (spec.isFull()) {
            InputStream in = storage.get(new GetRequest(meta.getBucket(), meta.getObjectKey()));
            return new DownloadStream(meta, throttle(in), 0, total - 1, total, true);
        }
        RangeStream rs = storage.getRange(GetRangeRequest.of(
                meta.getBucket(), meta.getObjectKey(), spec.start(), spec.end(), total));
        return new DownloadStream(meta, throttle(rs.stream()), rs.start(), rs.end(), rs.total(), false);
    }

    public record PresignedUrl(String url, Duration ttl) {}

    public record DownloadStream(FileMeta meta, InputStream stream,
                                 long start, long end, long total, boolean full) {}

    private void requireActiveEntry(String fileId, long userId) {
        if (!userFileService.hasActiveFileEntry(userId, fileId)) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, fileId);
        }
    }
}
