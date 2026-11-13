package com.cmbchina.managedb.application;
import com.cmbchina.managedb.domain.Actor;

import com.cmbchina.managedb.application.view.RuntimeConnectionView;
import com.cmbchina.managedb.domain.CheckContext;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceStatus;
import com.cmbchina.managedb.domain.RuntimeConnectionPort;
import com.cmbchina.managedb.domain.RuntimeDatabaseConnection;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 不连接真实数据库，核对资源身份、占用及读取期间变化。
 */
class DatasourceRuntimeConnectionServiceTest {
    private static final String APP = "12ab34cd56ef";
    private TableOperationRepository repository;
    private DatabaseSchemaPort schemas;
    private RuntimeConnectionPort connections;
    private DatasourceResource resource;
    private DatasourceRuntimeConnectionService service;

    /**
     * 建立无占用应用及独立调试连接端口。
     */
    @BeforeEach
    void setUp() {
        repository = mock(TableOperationRepository.class);
        schemas = mock(DatabaseSchemaPort.class);
        connections = mock(RuntimeConnectionPort.class);
        resource = DatasourceResource.register(new DatasourceKey(APP), 2L, null, "注册测试", Actor.system());
        when(repository.checkContext(APP)).thenReturn(new CheckContext(resource, 0L));
        service = new DatasourceRuntimeConnectionService(repository, schemas, connections);
    }

    /**
     * 只按服务端应用挂载取连接，结果身份不能由客户端指定。
     */
    @Test
    void resolvesFixedApplicationMount() {
        when(connections.resolve(2L)).thenReturn(
                new RuntimeDatabaseConnection("localhost", 3306, "inner2", "demo", "test-password", "VERIFY_IDENTITY"));
        RuntimeConnectionView result = service.resolve(APP);
        assertEquals(APP, result.getDatasourceKey());
        assertEquals(2L, result.getDatasourceId());
        assertEquals("inner2", result.getDatabase());
        assertEquals("demo", result.getUsername());
        verify(schemas).verifyAccessible(2L);
        verify(repository, times(2)).checkContext(APP);
    }

    /**
     * 未完成操作必须在读取配置之前拒绝。
     */
    @Test
    void refusesBusyApplication() {
        resource.claim(10L);
        assertThrows(BizException.class, () -> service.resolve(APP));
        verifyNoInteractions(connections, schemas);
    }

    /**
     * 取密期间操作数变化时不能返回本次连接。
     */
    @Test
    void refusesChangedOperationSnapshot() {
        when(connections.resolve(2L)).thenReturn(
                new RuntimeDatabaseConnection("localhost", 3306, "inner2", "demo", "test-password", "VERIFY_IDENTITY"));
        when(repository.checkContext(APP)).thenReturn(new CheckContext(resource, 0L), new CheckContext(resource, 1L));
        assertThrows(BizException.class, () -> service.resolve(APP));
    }

    /**
     * 释放状态不能获得凭据。
     */
    @Test
    void refusesReleasedApplication() {
        when(repository.checkContext(APP)).thenReturn(
                new CheckContext(DatasourceResource.restore(new DatasourceKey(APP), 2L, null, null, null, null, ResourceStatus.RELEASED, false, null), 0L));
        assertThrows(BizException.class, () -> service.resolve(APP));
        verifyNoInteractions(connections, schemas);
    }
}
