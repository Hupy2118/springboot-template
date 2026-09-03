package com.cmbchina.backend.auth.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 数据库 resource 表持久化对象。 */
@Data
@TableName("resource")
public class ResourcePO {
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @TableField("`key`")
    private String key;
    private String name;
    private String description;
    private String origin;
    private String type;
    private String targetResourceRef;
    private LocalDateTime createdAt;
}

