package com.cmbchina.backend.auth.domain.repository;

import com.cmbchina.backend.auth.domain.entity.Role;
import com.cmbchina.backend.common.page.PageResult;

public interface RoleRepository {
    PageResult<Role> page(int current, int pageSize);

    long countActive();

    Role findByRoleId(String roleId);

    Integer findMaxGeneratedRoleNumber();

    void save(Role role);

    void update(Role role);

    void softDelete(Role role, String actor);
}
