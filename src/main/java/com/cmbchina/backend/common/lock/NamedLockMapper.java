package com.cmbchina.backend.common.lock;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MySQL 命名锁 SQL 映射，复用当前事务的 MyBatis 会话与连接。
 */
@Mapper
public interface NamedLockMapper {

    Integer getLock(@Param("lockName") String lockName);

    Integer releaseLock(@Param("lockName") String lockName);
}
