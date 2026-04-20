package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.infra.redis.RedisService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 分片进度存储：Redis Hash 为主，MySQL/Storage 为兜底（由调用方传入）。
 */
@Component
public class ProgressStore {

    private static final String FIELD_TOTAL = "__total__";

    private final RedisService redis;

    public ProgressStore(RedisService redis) {
        this.redis = redis;
    }

    public void init(String fileId, int chunkTotal, Duration ttl) {
        redis.hSet(RedisKeys.uploadProgress(fileId), FIELD_TOTAL, String.valueOf(chunkTotal));
        redis.expire(RedisKeys.uploadProgress(fileId), ttl);
    }

    public void markDone(String fileId, int chunkIndex, Duration ttl) {
        String key = RedisKeys.uploadProgress(fileId);
        redis.hSet(key, String.valueOf(chunkIndex), "1");
        redis.expire(key, ttl);
    }

    public boolean isDone(String fileId, int chunkIndex) {
        return "1".equals(redis.hGet(RedisKeys.uploadProgress(fileId), String.valueOf(chunkIndex)));
    }

    public Set<Integer> uploadedSet(String fileId) {
        Map<Object, Object> all = redis.hGetAll(RedisKeys.uploadProgress(fileId));
        if (all == null || all.isEmpty()) return Collections.emptySet();
        Set<Integer> set = new TreeSet<>();
        for (Map.Entry<Object, Object> e : all.entrySet()) {
            String field = String.valueOf(e.getKey());
            if (FIELD_TOTAL.equals(field)) continue;
            if (!"1".equals(String.valueOf(e.getValue()))) continue;
            try {
                set.add(Integer.parseInt(field));
            } catch (NumberFormatException ignored) {}
        }
        return set;
    }

    public List<Integer> uploaded(String fileId) {
        return new ArrayList<>(uploadedSet(fileId));
    }

    public List<Integer> missing(String fileId, int chunkTotal) {
        Set<Integer> done = uploadedSet(fileId);
        List<Integer> miss = new ArrayList<>(chunkTotal - done.size());
        for (int i = 0; i < chunkTotal; i++) {
            if (!done.contains(i)) miss.add(i);
        }
        return miss;
    }

    public int doneCount(String fileId) {
        return uploadedSet(fileId).size();
    }

    public void clear(String fileId) {
        redis.delete(RedisKeys.uploadProgress(fileId));
    }

    /** 用兜底数据（MySQL/Storage 交集）重建进度 Hash */
    public void rebuild(String fileId, int chunkTotal, Set<Integer> confirmed, Duration ttl) {
        String key = RedisKeys.uploadProgress(fileId);
        redis.hSet(key, FIELD_TOTAL, String.valueOf(chunkTotal));
        for (Integer i : confirmed) {
            redis.hSet(key, String.valueOf(i), "1");
        }
        redis.expire(key, ttl);
    }
}
