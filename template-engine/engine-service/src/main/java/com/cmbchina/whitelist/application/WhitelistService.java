package com.cmbchina.whitelist.application;

import com.cmbchina.common.exception.ServiceException;
import com.cmbchina.common.util.JwtUtils;
import com.cmbchina.whitelist.domain.WhitelistRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WhitelistService {
    private static final String BEARER = "Bearer ";
    private static final String JWT_SK = "devagentstudio ";

    private final WhitelistRepository repository;

    public void add(List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        Set<String> uniqueIds = new HashSet<>();
        for (String id : ids) {
            if (StringUtils.hasLength(id)) {
                uniqueIds.add(id);
            }
        }
        repository.addAll(uniqueIds);
    }

    public boolean check(List<String> authorization) {
        String header = authorization != null && authorization.size() == 1 ? authorization.get(0) : null;
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw invalidToken();
        }

        JsonNode claims;
        try {
            claims = JwtUtils.verifyHs256(header.substring(BEARER.length()).trim(), JWT_SK);
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        } catch (IllegalStateException exception) {
            throw new ServiceException("WHITELIST_UNAVAILABLE", "whitelist JWT verification is unavailable", 503);
        }
        String id = claims.path("id").textValue();
        return repository.contains(id);
    }

    private static ServiceException invalidToken() {
        return new ServiceException("BAD_REQUEST", "a valid Bearer token is required", 400);
    }
}
