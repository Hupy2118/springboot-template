package com.cmbchina.managedb.adapter.web.application;

import com.cmb.bee.web.annotations.BeeResponseBody;
import com.cmbchina.managedb.adapter.web.request.DatasourceRegistrationRequest;
import com.cmbchina.managedb.adapter.web.security.ActorFilter;
import com.cmbchina.managedb.application.DatasourceRegistration;
import com.cmbchina.managedb.application.view.DatasourceResourceView;
import com.cmbchina.managedb.domain.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * SaaS 普通同步注册入口，datasourceKey 仅由服务生成。
 */
@RestController
@RequiredArgsConstructor
@BeeResponseBody
public class DatasourceRegistrationController {
    private final DatasourceRegistration registration;

    /**
     * 接受应用关联信息，事务提交后仍返回原资源绑定合同。
     *
     * <p>Bee 框架的 @BeeResponseBody 返回值处理器在写入响应体时会 flush
     * 并 commit 响应，@ResponseStatus 设置的状态码在 commit 后 apply 而失效，
     * 因此实际返回 HTTP 200；客户端以 200 判断成功。
     */
    @PostMapping("/managed-database/datasources")
    public DatasourceResourceView register(@RequestBody JsonNode request, HttpServletRequest httpRequest) {
        DatasourceRegistrationRequest input = DatasourceRegistrationRequest.parse(request);
        Actor actor = (Actor) httpRequest.getAttribute(ActorFilter.ACTOR_ATTRIBUTE);
        return registration.register(input.getAppKey(), input.getAppName(), actor == null ? Actor.system() : actor);
    }
}
