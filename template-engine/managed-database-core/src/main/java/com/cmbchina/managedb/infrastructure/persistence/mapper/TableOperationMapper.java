package com.cmbchina.managedb.infrastructure.persistence.mapper;

import com.cmbchina.managedb.infrastructure.persistence.po.TableOperationPo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 操作历史 Mapper，SQL 统一维护在 mybatis/TableOperationMapper.xml。
 * 操作历史使用基础 SQL，状态写入前按固定锁顺序读取。
 */
public interface TableOperationMapper {

    /**
     * 新增一条执行记录，主键由服务生成。
     */
    int insert(TableOperationPo row);

    /**
     * 按主键读取执行记录。
     */
    TableOperationPo selectById(@Param("id") long id);

    /**
     * 按数据源编码和操作 ID 读取历史，不触发重放。
     */
    TableOperationPo selectByDatasourceKeyAndId(@Param("datasourceKey") String datasourceKey,
                                                @Param("id") long id);

    /**
     * 统计应用全部保留操作数。
     */
    long countByDatasourceKey(@Param("datasourceKey") String datasourceKey);

    /**
     * 限定数量的历史只读查询。
     */
    List<TableOperationPo> selectRecentByDatasourceKey(@Param("datasourceKey") String datasourceKey,
                                                       @Param("limit") int limit);

    /**
     * 在 RUNNING 状态下更新执行结果，用于步骤保存。
     */
    int updateResultByIdAndStatus(@Param("id") long id,
                                  @Param("status") String status,
                                  @Param("executionResult") String executionResult);

    /**
     * 在原状态匹配下更新终态及执行结果，用于 finish。
     */
    int updateStatusAndResultByIdAndStatus(@Param("id") long id,
                                           @Param("oldStatus") String oldStatus,
                                           @Param("newStatus") String newStatus,
                                           @Param("executionResult") String executionResult);

    /**
     * 资源锁、目录锁之后锁定当前操作。
     */
    TableOperationPo lock(@Param("datasourceKey") String datasourceKey, @Param("id") long id);
}
