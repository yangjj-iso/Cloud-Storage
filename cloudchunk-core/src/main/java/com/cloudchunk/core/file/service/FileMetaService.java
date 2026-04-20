package com.cloudchunk.core.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.common.enums.FileStatus;
import com.cloudchunk.common.enums.TranscodeStatus;
import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import com.cloudchunk.core.CloudchunkProperties;
import com.cloudchunk.core.file.entity.FileMeta;
import com.cloudchunk.core.file.mapper.FileMetaMapper;
import com.cloudchunk.infra.redis.RedisService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

@Service
public class FileMetaService {

    private static final Logger log = LoggerFactory.getLogger(FileMetaService.class);

    private final FileMetaMapper mapper;
    private final RedisService redis;
    private final Cache<String, FileMeta> localCache;

    public FileMetaService(FileMetaMapper mapper, RedisService redis, CloudchunkProperties props) {
        this.mapper = mapper;
        this.redis = redis;
        CloudchunkProperties.Cache c = props.getCache();
        if (c.isFileMetaEnabled()) {
            this.localCache = Caffeine.newBuilder()
                    .maximumSize(c.getFileMetaMaxSize())
                    .expireAfterWrite(c.getFileMetaTtl())
                    .recordStats()
                    .build();
            log.info("FileMeta Caffeine cache enabled: maxSize={}, ttl={}",
                    c.getFileMetaMaxSize(), c.getFileMetaTtl());
        } else {
            this.localCache = null;
            log.info("FileMeta Caffeine cache disabled");
        }
    }

    public Optional<FileMeta> findById(String fileId) {
        if (localCache != null) {
            FileMeta cached = localCache.getIfPresent(fileId);
            if (cached != null) return Optional.of(cached);
        }
        FileMeta m = mapper.selectOne(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId));
        if (m != null && localCache != null) {
            localCache.put(fileId, m);
        }
        return Optional.ofNullable(m);
    }

    /** 暴露 Caffeine 缓存统计（Micrometer 可采集） */
    public Cache<String, FileMeta> getLocalCache() {
        return localCache;
    }

    public FileMeta getOrThrow(String fileId) {
        return findById(fileId).orElseThrow(() -> BizException.of(ErrorCode.FILE_NOT_FOUND, fileId));
    }

    public FileMeta getAvailableOrThrow(String fileId) {
        FileMeta m = getOrThrow(fileId);
        if (m.getStatus() == FileStatus.BROKEN) throw BizException.of(ErrorCode.FILE_BROKEN, fileId);
        if (m.getStatus() == FileStatus.DELETED) throw BizException.of(ErrorCode.FILE_NOT_FOUND, fileId);
        return m;
    }

    public Optional<FileMeta> findAvailableByMd5(String md5) {
        FileMeta m = mapper.selectOne(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getFileMd5, md5)
                .eq(FileMeta::getStatus, FileStatus.AVAILABLE)
                .last("limit 1"));
        return Optional.ofNullable(m);
    }

    public void insert(FileMeta meta) {
        mapper.insert(meta);
    }

    @Transactional(rollbackFor = Exception.class)
    public void incRefCount(String fileId) {
        int n = mapper.incRefCount(fileId);
        if (n <= 0) {
            throw BizException.of(ErrorCode.FILE_NOT_FOUND, fileId);
        }
        invalidateCache(fileId);
    }

    @Transactional(rollbackFor = Exception.class)
    public int decRefCount(String fileId) {
        int n = mapper.decRefCount(fileId);
        invalidateCache(fileId);
        return n;
    }

    public void updateStatus(String fileId, FileStatus status) {
        mapper.update(null, new LambdaUpdateWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId)
                .set(FileMeta::getStatus, status));
        invalidateCache(fileId);
    }

    public void updateTranscodeStatus(String fileId, TranscodeStatus status) {
        mapper.update(null, new LambdaUpdateWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId)
                .set(FileMeta::getTranscodeStatus, status));
        invalidateCache(fileId);
    }

    public void updateExtra(String fileId, String extraJson, String thumbnailUrl) {
        LambdaUpdateWrapper<FileMeta> w = new LambdaUpdateWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId)
                .set(FileMeta::getExtra, extraJson);
        if (thumbnailUrl != null) w.set(FileMeta::getThumbnailUrl, thumbnailUrl);
        mapper.update(null, w);
        invalidateCache(fileId);
    }

    public void markDeleted(String fileId) {
        mapper.update(null, new LambdaUpdateWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId)
                .set(FileMeta::getStatus, FileStatus.DELETED));
        invalidateCache(fileId);
    }

    public void cacheUrl(String fileId, String url, Duration ttl) {
        try {
            redis.set(RedisKeys.fileUrl(fileId), url, ttl);
        } catch (Exception e) {
            log.debug("cache url failed: {}", fileId, e);
        }
    }

    public String getCachedUrl(String fileId) {
        try {
            return redis.get(RedisKeys.fileUrl(fileId));
        } catch (Exception e) {
            return null;
        }
    }

    public Duration getCachedUrlTtl(String fileId) {
        try {
            return redis.getExpire(RedisKeys.fileUrl(fileId));
        } catch (Exception e) {
            return null;
        }
    }

    private void invalidateCache(String fileId) {
        if (localCache != null) localCache.invalidate(fileId);
        try {
            redis.delete(RedisKeys.fileMeta(fileId));
            redis.delete(RedisKeys.fileUrl(fileId));
        } catch (Exception e) {
            log.debug("invalidate cache failed: {}", fileId, e);
        }
    }
}
