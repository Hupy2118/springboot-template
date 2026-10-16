package com.cmbchina.backend.auth.infrastructure.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmbchina.backend.auth.domain.entity.RoleResource;
import com.cmbchina.backend.auth.domain.repository.RoleResourceRepository;
import com.cmbchina.backend.auth.infrastructure.mapper.RoleMemberMapper;
import com.cmbchina.backend.auth.infrastructure.mapper.RoleResourceMapper;
import com.cmbchina.backend.auth.infrastructure.po.RoleResourcePO;
import com.cmbchina.backend.auth.infrastructure.repository.converter.RoleResourceConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RoleResourceRepositoryImpl implements RoleResourceRepository {

    private final RoleMemberMapper roleMemberMapper;
    private final RoleResourceMapper roleResourceMapper;
    private final RoleResourceConverter converter;

    @Override
    public List<String> findResourceKeysByRoleId(String roleId) {
        return roleResourceMapper.findResourceKeysByRoleId(roleId);
    }

    @Override
    public List<String> findEffectiveResourceKeys(String memberId) {
        List<String> roleIds = roleMemberMapper.findEffectiveRoleIdsByMemberId(memberId);
        if (CollectionUtils.isEmpty(roleIds)) {
            return Collections.emptyList();
        }
        return roleResourceMapper.findResourceKeysByRoleIds(roleIds);
    }

    @Override
    public void deleteByRoleId(String roleId) {
        roleResourceMapper.delete(new LambdaQueryWrapper<RoleResourcePO>().eq(RoleResourcePO::getRoleId, roleId));
    }

    @Override
    public void deleteBatch(String roleId, List<String> resourceKeys) {
        if (roleId == null || resourceKeys == null || resourceKeys.isEmpty()) {
            return;
        }
        roleResourceMapper.deleteBatch(roleId, resourceKeys);
    }

    @Override
    public void saveAll(List<RoleResource> items) {
        roleResourceMapper.insertBatch(converter.toPOs(items));
    }

    @Override
    public long countActiveManagers(String resourceKey) {
        return roleResourceMapper.countActiveManagers(resourceKey);
    }

}
