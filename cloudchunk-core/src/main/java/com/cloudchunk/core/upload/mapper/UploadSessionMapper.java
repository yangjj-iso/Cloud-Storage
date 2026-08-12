package com.cloudchunk.core.upload.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.upload.entity.UploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    @Select("SELECT * FROM upload_session WHERE file_id = #{fileId} FOR UPDATE")
    UploadSession selectByFileIdForUpdate(@Param("fileId") String fileId);
}
