package com.cmbchina.backend.common.lock;

/**
 * 事务级互斥锁端口，基础设施实现负责锁的具体语义。
 */
public interface NamedLockRepository {
    void acquire(String lockName);
}

