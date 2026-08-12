package com.cloudchunk.core.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.audit.entity.OperationLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {

    /** 按批删除过期审计记录；保留窗口由合规要求决定，默认远长于其他几张运维表。 */
    @Delete("DELETE FROM op_log WHERE created_at < #{before} LIMIT #{limit}")
    int deleteCreatedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
