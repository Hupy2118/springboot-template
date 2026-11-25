package com.cmbchina.whitelist.infrastructure.persistence;

import com.cmbchina.common.exception.ServiceException;
import com.cmbchina.whitelist.domain.WhitelistRepository;
import com.cmbchina.whitelist.infrastructure.persistence.mapper.WhitelistMapper;
import com.cmbchina.whitelist.infrastructure.persistence.po.WhitelistPo;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class MybatisWhitelistRepository implements WhitelistRepository {
    private final WhitelistMapper mapper;

    @Override
    public void addAll(Set<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<WhitelistPo> rows = ids.stream().sorted()
                .map(id -> {
                    WhitelistPo row = new WhitelistPo();
                    row.setMemberId(id);
                    return row;
                })
                .collect(Collectors.toList());
        try {
            mapper.insertAll(rows);
        } catch (DataAccessException | PersistenceException exception) {
            throw unavailable();
        }
    }

    @Override
    public boolean contains(String id) {
        try {
            return mapper.selectByMemberId(id) != null;
        } catch (DataAccessException | PersistenceException exception) {
            throw unavailable();
        }
    }

    private static ServiceException unavailable() {
        return new ServiceException("WHITELIST_UNAVAILABLE", "whitelist storage is unavailable", 503);
    }
}
