package com.cmbchina.backend.auth.infrastructure.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmbchina.backend.auth.domain.entity.RoleMember;
import com.cmbchina.backend.auth.domain.repository.RoleMemberRepository;
import com.cmbchina.backend.auth.infrastructure.mapper.RoleMemberMapper;
import com.cmbchina.backend.auth.infrastructure.po.RoleMemberPO;
import com.cmbchina.backend.auth.infrastructure.repository.converter.RoleMemberConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class RoleMemberRepositoryImpl implements RoleMemberRepository {

    private final RoleMemberMapper roleMemberMapper;
    private final RoleMemberConverter converter;

    @Override
    public List<RoleMember> findByRoleId(String roleId) {
        return converter.toEntities(roleMemberMapper.findByRoleId(roleId));
    }

    @Override
    public void deleteByRoleId(String roleId) {
        roleMemberMapper.delete(new LambdaQueryWrapper<RoleMemberPO>()
                .eq(RoleMemberPO::getRoleId, roleId));
    }

    @Override
    public void deleteBatch(String roleId, List<String> memberIds) {
        roleMemberMapper.deleteBatch(roleId, memberIds);
    }

    @Override
    public void saveAll(List<RoleMember> items) {
        roleMemberMapper.insertBatch(converter.toPOs(items));
    }

}
