package com.cmbchina.backend.auth.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmbchina.backend.auth.infrastructure.po.RolePO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleMapper extends BaseMapper<RolePO> {
    Integer findMaxGeneratedRoleNumber();
}

