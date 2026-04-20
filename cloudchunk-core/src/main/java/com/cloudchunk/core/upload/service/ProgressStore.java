package com.cloudchunk.core.upload.service;

import com.cloudchunk.common.constant.RedisKeys;
import com.cloudchunk.infra.redis.RedisService;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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
 *
 * 关键设计：
 * <ol>
 *   <li>字段 {@code __count__} 维护已上传数量 → O(1) 查询进度，取代 O(N) 的 HGETALL；</li>
 *   <li>{@code markDone} 使用 Lua 脚本原子执行 HSETNX + HINCRBY + EXPIRE，
 *       把"置位 + 计数 + 续期"合并为单次 RTT，避免并发分片 double-count；</li>
 *   <li>{@code rebuild} 由兜底数据重算 {@code __count__} 保持一致性。</li>
 * </ol>
 */
@Component
public class ProgressStore {

    private static final String FIELD_TOTAL = "__total__";
    private static final String FIELD_COUNT = "__count__";

    /** KEYS[1]=hashKey; ARGV[1]=chunkIndexField; ARGV[2]=ttlSeconds; 返回执行后的 done count。 */
    private static final RedisScript<Long> MARK_DONE_SCRIPT = new DefaultRedisScript<>(
            "local added = redis.call('HSETNX', KEYS[1], ARGV[1], '1') " +
                    "local count " +
                    "if added == 1 then " +
                    "  count = redis.call('HINCRBY', KEYS[1], '__count__', 1) " +
                    "else " +
                    "  count = tonumber(redis.call('HGET', KEYS[1], '__count__')) or 0 " +
                    "end " +
                    "redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
                    "return count",
            Long.class);

    private final RedisService redis;

    public ProgressStore(RedisService redis) {
        this.redis = redis;
    }

    public void init(String fileId, int chunkTotal, Duration ttl) {
        String key = RedisKeys.uploadProgress(fileId);
        redis.hSet(key, FIELD_TOTAL, String.valueOf(chunkTotal));
        redis.hSet(key, FIELD_COUNT, "0");
        redis.expire(key, ttl);
    }

    /**
     * 原子标记某个分片完成并返回最新进度数。
     * 一次 Redis RTT 完成 HSETNX + HINCRBY + EXPIRE。
     * 幂等：重复调用同一分片 count 不会增加。
     */
    public int markDone(String fileId, int chunkIndex, Duration ttl) {
        Long v = redis.raw().execute(MARK_DONE_SCRIPT,
                List.of(RedisKeys.uploadProgress(fileId)),
                String.valueOf(chunkIndex),
                String.valueOf(Math.max(1, ttl.toSeconds())));
        return v == null ? 0 : v.intValue();
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
            if (FIELD_TOTAL.equals(field) || FIELD_COUNT.equals(field)) continue;
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

    /** O(1)：直接读取计数字段，替代原先的 HGETALL + size 遍历。 */
    public int doneCount(String fileId) {
        String v = redis.hGet(RedisKeys.uploadProgress(fileId), FIELD_COUNT);
        if (v == null || v.isEmpty()) {
            // 兼容老数据/首次查询：重算一次
            int n = uploadedSet(fileId).size();
            redis.hSet(RedisKeys.uploadProgress(fileId), FIELD_COUNT, String.valueOf(n));
            return n;
        }
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }

    public void clear(String fileId) {
        redis.delete(RedisKeys.uploadProgress(fileId));
    }

    /** 用兜底数据（MySQL/Storage 交集）重建进度 Hash（包含计数字段） */
    public void rebuild(String fileId, int chunkTotal, Set<Integer> confirmed, Duration ttl) {
        String key = RedisKeys.uploadProgress(fileId);
        redis.hSet(key, FIELD_TOTAL, String.valueOf(chunkTotal));
        redis.hSet(key, FIELD_COUNT, String.valueOf(confirmed.size()));
        for (Integer i : confirmed) {
            redis.hSet(key, String.valueOf(i), "1");
        }
        redis.expire(key, ttl);
    }
}
