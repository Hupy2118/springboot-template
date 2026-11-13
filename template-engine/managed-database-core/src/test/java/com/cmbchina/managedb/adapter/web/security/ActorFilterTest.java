package com.cmbchina.managedb.adapter.web.security;

import com.cmbchina.managedb.domain.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证身份头解析：存在则解码为 Actor，缺失或非法降级为 system，不中断请求。
 */
class ActorFilterTest {
    private final ActorFilter filter = new ActorFilter();

    /**
     * 带用户头时解码为真实用户，中文姓名按 UTF-8 还原。
     */
    @Test
    void decodesUserHeaders() throws Exception {
        MockHttpServletRequest request = managedRequest();
        request.addHeader("X-Managed-User-Id", URLEncoder.encode("123456", StandardCharsets.UTF_8.name()));
        request.addHeader("X-Managed-User-Name", URLEncoder.encode("张三", StandardCharsets.UTF_8.name()));
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        Actor actor = (Actor) request.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        assertEquals("123456", actor.getUserId());
        assertEquals("张三", actor.getUserName());
    }

    /**
     * 头缺失时落 system，字段为 null，请求继续。
     */
    @Test
    void systemWhenHeadersMissing() throws Exception {
        MockHttpServletRequest request = managedRequest();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        Actor actor = (Actor) request.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        assertTrue(actor.isSystem());
        assertNull(actor.getUserId());
        assertNull(actor.getUserName());
    }

    /**
     * 非法百分号编码原样返回，不抛异常中断请求。
     */
    @Test
    void keepsRawValueOnInvalidEncoding() throws Exception {
        MockHttpServletRequest request = managedRequest();
        request.addHeader("X-Managed-User-Id", "123%zz");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        Actor actor = (Actor) request.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        assertEquals("123%zz", actor.getUserId());
    }

    /**
     * 非 /managed-database/ 路径不解析身份属性。
     */
    @Test
    void skipsNonManagedPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/actuator/health");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertNull(request.getAttribute(ActorFilter.ACTOR_ATTRIBUTE));
    }

    private static MockHttpServletRequest managedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/managed-database/datasources");
        return request;
    }
}
