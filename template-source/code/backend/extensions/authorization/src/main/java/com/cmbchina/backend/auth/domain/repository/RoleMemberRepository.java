package com.cmbchina.backend.auth.domain.repository;

import com.cmbchina.backend.auth.domain.entity.RoleMember;

import java.util.List;

public interface RoleMemberRepository {

    List<RoleMember> findByRoleId(String roleId);

    void deleteByRoleId(String roleId);

    void deleteBatch(String roleId, List<String> memberIds);

    void saveAll(List<RoleMember> items);

}
