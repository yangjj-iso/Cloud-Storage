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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Hard upper bound on provider *calls*, not tokens or currency spend.
 * Failed requests are not refunded: retries cannot bypass configured daily ceilings.
 * Atomic conditional MySQL UPDATE works across API and Worker instances.
 */
@Service
public class AiDailyCallBudget {
    public enum Kind { CHAT, EMBEDDING }
    public record Counter(int used,int limit,int remaining,long reportedCalls,
                          long inputTokens,long outputTokens,String estimatedUsd) {}
    public record Snapshot(String dayUtc, Counter chat, Counter embedding) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int chatLimit;
    private final int embeddingLimit;
    // Nullable rates mean "unknown", never silently zero-cost.
    private final BigDecimal chatInputUsdPerMillion;
    private final BigDecimal chatOutputUsdPerMillion;
    private final BigDecimal embeddingInputUsdPerMillion;

    public AiDailyCallBudget(JdbcTemplate jdbc, PlatformTransactionManager manager, Environment env) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.chatLimit = Math.max(0, env.getProperty("cloudchunk.ai.budget.chat-calls-per-day", Integer.class, 20));
        this.embeddingLimit = Math.max(0, env.getProperty("cloudchunk.ai.budget.embedding-calls-per-day", Integer.class, 100));
        this.chatInputUsdPerMillion=rate(env,"cloudchunk.ai.budget.chat-input-usd-per-million");
        this.chatOutputUsdPerMillion=rate(env,"cloudchunk.ai.budget.chat-output-usd-per-million");
        this.embeddingInputUsdPerMillion=rate(env,"cloudchunk.ai.budget.embedding-input-usd-per-million");
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

    /** Called after a successful provider response, even if later citation validation fails. */
    public void recordReported(long userId, Kind kind, ModelTokenUsage usage) {
        if(usage==null || !usage.reported())return; // unknown usage is not free usage
        if(usage.inputTokens()<0 || usage.outputTokens()<0 ||
           usage.inputTokens()>100_000_000L || usage.outputTokens()>100_000_000L)
            throw new IllegalArgumentException("invalid token count");
        int updated=jdbc.update("""
            UPDATE ai_model_daily_usage SET
              usage_reported_calls=usage_reported_calls+1,
              input_tokens=input_tokens+?,output_tokens=output_tokens+?
            WHERE user_id=? AND usage_date=? AND model_kind=?
            """,usage.inputTokens(),usage.outputTokens(),userId,
            LocalDate.now(ZoneOffset.UTC),kind.name());
        if(updated!=1)throw new IllegalStateException("model call was not reserved");
    }

    private static BigDecimal rate(Environment env,String key) {
        String raw=env.getProperty(key,"").strip();
        if(raw.isBlank())return null;
        BigDecimal value=new BigDecimal(raw);
        if(value.signum()<0 || value.compareTo(new BigDecimal("1000000"))>0)
            throw new IllegalArgumentException("invalid configured model price");
        return value;
    }

    private String estimate(Kind kind,long input,long output) {
        BigDecimal in=kind==Kind.CHAT?chatInputUsdPerMillion:embeddingInputUsdPerMillion;
        BigDecimal out=kind==Kind.CHAT?chatOutputUsdPerMillion:BigDecimal.ZERO;
        if(in==null || out==null)return null;
        return in.multiply(BigDecimal.valueOf(input)).add(out.multiply(BigDecimal.valueOf(output)))
            .divide(BigDecimal.valueOf(1_000_000),6,RoundingMode.HALF_UP).toPlainString();
    }

    private Counter counter(long userId, Kind kind, LocalDate day) {
        int max = limit(kind);
        List<long[]> rows=jdbc.query("""
            SELECT used_calls,usage_reported_calls,input_tokens,output_tokens
            FROM ai_model_daily_usage WHERE user_id=? AND usage_date=? AND model_kind=?
            """,(rs,i)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)},
            userId,day,kind.name());
        long[] row=rows.isEmpty()?new long[]{0,0,0,0}:rows.getFirst();
        return new Counter((int)row[0],max,Math.max(0,max-(int)row[0]),
            row[1],row[2],row[3],estimate(kind,row[2],row[3]));
    }

    private int limit(Kind kind) { return kind == Kind.CHAT ? chatLimit : embeddingLimit; }
}
