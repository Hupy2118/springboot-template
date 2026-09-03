package com.cmbchina.backend.auth.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 数据库 role 表持久化对象。 */
@Data
@TableName("role")
public class RolePO {
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;
    private String roleId;
    private String roleName;
    private String description;
    private Boolean enable;
    @TableField("is_system")
    private Boolean isSystem;
    private LocalDateTime createdAt;
    private String createdBy;
    private LocalDateTime updatedAt;
    private String updatedBy;
    @TableField("is_deleted")
    @TableLogic(value = "0", delval = "1")
    private Boolean isDeleted;
    private LocalDateTime deletedAt;
    private String deletedBy;
}
