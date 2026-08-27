package com.cmbchina.backend.auth.adapter.web;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.application.service.MemberApplicationService;
import com.cmbchina.backend.auth.common.annotation.RequireAnyResource;
import com.cmbchina.backend.auth.domain.constant.AuthConstants;
import com.cmbchina.backend.common.page.PageParam;
import com.cmbchina.backend.common.page.PageResult;
import com.cmbchina.backend.common.response.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/authorization/members")
public class MemberController {

    private final MemberApplicationService memberService;

    @GetMapping
    public ResponseEntity<PageResult<MemberDTO>> list(@ModelAttribute PageParam query) {
        return ResponseEntity.success(memberService.listMembers(query));
    }

}
