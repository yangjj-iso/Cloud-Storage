package com.cloudchunk.infra.inbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface InboxEventMapper extends BaseMapper<InboxEvent> {

    @Update("""
            UPDATE event_inbox
            SET status = 0, locked_at = #{now}, attempt_count = attempt_count + 1,
                last_error = NULL
            WHERE event_id = #{eventId}
              AND (status = 2 OR (status = 0 AND locked_at < #{staleBefore}))
            """)
    int reacquire(@Param("eventId") String eventId,
                  @Param("now") LocalDateTime now,
                  @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
            UPDATE event_inbox
            SET status = 1, processed_at = #{now}, last_error = NULL
            WHERE event_id = #{eventId} AND status = 0
            """)
    int markDone(@Param("eventId") String eventId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE event_inbox
            SET status = 2, last_error = #{error}
            WHERE event_id = #{eventId} AND status = 0
            """)
    int markFailed(@Param("eventId") String eventId, @Param("error") String error);

    /**
     * 删除早已处理完成的幂等记录。
     * <p>
     * 保留窗口必须大于消息中间件的消息保留时长：这些行就是消费幂等键，在 Broker 仍可能重投的
     * 时间窗内删掉它，重复投递就会被当成新消息重新执行一遍副作用。
     */
    @Delete("DELETE FROM event_inbox WHERE status = 1 AND processed_at < #{before} LIMIT #{limit}")
    int deleteProcessedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
