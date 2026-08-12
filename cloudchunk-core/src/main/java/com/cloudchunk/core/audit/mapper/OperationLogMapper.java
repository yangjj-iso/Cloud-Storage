package com.cloudchunk.core.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.audit.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {}
