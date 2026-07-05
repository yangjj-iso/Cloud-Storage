package com.cloudchunk.core.upload.service;

import com.cloudchunk.infra.redis.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProgressStoreTest {

    @Mock
    private RedisService redis;

    @InjectMocks
    private ProgressStore progressStore;

    private static final String FILE_ID = "test-file-id";
    private static final String PROGRESS_KEY = "cc:upload:progress:" + FILE_ID;

    @BeforeEach
    void setUp() {
        // common setup if needed
    }

    @Test
    void init_setsHashFieldsAndExpire() {
        Duration ttl = Duration.ofHours(24);

        progressStore.init(FILE_ID, 10, ttl);

        verify(redis).hSet(PROGRESS_KEY, "__total__", "10");
        verify(redis).hSet(PROGRESS_KEY, "__count__", "0");
        verify(redis).expire(PROGRESS_KEY, ttl);
    }

    @Test
    void markDone_executesLuaScript() {
        StringRedisTemplate rawTemplate = mock(StringRedisTemplate.class);
        when(redis.raw()).thenReturn(rawTemplate);
        when(rawTemplate.execute(any(), anyList(), anyString(), anyString())).thenReturn(5L);

        int result = progressStore.markDone(FILE_ID, 3, Duration.ofHours(24));

        assertThat(result).isEqualTo(5);
        verify(rawTemplate).execute(any(), eq(List.of(PROGRESS_KEY)), eq("3"), eq("86400"));
    }

    @Test
    void isDone_returnsTrueWhenFieldIs1() {
        when(redis.hGet(PROGRESS_KEY, "7")).thenReturn("1");

        assertThat(progressStore.isDone(FILE_ID, 7)).isTrue();
    }

    @Test
    void isDone_returnsFalseWhenFieldNull() {
        when(redis.hGet(PROGRESS_KEY, "7")).thenReturn(null);

        assertThat(progressStore.isDone(FILE_ID, 7)).isFalse();
    }

    @Test
    void doneCount_parsesCountField() {
        when(redis.hGet(PROGRESS_KEY, "__count__")).thenReturn("42");

        assertThat(progressStore.doneCount(FILE_ID)).isEqualTo(42);
    }

    @Test
    void doneCount_rebuildsWhenNull() {
        when(redis.hGet(PROGRESS_KEY, "__count__")).thenReturn(null);
        Map<Object, Object> allFields = new HashMap<>();
        allFields.put("0", "1");
        allFields.put("2", "1");
        allFields.put("5", "1");
        allFields.put("__total__", "10");
        when(redis.hGetAll(PROGRESS_KEY)).thenReturn(allFields);

        int count = progressStore.doneCount(FILE_ID);

        assertThat(count).isEqualTo(3);
        verify(redis).hSet(PROGRESS_KEY, "__count__", "3");
    }

    @Test
    void rebuild_writesAllFieldsAndExpire() {
        Set<Integer> confirmed = new TreeSet<>(Set.of(0, 2, 5));
        Duration ttl = Duration.ofHours(24);

        progressStore.rebuild(FILE_ID, 10, confirmed, ttl);

        verify(redis).hSet(PROGRESS_KEY, "__total__", "10");
        verify(redis).hSet(PROGRESS_KEY, "__count__", "3");
        verify(redis).hSet(PROGRESS_KEY, "0", "1");
        verify(redis).hSet(PROGRESS_KEY, "2", "1");
        verify(redis).hSet(PROGRESS_KEY, "5", "1");
        verify(redis).expire(PROGRESS_KEY, ttl);
    }
}
