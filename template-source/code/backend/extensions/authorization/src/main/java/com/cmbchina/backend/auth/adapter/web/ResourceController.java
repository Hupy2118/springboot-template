package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.dto.MemberResourcesDTO;
import com.cmbchina.backend.auth.application.dto.ResourceDTO;
import com.cmbchina.backend.auth.application.service.ResourceApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import com.cmbchina.backend.auth.domain.constant.AuthConstants;
import com.cmbchina.backend.common.page.PageParam;
import com.cmbchina.backend.common.page.PageResult;
import com.cmbchina.backend.common.response.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/authorization")
public class ResourceController {

    private final ResourceApplicationService resourceService;

    @GetMapping("/resources")
    @RequireAnyResource(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE)
    public ResponseEntity<PageResult<ResourceDTO>> list(@ModelAttribute PageParam query) {
        return ResponseEntity.success(resourceService.listResources(query));
    }

    @GetMapping("/resources/{resourceKey}")
    @RequireAnyResource(AuthConstants.SYSTEM_MANAGEMENT_RESOURCE)
    public ResponseEntity<ResourceDTO> get(@PathVariable String resourceKey) {
        return ResponseEntity.success(resourceService.getResource(resourceKey));
    }

    @GetMapping("/me/resources")
    public ResponseEntity<MemberResourcesDTO> getMyResources() {
        return ResponseEntity.success(resourceService.getCurrentMemberResources());
    }
}
