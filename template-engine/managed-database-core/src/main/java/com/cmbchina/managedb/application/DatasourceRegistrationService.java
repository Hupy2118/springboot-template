package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.application.view.DatasourceResourceView;
import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceKeyConflictException;
import com.cmbchina.managedb.domain.DatasourceKeyGenerator;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.repository.DatasourceResourceRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.cmbchina.managedb.domain.valueobject.DatasourceLoad;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 编排选址、编码查重和有界碰撞重试，不持有数据库事务或连接。
 */
@Service
@RequiredArgsConstructor
public final class DatasourceRegistrationService implements DatasourceRegistration {
    private final DatasourceResourceRepository repository;
    private final DatabaseSchemaPort schemas;
    private final DatasourceKeyGenerator generator;

    /**
     * 选址只做一次；查重命中与明确插入冲突共享四次候选额度。
     */
    @Override
    public DatasourceResourceView register(String appKey, String appName, Actor actor) {
        DatasourceResource.validateRegistration(appKey, appName);
        List<Long> candidates = schemas.availableDatasourceIds();
        if (candidates.isEmpty()) {
            throw new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE);
        }
        long datasourceId = DatasourceLoad.select(repository.allocationLoads(candidates));
        schemas.verifyAccessible(datasourceId);
        for (int attempt = 0; attempt < 4; attempt++) {
            DatasourceKey datasourceKey = generator.generate();
            if (repository.existsIncludingDeleted(datasourceKey)) {
                continue;
            }
            DatasourceResource resource = DatasourceResource.register(datasourceKey, datasourceId, appKey, appName, actor);
            try {
                repository.insert(resource);
                return new DatasourceResourceView(resource);
            } catch (DatasourceKeyConflictException conflict) {
                // 仓储已经回滚，下一次在新事务中尝试；其他错误不得进入本分支。
            }
        }
        throw new BizException(ResourceErrorCode.DATASOURCE_KEY_GENERATION_FAILED);
    }
}
