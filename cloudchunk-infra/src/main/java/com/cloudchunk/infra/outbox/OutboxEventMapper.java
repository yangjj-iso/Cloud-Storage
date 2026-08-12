package com.cloudchunk.infra.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    @Select("""
            SELECT * FROM event_outbox
            WHERE ((status IN (0, 3) AND next_attempt_at <= #{now})
                   OR (status = 1 AND locked_at < #{staleBefore}))
            ORDER BY id
            LIMIT #{limit}
            """)
    List<OutboxEvent> findReady(@Param("now") LocalDateTime now,
                                @Param("staleBefore") LocalDateTime staleBefore,
                                @Param("limit") int limit);

    @Update("""
            UPDATE event_outbox
            SET status = 1, locked_by = #{worker}, locked_at = #{now}
            WHERE id = #{id}
              AND ((status IN (0, 3) AND next_attempt_at <= #{now})
                   OR (status = 1 AND locked_at < #{staleBefore}))
            """)
    int claim(@Param("id") long id,
              @Param("worker") String worker,
              @Param("now") LocalDateTime now,
              @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
            UPDATE event_outbox
            SET status = 2, published_at = #{now}, locked_by = NULL, locked_at = NULL,
                last_error = NULL
            WHERE id = #{id} AND status = 1 AND locked_by = #{worker}
            """)
    int markPublished(@Param("id") long id,
                      @Param("worker") String worker,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE event_outbox
            SET status = 3, attempt_count = attempt_count + 1,
                next_attempt_at = #{nextAttemptAt}, last_error = #{error},
                locked_by = NULL, locked_at = NULL
            WHERE id = #{id} AND status = 1 AND locked_by = #{worker}
            """)
    int markRetry(@Param("id") long id,
                  @Param("worker") String worker,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                  @Param("error") String error);

    /**
     * 删除已投递成功的历史事件。发布之后这些行只剩审计价值，但写入速率等同于业务写入速率，
     * 不清理就会无限增长。按批删除以避免长事务和大范围行锁。
     */
    @Delete("DELETE FROM event_outbox WHERE status = 2 AND published_at < #{before} LIMIT #{limit}")
    int deletePublishedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
