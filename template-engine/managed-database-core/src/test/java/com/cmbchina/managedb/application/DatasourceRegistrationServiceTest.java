package com.cmbchina.managedb.application;

import com.cmbchina.managedb.domain.Actor;
import com.cmbchina.managedb.application.view.DatasourceResourceView;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.DatasourceKeyConflictException;
import com.cmbchina.managedb.domain.DatasourceKeyGenerator;
import com.cmbchina.managedb.domain.DatasourceResource;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.repository.DatasourceResourceRepository;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import com.cmbchina.managedb.domain.valueobject.DatasourceLoad;
import com.cmbchina.managedb.infrastructure.identity.HashDatasourceKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 注册用例测试不连接数据库，覆盖选址边界与冲突重试安全性。
 */
class DatasourceRegistrationServiceTest {
    private DatasourceResourceRepository repository;
    private DatabaseSchemaPort schemas;
    private DatasourceKeyGenerator generator;
    private DatasourceRegistrationService service;

    /**
     * 提供真实用例和独立的能力替身，避免启动 Spring 或目标连接池。
     */
    @BeforeEach
    void setUp() {
        repository = mock(DatasourceResourceRepository.class);
        schemas = mock(DatabaseSchemaPort.class);
        generator = mock(DatasourceKeyGenerator.class);
        service = new DatasourceRegistrationService(repository, schemas, generator);
        when(schemas.availableDatasourceIds()).thenReturn(Arrays.asList(1L, 2L));
        when(repository.allocationLoads(any())).thenReturn(Arrays.asList(
                new DatasourceLoad(1L, 12L, 3L), new DatasourceLoad(2L, 5L, 20L)));
    }

    /**
     * 表数优先于应用数，并返回数据库提交后的注册投影。
     */
    @Test
    void selectsFewestTablesAndCreates() {
        when(generator.generate()).thenReturn(new DatasourceKey("db0ddb23acc9"));
        DatasourceResourceView result = service.register(null, "注册测试", Actor.system());
        assertEquals("db0ddb23acc9", result.getDatasourceKey());
        assertEquals(2L, result.getDatasourceId());
        assertEquals("REGISTERED", result.getStatus());
        assertFalse(result.getIsDeleted());
        verify(schemas).verifyAccessible(2L);
        ArgumentCaptor<DatasourceResource> saved = ArgumentCaptor.forClass(DatasourceResource.class);
        verify(repository).insert(saved.capture());
        assertNull(saved.getValue().getAppKey());
        assertEquals("注册测试", saved.getValue().getAppName());
    }

    /**
     * 一级平局比较应用数，二级平局按数值 ID，零表候选同样有效。
     */
    @Test
    void resolvesTiesAndIncludesZeroLoads() {
        assertEquals(2L, DatasourceLoad.select(Arrays.asList(
                new DatasourceLoad(1L, 0L, 9L), new DatasourceLoad(2L, 0L, 1L))));
        assertEquals(2L, DatasourceLoad.select(Arrays.asList(
                new DatasourceLoad(10L, 0L, 1L), new DatasourceLoad(2L, 0L, 1L))));
    }

    /**
     * 查重命中和并发插入碰撞共用尝试额度，且不重复选择挂载。
     */
    @Test
    void retriesLookupAndInsertCollisionsWithoutReallocating() {
        DatasourceKey first = new DatasourceKey("d00000000001");
        DatasourceKey second = new DatasourceKey("d00000000002");
        DatasourceKey third = new DatasourceKey("d00000000003");
        when(generator.generate()).thenReturn(first, second, third);
        when(repository.existsIncludingDeleted(first)).thenReturn(true);
        doThrow(new DatasourceKeyConflictException()).doNothing().when(repository).insert(any());
        assertEquals(third.getValue(), service.register(null, "注册测试", Actor.system()).getDatasourceKey());
        verify(generator, times(3)).generate();
        verify(repository, times(1)).allocationLoads(any());
        verify(schemas, times(1)).verifyAccessible(2L);
        ArgumentCaptor<DatasourceResource> attempts = ArgumentCaptor.forClass(DatasourceResource.class);
        verify(repository, times(2)).insert(attempts.capture());
        assertTrue(attempts.getAllValues().stream().allMatch(row -> row.getDatasourceId() == 2L));
    }

