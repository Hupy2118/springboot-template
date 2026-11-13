package com.cmbchina.managedb.infrastructure.persistence.mapper;

import com.cmbchina.managedb.infrastructure.persistence.po.TableBindingPo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 表目录 Mapper，SQL 统一维护在 mybatis/TableBindingMapper.xml。
 * 表目录 CRUD 复用基础 SQL，位置行锁保留必要 SQL。
 */
public interface TableBindingMapper {

    /**
     * 新增一条表目录记录，主键由服务生成。
     */
    int insert(TableBindingPo row);

    /**
     * 按主键读取表目录记录。
     */
    TableBindingPo selectById(@Param("id") long id);

    /**
     * 读取应用全部目录，包含软删除记录。
     */
    List<TableBindingPo> selectByDatasourceKey(@Param("datasourceKey") String datasourceKey);

    /**
     * 读取应用有效目录，不包含软删除记录。
     */
    List<TableBindingPo> selectActiveByDatasourceKey(@Param("datasourceKey") String datasourceKey);

    /**
     * 精确位置查询不隐式过滤软删除。
     */
    TableBindingPo selectByDatasourceIdAndTableName(@Param("datasourceId") long datasourceId,
                                                    @Param("tableName") String tableName);

    /**
     * 按主键更新表状态。
     */
    int updateStatusById(@Param("id") long id, @Param("status") String status);

    /**
     * 在资源锁之后锁定精确位置，包含软删除记录。
     */
    TableBindingPo lock(@Param("datasourceId") long datasourceId, @Param("tableName") String tableName);
}
