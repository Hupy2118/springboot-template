package com.cmbchina.backend.auth.domain.repository;

import com.cmbchina.backend.auth.domain.entity.Resource;
import com.cmbchina.backend.common.page.PageResult;

import java.util.List;

public interface ResourceRepository {
    PageResult<Resource> page(int current, int pageSize);

    Resource findByKey(String resourceKey);

    List<String> findExistingKeys(List<String> resourceKeys);
}