    /**
     * 四次候选用尽后立即报错，不能无限重试或返回碰撞记录。
     */
    @Test
    void stopsAfterFourConflictingCandidates() {
        when(generator.generate()).thenReturn(new DatasourceKey("d00000000001"));
        when(repository.existsIncludingDeleted(any())).thenReturn(true);
        BizException error = assertThrows(BizException.class, () -> service.register(null, "注册测试", Actor.system()));
        assertEquals(ResourceErrorCode.DATASOURCE_KEY_GENERATION_FAILED, error.getErrorCode());
        verify(generator, times(4)).generate();
        verify(repository, never()).insert(any());
    }

    /**
     * 未知写入结果禁止换编码重试，避免同一次创建落下多个应用。
     */
    @Test
    void neverRetriesUncertainWrites() {
        when(generator.generate()).thenReturn(new DatasourceKey("d00000000001"));
        doThrow(new BizException(ResourceErrorCode.INTERNAL_ERROR)).when(repository).insert(any());
        assertThrows(BizException.class, () -> service.register(null, "注册测试", Actor.system()));
        verify(generator, times(1)).generate();
        verify(repository, times(1)).insert(any());
    }

    /**
     * 无候选或挂载连接失败时不生成编码、不写入管理记录。
     */
    @Test
    void rejectsUnavailableMountsBeforeWriting() {
        when(schemas.availableDatasourceIds()).thenReturn(Collections.emptyList());
        assertThrows(BizException.class, () -> service.register(null, "注册测试", Actor.system()));
        verifyNoInteractions(generator, repository);
        when(schemas.availableDatasourceIds()).thenReturn(Arrays.asList(1L, 2L));
        doThrow(new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE)).when(schemas).verifyAccessible(2L);
        assertThrows(BizException.class, () -> service.register(null, "注册测试", Actor.system()));
        verifyNoInteractions(generator);
        verify(repository, never()).insert(any());
    }

    /**
     * 提供正式应用 Key 时原样关联，名称相同不合并不同资源。
     */
    @Test
    void preservesApplicationIdentityWithoutUsingNameAsKey() {
        when(generator.generate()).thenReturn(new DatasourceKey("d00000000001"), new DatasourceKey("d00000000002"));
        assertNotEquals(service.register("studio-application-1", "同名应用", Actor.system()).getDatasourceKey(),
                service.register(null, "同名应用", Actor.system()).getDatasourceKey());
        ArgumentCaptor<DatasourceResource> saved = ArgumentCaptor.forClass(DatasourceResource.class);
        verify(repository, times(2)).insert(saved.capture());
        assertEquals("studio-application-1", saved.getAllValues().get(0).getAppKey());
        assertNull(saved.getAllValues().get(1).getAppKey());
        assertEquals("同名应用", saved.getAllValues().get(1).getAppName());
    }

    /**
     * 非法关联参数在选址和持久化前拒绝，不产生半注册资源。
     */
    @Test
    void rejectsInvalidApplicationMetadataBeforeSelectingMount() {
        assertThrows(BizException.class, () -> service.register(null, null, Actor.system()));
        assertThrows(BizException.class, () -> service.register(" ", "有效名称", Actor.system()));
        assertThrows(BizException.class, () -> service.register(null, "", Actor.system()));
        verifyNoInteractions(repository, schemas, generator);
    }

    /**
     * 真实生成器必须返回 d 前缀且保留前导零能力的十二位编码（d 加十一位十六进制）。
     */
    @Test
    void generatesTwelveCharactersWithLetterPrefix() {
        String key = new HashDatasourceKeyGenerator().generate().getValue();
        assertTrue(key.matches("d[0-9a-f]{11}"));
        assertTrue(key.matches("[0-9a-f]{12}"));
    }
}
