package com.cmbchina.managedb.infrastructure.persistence;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 持久化异常只记录白名单诊断字段，禁止输出异常正文及完整堆栈。
 */
@Slf4j
final class PersistenceDiagnostics {
    /**
     * 工具类不创建实例。
     */
    private PersistenceDiagnostics() {
    }

    /**
     * 阶段由代码常量提供；有限遍历异常链，不能把驱动消息带入日志。
     */
    static void failure(String stage, Long operationId, Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = failure;
        Throwable root = failure;
        SQLException sql = null;
        for (int depth = 0; current != null && depth < 32 && seen.add(current); depth++) {
            root = current;
            if (sql == null && current instanceof SQLException) {
                sql = (SQLException)current;
            }
            current = current.getCause();
        }
        String state = sql == null ? null : sql.getSQLState();
        // SQLState 仅允许标准五位代码，防止非标准驱动注入正文或换行。
        if (state != null && !state.matches("[A-Za-z0-9]{5}")) {
            state = "INVALID_SQL_STATE";
        }
        log.error("持久化诊断 stage={}, operationId={}, exceptionType={}, rootType={}, sqlState={}, vendorCode={}",
                stage, operationId, failure.getClass().getName(), root.getClass().getName(), state, sql == null ? null : sql.getErrorCode());
    }
}
