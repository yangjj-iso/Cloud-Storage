package com.cloudchunk.core.drive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudchunk.core.drive.entity.UserFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * user_file 数据访问。普通查询用 LambdaQueryWrapper 在 Service 层组装；
 * 整棵子树遍历用 MySQL 8 递归 CTE 一次查完（见 UserFileMapper.xml），避免逐层 N+1。
 */
@Mapper
public interface UserFileMapper extends BaseMapper<UserFile> {

    /**
     * 递归返回 rootId 及其全部后代（含 root）。status 为空表示不限状态；否则只纳入该状态的后代
     * （root 本身不受 status 过滤，与原 BFS 语义一致）。
     */
    List<UserFile> selectSubtree(@Param("userId") long userId,
                                 @Param("rootId") long rootId,
                                 @Param("status") Integer status,
                                 @Param("limit") int limit);
}
