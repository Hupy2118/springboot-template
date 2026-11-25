package com.cmbchina.managedb.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 isSystem 与 SYSTEM 实例字段的一致性，防止占位串导致未登录落库误判。
 */
class ActorTest {
    /**
     * 空值归一为 system，且字段为 null（落库走 truncate(null) 得 null）。
     */
    @Test
    void systemWhenBothBlank() {
        Actor actor = Actor.of(null, null);
        assertTrue(actor.isSystem());
        assertNull(actor.getUserId());
        assertNull(actor.getUserName());
        assertEquals("system", actor.toString());
        assertSame(Actor.system(), actor);
    }

    /**
     * 纯空白也归一为 system，不落占位值。
     */
    @Test
    void systemOnWhitespaceOnly() {
        Actor actor = Actor.of("   ", "  ");
        assertTrue(actor.isSystem());
        assertNull(actor.getUserId());
        assertNull(actor.getUserName());
    }

    /**
     * 有 id 时为真实用户，name 为个人信息只用于落库不用于日志。
     */
    @Test
    void userWhenIdPresent() {
        Actor actor = Actor.of("123456", "张三");
        assertFalse(actor.isSystem());
        assertEquals("123456", actor.getUserId());
        assertEquals("张三", actor.getUserName());
        assertEquals("user(123456)", actor.toString());
    }

    /**
     * 只有姓名没有 id 不是 system；requestAbandon 的 requestedBy 由服务层回退为 STUDIO_SERVICE。
     */
    @Test
    void nameOnlyIsNotSystem() {
        Actor actor = Actor.of(null, "张三");
        assertFalse(actor.isSystem());
        assertNull(actor.getUserId());
        assertEquals("张三", actor.getUserName());
    }
}
