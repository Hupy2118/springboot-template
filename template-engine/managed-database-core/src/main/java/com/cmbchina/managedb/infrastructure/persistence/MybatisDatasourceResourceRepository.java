package com.cmbchina.managedb.infrastructure.persistence;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.DatasourceKeyConflictException;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.repository.DatasourceResourceRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.cmbchina.managedb.domain.valueobject.DatasourceLoad;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceCountPo;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceRegistrationPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 管理库注册仓储，所有失败先离开事务，再翻译为可公开异常。
 */
public final class MybatisDatasourceResourceRepository implements DatasourceResourceRepository {
    private final DatasourceRegistrationMapper mapper;
    private final IdentifierGenerator identifiers;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    /**
     * 配置相互独立且有超时的短事务，避免碰撞后复用失败事务。
     */
    public MybatisDatasourceResourceRepository(DatasourceRegistrationMapper mapper,
                                               IdentifierGenerator identifiers, PlatformTransactionManager manager, int timeoutSeconds) {
        this.mapper = mapper;
        this.identifiers = identifiers;
        this.read = template(manager, timeoutSeconds, true);
        this.write = template(manager, timeoutSeconds, false);
    }

    /**
     * 统一管理事务配置，只读统计使用同一快照。
     */
    private static TransactionTemplate template(PlatformTransactionManager manager,
                                                int timeoutSeconds, boolean readOnly) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("管理事务超时必须为正数");
        }
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setTimeout(timeoutSeconds);
        template.setReadOnly(readOnly);
        return template;
    }

    /**
     * 先以候选列表补齐零计数，防止新挂载被分组查询漏掉。
     */
    @Override
    public List<DatasourceLoad> allocationLoads(List<Long> datasourceIds) {
        if (datasourceIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return read.execute(transaction -> {
                Map<Long, Long> tables = counts(mapper.countTables(datasourceIds));
                Map<Long, Long> resourceCounts = counts(mapper.countResources(datasourceIds));
                List<DatasourceLoad> result = new ArrayList<>();
                for (Long id : datasourceIds) {
                    result.add(new DatasourceLoad(id, tables.getOrDefault(id, 0L),
                            resourceCounts.getOrDefault(id, 0L)));
                }
                return result;
            });
        } catch (RuntimeException exception) {
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 转换基础设施计数行，不让 PO 进入业务层。
     */
    private static Map<Long, Long> counts(List<DatasourceCountPo> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (DatasourceCountPo row : rows) {
            result.put(row.getDatasourceId(), row.getTotal());
        }
        return result;
    }

    /**
     * 每个候选独立读取，包含软删除和已释放记录。
     */
    @Override
    public boolean existsIncludingDeleted(DatasourceKey datasourceKey) {
        try {
            return Boolean.TRUE.equals(read.execute(transaction ->
                    mapper.countByDatasourceKey(datasourceKey.getValue()) > 0));
        } catch (RuntimeException exception) {
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 事务返回意味着已提交，只有确定的编码唯一冲突允许重新生成。
     */
    @Override
    public void insert(DatasourceResource resource) {
        DatasourceRegistrationPo row = new DatasourceRegistrationPo();
        row.setId(identifiers.nextId(row).longValue());
        row.setDatasourceKey(resource.getDatasourceKey().getValue());
        row.setDatasourceId(resource.getDatasourceId());
        row.setAppKey(resource.getAppKey());
        row.setAppName(resource.getAppName());
        row.setStatus(resource.getStatus().name());
        row.setCreatedBy(truncate(resource.getCreatedBy(), 32));
        row.setCreator(truncate(resource.getCreator(), 255));
        row.setDeleted(0);
        try {
            write.execute(transaction -> {
                if (mapper.insert(row) != 1) {
                    throw new IllegalStateException("注册记录未写入");
                }
                return null;
            });
        } catch (DuplicateKeyException exception) {
            // TransactionTemplate 已成功回滚；回滚失败会抛其他异常，禁止重试。
            if (isDatasourceKeyConflict(exception)) {
                throw new DatasourceKeyConflictException();
            }
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        } catch (RuntimeException exception) {
            // 包括提交结果不确定，不能尝试另一编码再次插入。
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 截断到列宽；null 原样返回 null，空串返回 null，避免未登录落占位值。
     */
    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }

    /**
     * 同时核对 MySQL 重复键错误及约束名，主键冲突不能冒充编码冲突。
     */
    private static boolean isDatasourceKeyConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException) {
                SQLException sql = (SQLException)cause;
                if (sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())
                        && sql.getMessage() != null
                        && sql.getMessage().contains("uk_md_datasource_resource_datasource_key")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 持久化恢复原状态，不生成新编码或重新选址。
     */
    @Override
    public Optional<DatasourceResource> findByDatasourceKey(DatasourceKey datasourceKey) {
        try {
            return read.execute(transaction -> {
                DatasourceRegistrationPo row = mapper.selectByDatasourceKey(datasourceKey.getValue());
                if (row == null) {
                    return Optional.empty();
                }
                return Optional.of(DatasourceResource.restore(new DatasourceKey(row.getDatasourceKey()),
                        row.getDatasourceId(), row.getAppKey(), row.getAppName(), row.getCreatedBy(), row.getCreator(),
                        ResourceStatus.valueOf(row.getStatus()),
                        Integer.valueOf(1).equals(row.getDeleted()), row.getActiveOperationId()));
            });
        } catch (RuntimeException exception) {
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }
}
