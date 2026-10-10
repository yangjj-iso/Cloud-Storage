package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import com.cloudchunk.common.exception.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Hard upper bound on provider *calls*, not tokens or currency spend.
 * Failed requests are not refunded: retries cannot bypass configured daily ceilings.
 * Atomic conditional MySQL UPDATE works across API and Worker instances.
 */
@Service
public class AiDailyCallBudget {
    public enum Kind { CHAT, EMBEDDING }
    public record Counter(int used, int limit, int remaining) {}
    public record Snapshot(String dayUtc, Counter chat, Counter embedding) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int chatLimit;
    private final int embeddingLimit;

    public AiDailyCallBudget(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment env) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.chatLimit = Math.max(0, env.getProperty("cloudchunk.ai.budget.chat-calls-per-day", Integer.class, 20));
        this.embeddingLimit = Math.max(0, env.getProperty("cloudchunk.ai.budget.embedding-calls-per-day", Integer.class, 100));
    }

    public Snapshot snapshot(long userId) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        return new Snapshot(day.toString(),
            counter(userId, Kind.CHAT, day),
            counter(userId, Kind.EMBEDDING, day));
    }

    public void ensureChatAvailable(long userId) {
        if (counter(userId, Kind.CHAT, LocalDate.now(ZoneOffset.UTC)).remaining() <= 0) {
            throw BizException.of(ErrorCode.AI_BUDGET_EXHAUSTED);
        }
        // Advisory preflight only. reserve() is the final atomic gate.
    }

    public void reserve(long userId, Kind kind) {
        if (userId <= 0) throw BizException.of(ErrorCode.UNAUTHORIZED);
        int max = limit(kind);
        if (max <= 0) throw BizException.of(ErrorCode.AI_BUDGET_EXHAUSTED);
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        // Concurrent INSERT IGNORE + UPDATE of the same new daily row can deadlock
        // under InnoDB. Retry ONLY a transaction rolled back for lock contention.
        // Never retry on connection/commit ambiguity, which could double-charge.
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    jdbc.update("""
                        INSERT IGNORE INTO ai_model_daily_usage(user_id, usage_date, model_kind, used_calls)
                        VALUES (?,?,?,0)
                        """, userId, day, kind.name());
                    int accepted = jdbc.update("""
                        UPDATE ai_model_daily_usage SET used_calls=used_calls+1
                        WHERE user_id=? AND usage_date=? AND model_kind=? AND used_calls < ?
                        """, userId, day, kind.name(), max);
                    if (accepted != 1) throw BizException.of(ErrorCode.AI_BUDGET_EXHAUSTED);
                });
                return;
            } catch (PessimisticLockingFailureException rolledBackDeadlock) {
                if (attempt == 9) throw rolledBackDeadlock;
                try {
                    Thread.sleep((attempt + 1L) * 8L + (userId % 5));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("model budget transaction interrupted");
                }
            }
        }
    }

    private Counter counter(long userId, Kind kind, LocalDate day) {
        int max = limit(kind);
        List<Integer> rows = jdbc.query("""
            SELECT used_calls FROM ai_model_daily_usage
            WHERE user_id=? AND usage_date=? AND model_kind=?
            """, (rs, i) -> rs.getInt(1), userId, day, kind.name());
        int used = rows.isEmpty() ? 0 : rows.getFirst();
        return new Counter(used, max, Math.max(0, max-used));
    }

    private int limit(Kind kind) { return kind == Kind.CHAT ? chatLimit : embeddingLimit; }
}
