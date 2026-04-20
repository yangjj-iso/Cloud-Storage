package com.cloudchunk.core.download.service;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
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

    public DownloadService(FileMetaService fileMetaService, StorageStrategyFactory storageFactory) {
        this.fileMetaService = fileMetaService;
        this.storageFactory = storageFactory;
    }

    /** 生成（或获取缓存的）预签名 URL */
    public PresignedUrl presign(String fileId, Duration ttl) {
        FileMeta meta = fileMetaService.getAvailableOrThrow(fileId);
        String cached = fileMetaService.getCachedUrl(fileId);
        if (cached != null && !cached.isBlank()) {
            return new PresignedUrl(cached, null);
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
    public DownloadStream open(String fileId, String rangeHeader) {
        FileMeta meta = fileMetaService.getAvailableOrThrow(fileId);
        long total = meta.getFileSize();
        RangeSpec spec = RangeSpec.parse(rangeHeader, total);
        if (!spec.valid()) {
            throw BizException.of(ErrorCode.RANGE_NOT_SATISFIABLE, rangeHeader);
        }
        StorageStrategy storage = storageFactory.current();
        if (spec.isFull()) {
            InputStream in = storage.get(new GetRequest(meta.getBucket(), meta.getObjectKey()));
            return new DownloadStream(meta, in, 0, total - 1, total, true);
        }
        RangeStream rs = storage.getRange(new GetRangeRequest(
                meta.getBucket(), meta.getObjectKey(), spec.start(), spec.end()));
        return new DownloadStream(meta, rs.stream(), rs.start(), rs.end(), rs.total(), false);
    }

    public record PresignedUrl(String url, Duration ttl) {}

    public record DownloadStream(FileMeta meta, InputStream stream,
                                 long start, long end, long total, boolean full) {}
}
