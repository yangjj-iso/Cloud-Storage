package com.cloudchunk.core.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.file.entity.FileReference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FileReferenceMapper extends BaseMapper<FileReference> {

    int insertIgnore(@Param("r") FileReference reference);

    int deleteByFileAndUser(@Param("fileId") String fileId, @Param("userId") long userId);

    int deleteByFileId(@Param("fileId") String fileId);

    int countByFileId(@Param("fileId") String fileId);

    List<FileReference> selectByFileId(@Param("fileId") String fileId);
}
