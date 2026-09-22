package com.cmbchina.backend.auth.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 当前登录人上下文。
 *
 * <p>类名保留项目约定的 Hodler 拼写；请求完成后必须调用 {@link #clear()}。</p>
 */
public final class BaseUserDataThreadHodler {

    private static final TransmittableThreadLocal<BaseUserData> USER_DATA = new TransmittableThreadLocal<>();

    private BaseUserDataThreadHodler() {
    }

    public static void set(BaseUserData userData) {
        USER_DATA.set(userData);
    }

    public static BaseUserData get() {
        return USER_DATA.get();
    }

    public static void clear() {
        USER_DATA.remove();
    }
}
