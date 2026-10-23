package com.cmb.oa.one.account.oauth.client.login.model;

/** Local compatibility type; contains no enterprise authentication implementation. */
public class LocalToken {
    private final String sapId;
    public LocalToken(String sapId) { this.sapId = sapId; }
    public String getSapId() { return sapId; }
}
