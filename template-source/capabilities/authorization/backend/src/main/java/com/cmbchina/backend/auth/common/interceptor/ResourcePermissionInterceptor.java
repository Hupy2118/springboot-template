package com.cmbchina.backend.auth.common.interceptor;

import com.cmbchina.backend.auth.application.dto.MemberResourcesDTO;
import com.cmbchina.backend.auth.application.service.ResourceApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import com.cmbchina.backend.auth.domain.exception.AuthErrorCode;
import com.cmbchina.backend.common.exception.BizException;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 执行 {@link RequireAnyResource} 声明的任一资源点校验。
 */
@Component
public class ResourcePermissionInterceptor implements HandlerInterceptor {

    private final ResourceApplicationService resourceService;

    public ResourcePermissionInterceptor(ResourceApplicationService resourceService) {
        this.resourceService = resourceService;
    }

    @Override
    public boolean preHandle(@NotNull HttpServletRequest request,
                             @NotNull HttpServletResponse response,
                             @NotNull Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireAnyResource permission = findPermission(handlerMethod);
        if (permission == null) {
            return true;
        }

        BaseUserData currentUser = BaseUserDataThreadHodler.get();
        if (currentUser == null) {
            throw new BizException(AuthErrorCode.UNAUTHENTICATED);
        }

        MemberResourcesDTO memberResources = resourceService.getMemberResources(currentUser.getMemberId());
        List<String> resourceKeys = memberResources == null ? null : memberResources.getResourceKeys();
        Set<String> ownedResources = resourceKeys == null
                ? Collections.emptySet() : new HashSet<>(resourceKeys);
        for (String requiredResource : permission.value()) {
            if (StringUtils.hasText(requiredResource) && ownedResources.contains(requiredResource)) {
                return true;
            }
        }
        throw new BizException(AuthErrorCode.FORBIDDEN);
    }

    private RequireAnyResource findPermission(HandlerMethod handlerMethod) {
        RequireAnyResource methodPermission = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequireAnyResource.class);
        if (methodPermission != null) {
            return methodPermission;
        }
        return AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), RequireAnyResource.class);
    }
}
