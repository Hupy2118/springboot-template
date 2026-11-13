package com.cmbchina.managedb.infrastructure.persistence;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.AuthorizationSeedPayload;
import com.cmbchina.managedb.domain.DataOperation;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.repository.DataOperationRepository;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.mapper.TableBindingMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceRegistrationPo;
import com.cmbchina.managedb.infrastructure.persistence.po.TableBindingPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** 复用 MyBatis 资源及目录锁，Seed 记录使用 JDBC，避免日志捕获敏感绑定参数。 */
public final class MybatisDataOperationRepository implements DataOperationRepository {
    private static final String SUMMARY = "SELECT id,datasource_key,datasource_id,request_fingerprint,status,result_json "
            + "FROM md_data_operation WHERE datasource_key=? AND ";
    private final DatasourceRegistrationMapper resources;
    private final TableBindingMapper tables;
    private final IdentifierGenerator ids;
    private final DataOperationCodec codec;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /** 各阶段使用独立短管理事务，与目标数据库连接完全分离。 */
    public MybatisDataOperationRepository(DatasourceRegistrationMapper resources, TableBindingMapper tables,
                                          IdentifierGenerator ids, DataOperationCodec codec, JdbcTemplate jdbc,
                                          PlatformTransactionManager manager, int timeout) {
        this.resources = resources;
        this.tables = tables;
        this.ids = ids;
        this.codec = codec;
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(timeout);
    }

    /** 预检只读取管理状态，在资源行锁内核对精确目录。 */
    @Override
    public long preflight(AuthorizationSeedPayload payload) {
        return tx("seed.preflight", () -> {
            DatasourceRegistrationPo resource = requireResource(payload.getDatasourceKey(), null);
            requireTables(resource.getDatasourceId(), payload);
            return resource.getDatasourceId();
        });
    }

    /** 幂等优先于资源状态检查，历史重试仍可查询原记录。 */
    @Override
    public Admission begin(long datasourceId, String idempotencyKey, AuthorizationSeedPayload payload) {
        String key = payload.getDatasourceKey();
        return tx("seed.begin", () -> {
            DatasourceRegistrationPo resource = resources.lock(key);
            Optional<DataOperation> existing = find(key, "idempotency_key=? FOR UPDATE", idempotencyKey);
            if (existing.isPresent()) {
                return new Admission(existing.get().requireFingerprint(payload.getFingerprint()), false);
            }
            requireResource(resource, null);
            if (resource.getDatasourceId() != datasourceId) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
            requireTables(datasourceId, payload);
            long id = ids.nextId(null).longValue();
            String stored = codec.payload(payload);
            requireOne(jdbc.update("INSERT INTO md_data_operation "
                    + "(id,datasource_key,datasource_id,purpose,idempotency_key,request_fingerprint,status,payload_json) "
                    + "VALUES (?,?,?,?,?,?,'RUNNING',?)", ps -> {
                ps.setLong(1, id);
                ps.setString(2, key);
                ps.setLong(3, datasourceId);
                ps.setString(4, AuthorizationSeedPayload.PURPOSE);
                ps.setString(5, idempotencyKey);
                ps.setString(6, payload.getFingerprint());
                ps.setString(7, stored);
            }));
            requireOne(resources.claim(key, id));
            return new Admission(new DataOperation(id, key, datasourceId, payload.getFingerprint(),
                    DataOperation.Result.running()), true);
        });
    }

    /** 调度后再次核对持久化状态、占用、挂载和四表归属。 */
    @Override
    public void requireExecution(DataOperation operation, AuthorizationSeedPayload payload) {
        tx("seed.requireExecution", () -> {
            requireCurrent(operation);
            requireTables(operation.getDatasourceId(), payload);
            return null;
        });
    }

