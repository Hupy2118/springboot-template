package com.cmbchina.whitelist.infrastructure.config;

import com.cmbchina.whitelist.infrastructure.persistence.mapper.WhitelistMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackageClasses = WhitelistMapper.class)
public class WhitelistPersistenceConfiguration {
}
