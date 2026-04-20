package com.cloudchunk.core.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.file.entity.FileMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileMetaMapper extends BaseMapper<FileMeta> {

    /** 引用计数递增（秒传命中） */
    int incRefCount(@Param("fileId") String fileId);

    /** 引用计数递减（删除） */
    int decRefCount(@Param("fileId") String fileId);
}
