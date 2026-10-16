package com.cmbchina.backend.auth.adapter.web;

import com.cmb.bee.web.entity.BeeResponseEntity;
import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.application.service.MemberApplicationService;
import com.cmbchina.backend.common.entity.page.PageParam;
import com.cmbchina.backend.common.entity.page.PageResult;
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
    public BeeResponseEntity<PageResult<MemberDTO>> list(@ModelAttribute PageParam query) {
        return BeeResponseEntity.success(memberService.listMembers(query));
    }

}
