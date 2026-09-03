package com.cmbchina.backend.auth.infrastructure.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 数据库 role_member 表持久化对象。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("role_member")
public class RoleMemberPO {
    private String roleId;
    private String memberId;
    private String memberName;
    private LocalDateTime createdAt;
    private String createdBy;
}

