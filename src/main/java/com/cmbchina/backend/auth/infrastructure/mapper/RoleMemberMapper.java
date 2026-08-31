package com.cmbchina.backend.auth.infrastructure.mapper;

import com.cmbchina.backend.auth.infrastructure.po.RoleMemberPO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoleMemberMapper extends BaseMapper<RoleMemberPO> {
    List<RoleMemberPO> listMembers(@Param("offset") int offset, @Param("pageSize") int pageSize);

    long countMembers();

    List<RoleMemberPO> findByRoleId(@Param("roleId") String roleId);

    int insertBatch(@Param("items") List<RoleMemberPO> items);

    int deleteBatch(@Param("roleId") String roleId, @Param("memberIds") List<String> memberIds);

}

