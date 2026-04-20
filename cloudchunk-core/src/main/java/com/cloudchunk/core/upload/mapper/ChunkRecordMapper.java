package com.cloudchunk.core.upload.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.upload.entity.ChunkRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChunkRecordMapper extends BaseMapper<ChunkRecord> {

    /** 幂等 upsert：存在则更新 status/etag，否则插入 */
    int upsert(@Param("r") ChunkRecord record);
}
