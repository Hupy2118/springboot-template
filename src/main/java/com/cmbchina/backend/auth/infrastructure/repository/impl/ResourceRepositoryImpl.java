package com.cmbchina.backend.auth.infrastructure.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cmbchina.backend.auth.domain.entity.Resource;
import com.cmbchina.backend.auth.domain.repository.ResourceRepository;
import com.cmbchina.backend.auth.infrastructure.mapper.ResourceMapper;
import com.cmbchina.backend.auth.infrastructure.po.ResourcePO;
import com.cmbchina.backend.auth.infrastructure.repository.converter.ResourceConverter;
import com.cmbchina.backend.common.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ResourceRepositoryImpl implements ResourceRepository {
    private final ResourceMapper mapper;
    private final ResourceConverter converter;

    @Override
    public PageResult<Resource> page(int current, int pageSize) {
        Page<ResourcePO> page = new Page<>(current, pageSize);
        LambdaQueryWrapper<ResourcePO> query = new LambdaQueryWrapper<ResourcePO>().orderByAsc(ResourcePO::getId);
        Page<ResourcePO> result = mapper.selectPage(page, query);
        return PageResult.of(result.getTotal(), (int) result.getCurrent(),
                (int) result.getSize(), result.getRecords(), converter::toEntity);
    }

    @Override
    public Resource findByKey(String resourceKey) {
        LambdaQueryWrapper<ResourcePO> query =
                new LambdaQueryWrapper<ResourcePO>().eq(ResourcePO::getKey, resourceKey);
        ResourcePO resource = mapper.selectOne(query);
        return converter.toEntity(resource);
    }

    @Override
    public List<String> findExistingKeys(List<String> resourceKeys) {
        if (resourceKeys == null || resourceKeys.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return mapper.selectList(new LambdaQueryWrapper<ResourcePO>()
                        .in(ResourcePO::getKey, resourceKeys)
                        .select(ResourcePO::getKey)
                        .orderByAsc(ResourcePO::getKey))
                .stream()
                .map(ResourcePO::getKey)
                .collect(Collectors.toList());
    }

    @Override
    public void save(Resource resource) {
        ResourcePO po = converter.toPO(resource);
        mapper.insert(po);
        resource.setId(po.getId());
    }
}
