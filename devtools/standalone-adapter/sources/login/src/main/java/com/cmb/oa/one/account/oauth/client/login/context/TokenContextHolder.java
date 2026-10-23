package com.cmb.oa.one.account.oauth.client.login.context;

import com.cmb.oa.one.account.oauth.client.login.model.LocalToken;

/** Synchronous request scope; never inherited by background or pooled threads. */
public final class TokenContextHolder {
    private static final ThreadLocal<LocalToken> CURRENT = new ThreadLocal<LocalToken>();
    private TokenContextHolder() { }
    public static LocalToken get() { return CURRENT.get(); }
    public static void set(LocalToken token) { if (token == null) clear(); else CURRENT.set(token); }
    public static void clear() { CURRENT.remove(); }
}
