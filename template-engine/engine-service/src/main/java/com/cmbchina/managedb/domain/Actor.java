package com.cmbchina.managedb.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 发起操作的最终用户身份，由 Studio 网关注入，服务只记录不校验。
 * 未登录时为 system()，落库 created_by/creator 为 null。
 */
@Getter
@EqualsAndHashCode
public final class Actor {
    /** system 的 userId/userName 必须为 null，保证 isSystem() 与落库 null 语义一致。 */
    private static final Actor SYSTEM = new Actor(null, null);
    private final String userId;
    private final String userName;

    private Actor(String userId, String userName) {
        this.userId = userId;
        this.userName = userName;
    }

    /**
     * 从已解码的请求头构造；空值归一为 system。
     */
    public static Actor of(String userId, String userName) {
        if (isBlank(userId) && isBlank(userName)) {
            return SYSTEM;
        }
        return new Actor(trimToNull(userId), trimToNull(userName));
    }

    /**
     * 未登录或身份缺失时的服务身份。
     */
    public static Actor system() {
        return SYSTEM;
    }

    /**
     * 是否为服务身份（无最终用户）。
     */
    public boolean isSystem() {
        return userId == null && userName == null;
    }

    /**
     * 日志脱敏：system 打 "system"，用户只打 id 不打 name（name 是个人信息）。
     */
    @Override
    public String toString() {
        return isSystem() ? "system" : "user(" + userId + ")";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
