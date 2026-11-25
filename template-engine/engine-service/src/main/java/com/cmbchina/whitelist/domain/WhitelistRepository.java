package com.cmbchina.whitelist.domain;

import java.util.Set;

public interface WhitelistRepository {
    void addAll(Set<String> ids);

    boolean contains(String id);
}
