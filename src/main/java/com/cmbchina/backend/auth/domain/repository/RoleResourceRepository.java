package com.cmbchina.backend.auth.domain.repository;

import com.cmbchina.backend.auth.domain.entity.RoleResource;

import java.util.List;

public interface RoleResourceRepository {
    List<String> findResourceKeysByRoleId(String roleId);

    List<String> findEffectiveResourceKeys(String memberId);

    void deleteByRoleId(String roleId);

    void deleteBatch(String roleId, List<String> resourceKeys);

    void saveAll(List<RoleResource> items);

    long countActiveManagers(String resourceKey);

}