    /** 终态和释放必须一起提交；结果未知时始终保留原占用。 */
    @Override
    public void finish(DataOperation operation, DataOperation.Result result) {
        if (result.getStatus() == DataOperation.Status.RUNNING) {
            throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
        }
        tx("seed.finish", () -> {
            requireCurrent(operation);
            requireOne(jdbc.update("UPDATE md_data_operation SET status=?,result_json=?,completed_at=CURRENT_TIMESTAMP "
                            + "WHERE datasource_key=? AND id=? AND status='RUNNING'",
                    result.getStatus().name(), codec.result(result), operation.getDatasourceKey(), operation.getId()));
            if (result.getStatus() != DataOperation.Status.UNKNOWN) {
                requireOne(resources.release(operation.getDatasourceKey(), operation.getId()));
            }
            return null;
        });
    }

    /** 只读投影不访问敏感 JSON 列。 */
    @Override
    public Optional<DataOperation> detail(String datasourceKey, long id) {
        return tx("seed.detail", () -> find(datasourceKey, "id=?", id));
    }

    /** 按幂等键查找原记录，不认领资源或调度任务。 */
    @Override
    public Optional<DataOperation> byKey(String datasourceKey, String idempotencyKey) {
        return tx("seed.byKey", () -> find(datasourceKey, "idempotency_key=?", idempotencyKey));
    }

    /** 查询条件由仓储提供，所有外部值通过 JDBC 参数传入。 */
    private Optional<DataOperation> find(String datasourceKey, String condition, Object value) {
        List<DataOperation> matches = jdbc.query(SUMMARY + condition, (row, index) -> new DataOperation(
                row.getLong("id"), row.getString("datasource_key"), row.getLong("datasource_id"),
                row.getString("request_fingerprint"), codec.restore(row.getString("status"), row.getString("result_json"))),
                datasourceKey, value);
        return matches.stream().findFirst();
    }

    /** 不允许失效资源、挂载变化或过期占用继续执行或释放。 */
    private void requireCurrent(DataOperation operation) {
        String key = operation.getDatasourceKey();
        DatasourceRegistrationPo resource = requireResource(key, operation.getId());
        Optional<DataOperation> current = find(key, "id=? FOR UPDATE", operation.getId());
        if (resource.getDatasourceId() != operation.getDatasourceId() || !current.isPresent()
                || current.get().getDatasourceId() != operation.getDatasourceId()
                || current.get().getResult().getStatus() != DataOperation.Status.RUNNING
                || !current.get().getFingerprint().equals(operation.getFingerprint())) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
    }

    /** 按同一资源锁读取固定挂载和期望占用。 */
    private DatasourceRegistrationPo requireResource(String key, Long expectedOperationId) {
        return requireResource(resources.lock(key), expectedOperationId);
    }

    /** 空占用只用于受理前，执行和完成必须持有原操作占用。 */
    private static DatasourceRegistrationPo requireResource(DatasourceRegistrationPo resource, Long expectedOperationId) {
        if (resource == null || !"REGISTERED".equals(resource.getStatus())
                || !Integer.valueOf(0).equals(resource.getDeleted())) {
            throw new BizException(ResourceErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!Objects.equals(expectedOperationId, resource.getActiveOperationId())) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
        return resource;
    }

    /** 锁定四个精确目录位置，检查有效状态、挂载和归属。 */
    private void requireTables(long datasourceId, AuthorizationSeedPayload payload) {
        for (String name : payload.getTableNames()) {
            TableBindingPo binding = tables.lock(datasourceId, name);
            if (binding == null || !name.equals(binding.getTableName())
                    || !payload.getDatasourceKey().equals(binding.getDatasourceKey())
                    || binding.getDatasourceId() != datasourceId || !"CREATED".equals(binding.getStatus())
                    || !Integer.valueOf(0).equals(binding.getDeleted())) {
                throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
            }
        }
    }

    /** 拒绝意外的条件写入行数，触发整个管理事务回滚。 */
    private static void requireOne(int count) {
        if (count != 1) {
            throw new BizException(ResourceErrorCode.RESOURCE_BUSY);
        }
    }

    /** 持久化错误只记录安全分类，不记录 SQL 异常正文或参数。 */
    private <T> T tx(String stage, Supplier<T> action) {
        try {
            return transaction.execute(state -> action.get());
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            PersistenceDiagnostics.failure(stage, null, exception);
            throw new BizException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }
}
