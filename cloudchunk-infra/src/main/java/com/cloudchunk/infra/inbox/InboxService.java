package com.cloudchunk.infra.inbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class InboxService {

    public enum AcquireResult { ACQUIRED, ALREADY_DONE, BUSY }

    private static final Duration STALE_PROCESSING = Duration.ofHours(1);
    private final InboxEventMapper mapper;

    public InboxService(InboxEventMapper mapper) {
        this.mapper = mapper;
    }

    public AcquireResult acquire(String eventId, String eventType) {
        LocalDateTime now = LocalDateTime.now();
        InboxEvent row = new InboxEvent();
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setStatus(0);
        row.setAttemptCount(1);
        row.setLockedAt(now);
        try {
            mapper.insert(row);
            return AcquireResult.ACQUIRED;
        } catch (DuplicateKeyException duplicate) {
            InboxEvent existing = mapper.selectOne(new LambdaQueryWrapper<InboxEvent>()
                    .eq(InboxEvent::getEventId, eventId));
            if (existing != null && Integer.valueOf(1).equals(existing.getStatus())) {
                return AcquireResult.ALREADY_DONE;
            }
            int reacquired = mapper.reacquire(eventId, now, now.minus(STALE_PROCESSING));
            return reacquired > 0 ? AcquireResult.ACQUIRED : AcquireResult.BUSY;
        }
    }

    public void markDone(String eventId) {
        mapper.markDone(eventId, LocalDateTime.now());
    }

    public void markFailed(String eventId, Throwable error) {
        String message = error == null ? "unknown" : error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        mapper.markFailed(eventId, message.substring(0, Math.min(message.length(), 1024)));
    }
}
