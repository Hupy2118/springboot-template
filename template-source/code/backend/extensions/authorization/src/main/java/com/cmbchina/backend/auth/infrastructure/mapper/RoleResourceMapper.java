package com.cmbchina.backend.auth.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmbchina.backend.auth.infrastructure.po.RoleResourcePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoleResourceMapper extends BaseMapper<RoleResourcePO> {

    List<String> findResourceKeysByRoleId(@Param("roleId") String roleId);

    List<String> findResourceKeysByRoleIds(@Param("roleIds") List<String> roleIds);

    int deleteBatch(@Param("roleId") String roleId, @Param("resourceKeys") List<String> resourceKeys);

    int insertBatch(@Param("items") List<RoleResourcePO> items);

    long countActiveManagers(@Param("resourceKey") String resourceKey);

}

