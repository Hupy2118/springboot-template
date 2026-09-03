package com.cmbchina.backend.auth.infrastructure.mapper;

import com.cmbchina.backend.auth.infrastructure.po.ResourcePO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceMapper extends BaseMapper<ResourcePO> {
}

