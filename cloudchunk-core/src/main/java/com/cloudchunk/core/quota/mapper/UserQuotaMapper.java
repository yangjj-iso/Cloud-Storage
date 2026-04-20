package com.cloudchunk.core.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.quota.entity.UserQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserQuotaMapper extends BaseMapper<UserQuota> {

    int addUsed(@Param("userId") Long userId, @Param("bytes") long bytes);

    int subUsed(@Param("userId") Long userId, @Param("bytes") long bytes);
}
