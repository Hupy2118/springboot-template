package com.cmbchina.backend.auth.common.context;

import lombok.Getter;

import java.util.Objects;

/**
 * 当前请求中的基础登录人信息。
 */
@Getter
public class BaseUserData {

    private final String memberId;
    private final String memberName;

    public BaseUserData(String memberId, String memberName) {
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.memberName = Objects.requireNonNull(memberName, "memberName");
    }
}
