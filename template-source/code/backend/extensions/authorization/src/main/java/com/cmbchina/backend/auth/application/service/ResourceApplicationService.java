package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.application.assembler.ResourceAssembler;
import com.cmbchina.backend.auth.application.dto.MemberResourcesDTO;
import com.cmbchina.backend.auth.application.dto.ResourceDTO;
import com.cmbchina.backend.auth.common.context.BaseUserData;
import com.cmbchina.backend.auth.common.context.BaseUserDataThreadHodler;
import com.cmbchina.backend.auth.domain.entity.Resource;
import com.cmbchina.backend.auth.domain.exception.AuthErrorCode;
import com.cmbchina.backend.auth.domain.repository.ResourceRepository;
import com.cmbchina.backend.auth.domain.repository.RoleResourceRepository;
import com.cmbchina.backend.common.exception.BizException;
import com.cmbchina.backend.common.page.PageParam;
import com.cmbchina.backend.common.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 负责只读资源目录查询。
 */
@Service
@RequiredArgsConstructor
public class ResourceApplicationService {

    private final ResourceRepository resourceRepository;
    private final RoleResourceRepository roleResourceRepository;
    private final ResourceAssembler resourceAssembler;

    public PageResult<ResourceDTO> listResources(PageParam query) {
        int safeCurrent = query.getCurrent();
        int safePageSize = query.getPageSize();
        return PageResult.convert(resourceRepository.page(safeCurrent, safePageSize), resourceAssembler::toDTO);
    }

    public ResourceDTO getResource(String resourceKey) {
        Resource resource = resourceRepository.findByKey(resourceKey);
        if (resource == null) {
            throw new BizException(AuthErrorCode.RESOURCE_NOT_FOUND);
        }
        return resourceAssembler.toDTO(resource);
    }

    public MemberResourcesDTO getMemberResources(String memberId) {
        return new MemberResourcesDTO(memberId, roleResourceRepository.findEffectiveResourceKeys(memberId));
    }

    public MemberResourcesDTO getCurrentMemberResources() {
        BaseUserData userData = BaseUserDataThreadHodler.get();
        if (userData == null) {
            throw new BizException(AuthErrorCode.UNAUTHENTICATED);
        }
        return getMemberResources(userData.getMemberId());
    }

}
