package com.cmbchina.backend.auth.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cmbchina.backend.auth.infrastructure.po.RoleMemberPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoleMemberMapper extends BaseMapper<RoleMemberPO> {

    List<RoleMemberPO> findByRoleId(@Param("roleId") String roleId);

    int insertBatch(@Param("items") List<RoleMemberPO> items);

    int deleteBatch(@Param("roleId") String roleId, @Param("memberIds") List<String> memberIds);

    List<String> findEffectiveRoleIdsByMemberId(@Param("memberId") String memberId);

}
