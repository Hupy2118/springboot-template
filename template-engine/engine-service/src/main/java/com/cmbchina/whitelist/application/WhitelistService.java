package com.cmbchina.whitelist.application;

import com.cmbchina.whitelist.domain.WhitelistRepository;
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
    private static final int MAX_ID_LENGTH = 128;

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

    public boolean contains(String id) {
        return repository.contains(id);
    }
}
