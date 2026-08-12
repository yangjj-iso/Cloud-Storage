package com.cloudchunk.infra.gc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ObjectGcTaskMapper extends BaseMapper<ObjectGcTask> {

    @Select("""
            SELECT * FROM object_gc_task
            WHERE ((status IN (0, 3) AND next_attempt_at <= #{now})
                   OR (status = 1 AND locked_at < #{staleBefore}))
            ORDER BY id LIMIT #{limit}
            """)
    List<ObjectGcTask> findReady(@Param("now") LocalDateTime now,
                                 @Param("staleBefore") LocalDateTime staleBefore,
                                 @Param("limit") int limit);

    @Update("""
            UPDATE object_gc_task SET status = 1, locked_by = #{worker}, locked_at = #{now}
            WHERE id = #{id}
              AND ((status IN (0, 3) AND next_attempt_at <= #{now})
                   OR (status = 1 AND locked_at < #{staleBefore}))
            """)
    int claim(@Param("id") long id, @Param("worker") String worker,
              @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);

    @Update("""
            UPDATE object_gc_task
            SET status = 2, completed_at = #{now}, locked_by = NULL, locked_at = NULL, last_error = NULL
            WHERE id = #{id} AND status = 1 AND locked_by = #{worker}
            """)
    int markDone(@Param("id") long id, @Param("worker") String worker,
                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE object_gc_task
            SET status = 3, attempt_count = attempt_count + 1, next_attempt_at = #{nextAttemptAt},
                last_error = #{error}, locked_by = NULL, locked_at = NULL
            WHERE id = #{id} AND status = 1 AND locked_by = #{worker}
            """)
    int markRetry(@Param("id") long id, @Param("worker") String worker,
                  @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("error") String error);
}
