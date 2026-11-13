package com.cmbchina.managedb.infrastructure.persistence.mapper;

import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceCountPo;
import com.cmbchina.managedb.infrastructure.persistence.po.DatasourceRegistrationPo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据源资源注册 Mapper，SQL 统一维护在 mybatis/DatasourceRegistrationMapper.xml。
 * 简单读写复用基础 CRUD，跨表及分组统计保留明确 SQL。
 */
public interface DatasourceRegistrationMapper {

    /**
     * 新增一条注册记录，主键由服务生成。
     */
    int insert(DatasourceRegistrationPo row);

    /**
     * 按主键读取注册记录。
     */
    DatasourceRegistrationPo selectById(@Param("id") long id);

    /**
     * 按数据源编码读取注册记录，包含软删除和已释放记录。
     */
    DatasourceRegistrationPo selectByDatasourceKey(@Param("datasourceKey") String datasourceKey);

    /**
     * 按数据源编码统计记录数，查重包含软删除和已释放记录。
     */
    long countByDatasourceKey(@Param("datasourceKey") String datasourceKey);

    /**
     * 执行事务首先锁定应用，恢复完整占用。
     */
    DatasourceRegistrationPo lock(@Param("datasourceKey") String datasourceKey);

    /**
     * 只认领有效且无占用的应用。
     */
    int claim(@Param("datasourceKey") String datasourceKey, @Param("operationId") long operationId);

    /**
     * 只释放本操作占用，UNKNOWN 不调用。
     */
    int release(@Param("datasourceKey") String datasourceKey, @Param("operationId") long operationId);

    /**
     * 回收成功与当前占用释放原子提交，保留资源和归属历史。
     */
    int completeRelease(@Param("datasourceKey") String datasourceKey, @Param("operationId") long operationId);

    /**
     * 统计最近确认建成的表，软删除不意味着物理表已经删除。
     */
    List<DatasourceCountPo> countTables(@Param("ids") List<Long> ids);

    /**
     * 有效资源数只用于表数相同时处理平局。
     */
    List<DatasourceCountPo> countResources(@Param("ids") List<Long> ids);
}
