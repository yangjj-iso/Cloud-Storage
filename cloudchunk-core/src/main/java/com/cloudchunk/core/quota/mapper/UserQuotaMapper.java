package com.cloudchunk.core.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.quota.entity.UserQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserQuotaMapper extends BaseMapper<UserQuota> {

    int addUsed(@Param("userId") Long userId, @Param("bytes") long bytes);

    int subUsed(@Param("userId") Long userId, @Param("bytes") long bytes);

    /**
     * 原子预留：仅当 used_bytes + bytes <= total_bytes 时才增加，返回受影响行数（0=容量不足）。
     * 消除“先查后加”的 TOCTOU 竞态。
     */
    int tryAddUsed(@Param("userId") Long userId, @Param("bytes") long bytes);
}
