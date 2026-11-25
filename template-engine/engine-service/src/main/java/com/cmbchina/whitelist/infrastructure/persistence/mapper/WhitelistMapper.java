package com.cmbchina.whitelist.infrastructure.persistence.mapper;

import com.cmbchina.whitelist.infrastructure.persistence.po.WhitelistPo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WhitelistMapper {
    int insertAll(@Param("rows") List<WhitelistPo> rows);

    WhitelistPo selectByMemberId(@Param("memberId") String memberId);
}
