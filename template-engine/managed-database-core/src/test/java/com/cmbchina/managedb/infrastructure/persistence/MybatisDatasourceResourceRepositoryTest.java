package com.cmbchina.managedb.infrastructure.persistence;
import com.cmbchina.managedb.domain.Actor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.cmbchina.managedb.domain.DatasourceKeyConflictException;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.cmbchina.managedb.domain.valueobject.DatasourceLoad;
import com.cmbchina.managedb.infrastructure.persistence.mapper.DatasourceRegistrationMapper;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceCountPo;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceRegistrationPo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证事务边界和错误分类，不模拟数据库执行成功证据。
 */
class MybatisDatasourceResourceRepositoryTest {
    private DatasourceRegistrationMapper mapper;
    private PlatformTransactionManager transactions;
    private MybatisDatasourceResourceRepository repository;
    private DatasourceResource resource;

    /**
     * 使用 Spring 真实 TransactionTemplate 和受控事务管理器替身。
     */
    @BeforeEach
    void setUp() {
        // 初始化 Lambda 字段映射，不创建连接或执行 SQL。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(DatasourceRegistrationMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, DatasourceRegistrationPo.class);
        mapper = mock(DatasourceRegistrationMapper.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        IdentifierGenerator identifiers = mock(IdentifierGenerator.class);
        when(identifiers.nextId(any())).thenReturn(100L);
        repository = new MybatisDatasourceResourceRepository(mapper, identifiers, transactions, 10);
        resource = DatasourceResource.register(new DatasourceKey("b0ddb23acc91"), 1L, null, "注册测试", Actor.system());
    }

    /**
     * 统计查询缺少的候选必须补零，并在同一只读事务中完成两项计数。
     */
    @Test
    void includesMountWithNoDirectoryRows() {
        DatasourceCountPo count = new DatasourceCountPo();
        count.setDatasourceId(1L);
        count.setTotal(5L);
        when(mapper.countTables(any())).thenReturn(Collections.singletonList(count));
        when(mapper.countResources(any())).thenReturn(Collections.emptyList());
        List<DatasourceLoad> loads = repository.allocationLoads(Arrays.asList(1L, 2L));
        assertEquals(2, loads.size());
        assertEquals(0L, loads.get(1).getCreatedTableCount());
        assertEquals(2L, DatasourceLoad.select(loads));
        verify(transactions).commit(any());
    }

    /**
     * 明确的 datasource_key 碰撞只能在事务成功回滚后向应用层报告。
     */
    @Test
    void translatesOnlyConfirmedDatasourceKeyDuplicateAfterRollback() {
        when(mapper.insert(any())).thenThrow(duplicate("uk_md_datasource_resource_datasource_key"));
        assertThrows(DatasourceKeyConflictException.class, () -> repository.insert(resource));
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    /**
     * 主键重复不消耗编码重试额度，必须作为系统错误停止。
     */
    @Test
    void doesNotTreatPrimaryKeyCollisionAsDatasourceKeyCollision() {
        when(mapper.insert(any())).thenThrow(duplicate("PRIMARY"));
        assertThrows(BizException.class, () -> repository.insert(resource));
        verify(transactions).rollback(any());
    }

    /**
     * 提交异常可能已经落库，不能转换成允许重试的标识冲突。
     */
    @Test
    void neverRetriesAnUncertainCommit() {
        when(mapper.insert(any())).thenReturn(1);
        doThrow(new TransactionSystemException("提交结果不确定")).when(transactions).commit(any());
        assertThrows(BizException.class, () -> repository.insert(resource));
    }

    /**
     * 即使原始错误是编码重复，回滚失败也不能继续下一次插入。
     */
    @Test
    void neverRetriesWhenRollbackFails() {
        when(mapper.insert(any())).thenThrow(duplicate("uk_md_datasource_resource_datasource_key"));
        doThrow(new TransactionSystemException("回滚失败")).when(transactions).rollback(any());
        assertThrows(BizException.class, () -> repository.insert(resource));
    }

    /**
     * 查重和历史恢复不能因 MyBatis-Plus 自动过滤而丢失软删及已释放记录。
     */
    @Test
    void includesDeletedAndReleasedRecordsWithPlusQueries() {
        DatasourceKey key = new DatasourceKey("b0ddb23acc91");
        when(mapper.countByDatasourceKey(key.getValue())).thenReturn(1L);
        DatasourceRegistrationPo row = new DatasourceRegistrationPo();
        row.setDatasourceKey(key.getValue());
        row.setDatasourceId(1L);
        row.setAppKey("studio-app-1");
        row.setAppName("关联应用");
        row.setStatus("RELEASED");
        row.setDeleted(1);
        when(mapper.selectByDatasourceKey(key.getValue())).thenReturn(row);

        assertTrue(repository.existsIncludingDeleted(key));
        DatasourceResource restored = repository.findByDatasourceKey(key).orElseThrow(AssertionError::new);
        assertEquals(ResourceStatus.RELEASED, restored.getStatus());
        assertTrue(restored.isDeleted());
        assertEquals("studio-app-1", restored.getAppKey());
        assertEquals("关联应用", restored.getAppName());
        // 显式方法不再生成包含 is_deleted / status 的 SQL 片段，直接核对调用编码即可。
        verify(mapper).countByDatasourceKey(eq(key.getValue()));
        verify(mapper).selectByDatasourceKey(eq(key.getValue()));
    }

    /**
     * PO 显式映射真实表与软删列，禁止框架隐藏保留记录或替换统一主键。
     */
    @Test
    void mapsManagementColumnsWithoutAutomaticLogicDeletion() {
        TableInfo table = TableInfoHelper.getTableInfo(DatasourceRegistrationPo.class);
        assertEquals("md_datasource_resource", table.getTableName());
        assertEquals(IdType.INPUT, table.getIdType());
        assertFalse(table.isWithLogicDelete());
        assertTrue(table.getFieldList().stream().anyMatch(field ->
                "deleted".equals(field.getProperty()) && "is_deleted".equals(field.getColumn())));
    }

    /**
     * 插入仍使用统一生成的主键和明确的未删除状态，提交后才能成功。
     */
    @Test
    void insertsWithAssignedIdAndInitialDeletionFlag() {
        when(mapper.insert(any())).thenReturn(1);
        repository.insert(resource);
        ArgumentCaptor<DatasourceRegistrationPo> inserted = ArgumentCaptor.forClass(DatasourceRegistrationPo.class);
        verify(mapper).insert(inserted.capture());
        assertEquals(Long.valueOf(100L), inserted.getValue().getId());
        assertEquals(Integer.valueOf(0), inserted.getValue().getDeleted());
        assertEquals("REGISTERED", inserted.getValue().getStatus());
        assertNull(inserted.getValue().getAppKey());
        assertEquals("注册测试", inserted.getValue().getAppName());
        verify(transactions).commit(any());
    }

    /**
     * 构造 MySQL 重复键错误，测试只依赖错误码和约束名分类。
     */
    private static DuplicateKeyException duplicate(String key) {
        return new DuplicateKeyException("重复键", new SQLException(
                "Duplicate entry for key 'md_datasource_resource." + key + "'", "23000", 1062));
    }
}
