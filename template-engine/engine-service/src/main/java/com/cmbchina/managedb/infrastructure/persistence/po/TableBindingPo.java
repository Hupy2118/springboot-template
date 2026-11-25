package com.cmbchina.managedb.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 管理表持久化行；保留记录由查询显式处理，不启用 TableLogic。
 */
@Getter
@Setter
@TableName("md_datasource_table")
public class TableBindingPo {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String datasourceKey;
    private Long datasourceId;
    private String tableName;
    private String status;
    private String createdBy;
    private String creator;
    @TableField("is_deleted")
    private Integer deleted;
}
