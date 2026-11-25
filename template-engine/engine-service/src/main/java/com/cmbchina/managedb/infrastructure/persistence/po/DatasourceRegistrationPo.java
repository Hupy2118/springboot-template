package com.cmbchina.managedb.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 注册持久化行；不启用自动逻辑删除过滤，查重和历史恢复需要读取保留记录。
 */
@Getter
@Setter
@TableName("md_datasource_resource")
public class DatasourceRegistrationPo {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String datasourceKey;
    private String appKey;
    private String appName;
    private Long datasourceId;
    private String status;
    private Long activeOperationId;
    private String createdBy;
    private String creator;
    @TableField("is_deleted")
    private Integer deleted;
}
