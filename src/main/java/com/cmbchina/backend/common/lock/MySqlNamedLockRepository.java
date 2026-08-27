package com.cmbchina.backend.common.lock;

import com.cmbchina.backend.auth.domain.exception.AuthErrorCode;
import com.cmbchina.backend.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在当前事务连接上持有 MySQL 命名锁，直至事务完成后再释放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MySqlNamedLockRepository implements NamedLockRepository {

    private final NamedLockMapper namedLockMapper;

    @Override
    public void acquire(String lockName) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("MySQL 命名锁必须在事务中使用");
        }
        try {
            Integer result = namedLockMapper.getLock(lockName);
            if (!Integer.valueOf(1).equals(result)) {
                throw new BizException(AuthErrorCode.AUTHORIZATION_WRITE_BUSY);
            }
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BizException(AuthErrorCode.AUTHORIZATION_WRITE_BUSY);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                // 在 MyBatis 会话清理前释放命名锁，确保仍使用当前事务连接。
                return 0;
            }

            @Override
            public void afterCompletion(int status) {
                release(lockName);
            }
        });
    }

    private void release(String lockName) {
        try {
            namedLockMapper.releaseLock(lockName);
        } catch (RuntimeException exception) {
            log.warn("释放权限命名锁失败：{}", lockName);
        }
    }
}
