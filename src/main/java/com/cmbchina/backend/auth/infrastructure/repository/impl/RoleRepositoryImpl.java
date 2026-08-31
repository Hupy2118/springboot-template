package com.cmbchina.backend.auth.infrastructure.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cmbchina.backend.auth.domain.entity.Role;
import com.cmbchina.backend.auth.domain.repository.RoleRepository;
import com.cmbchina.backend.auth.infrastructure.mapper.RoleMapper;
import com.cmbchina.backend.auth.infrastructure.po.RolePO;
import com.cmbchina.backend.auth.infrastructure.repository.converter.RoleConverter;
import com.cmbchina.backend.common.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class RoleRepositoryImpl implements RoleRepository {
    private final RoleMapper roleMapper;
    private final RoleConverter converter;

    @Override
    public PageResult<Role> page(int current, int pageSize) {
        Page<RolePO> page = new Page<>(current, pageSize);
        LambdaQueryWrapper<RolePO> query = new LambdaQueryWrapper<RolePO>()
                .orderByAsc(RolePO::getId);
        Page<RolePO> result = roleMapper.selectPage(page, query);
        return PageResult.of(result.getTotal(), (int) result.getCurrent(), (int) result.getSize(),
                result.getRecords(), converter::toEntity);
    }

    @Override
    public Role findByRoleId(String roleId) {
        RolePO role = roleMapper.selectOne(new LambdaQueryWrapper<RolePO>()
                .eq(RolePO::getRoleId, roleId));
        return converter.toEntity(role);
    }

    @Override
    public Integer findMaxGeneratedRoleNumber() {
        return roleMapper.findMaxGeneratedRoleNumber();
    }

    @Override
    public void save(Role role) {
        RolePO po = converter.toPO(role);
        roleMapper.insert(po);
        role.setId(po.getId());
    }

    @Override
    public void update(Role role) {
        roleMapper.update(null, new LambdaUpdateWrapper<RolePO>()
                .eq(RolePO::getRoleId, role.getRoleId())
                .set(RolePO::getRoleName, role.getRoleName())
                .set(RolePO::getDescription, role.getDescription())
                .set(RolePO::getEnable, role.getEnable())
                .set(RolePO::getUpdatedBy, role.getUpdatedBy()));
    }

    @Override
    public void softDelete(Role role, String actor) {
        roleMapper.update(null, new LambdaUpdateWrapper<RolePO>()
                .eq(RolePO::getRoleId, role.getRoleId())
                .set(RolePO::getIsDeleted, true)
                .set(RolePO::getDeletedAt, LocalDateTime.now())
                .set(RolePO::getDeletedBy, actor)
                .set(RolePO::getUpdatedBy, actor));
    }
}
