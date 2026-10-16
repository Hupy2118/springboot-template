package com.cmbchina.backend.auth.adapter.web;

import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmbchina.backend.auth.application.dto.MemberResourcesDTO;
import com.cmbchina.backend.auth.application.dto.ResourceDTO;
import com.cmbchina.backend.auth.application.service.ResourceApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import com.cmbchina.backend.auth.domain.constant.AuthConstants;
import com.cmbchina.backend.common.entity.page.PageParam;
import com.cmbchina.backend.common.entity.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/authorization")
public class ResourceController {

    private final ResourceApplicationService resourceService;

    @GetMapping("/resources")
    @RequireAnyResource(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE)
    public BeeResponseEntity<PageResult<ResourceDTO>> list(@ModelAttribute PageParam query) {
        return BeeResponseEntity.success(resourceService.listResources(query));
    }

    @GetMapping("/resources/{resourceKey}")
    @RequireAnyResource(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE)
    public BeeResponseEntity<ResourceDTO> get(@PathVariable String resourceKey) {
        return BeeResponseEntity.success(resourceService.getResource(resourceKey));
    }

    @GetMapping("/me/resources")
    public BeeResponseEntity<MemberResourcesDTO> getMyResources() {
        return BeeResponseEntity.success(resourceService.getCurrentMemberResources());
    }
}
