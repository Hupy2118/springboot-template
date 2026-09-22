package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.common.exception.BizException;
import com.cmbchina.backend.common.page.PageParam;
import com.cmbchina.backend.common.page.PageResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemberApplicationServiceTest {

    @Test
    void returnsTwentyStableMockMembersWithPagination() {
        MemberApplicationService service = new MemberApplicationService();

        PageParam query = new PageParam();
        query.setCurrent(2);
        query.setPageSize(5);
        PageResult<MemberDTO> result = service.listMembers(query);

        assertEquals(20, result.getTotal());
        assertEquals(2, result.getCurrent());
        assertEquals(5, result.getPageSize());
        assertEquals(4, result.getTotalPage());
        assertEquals(5, result.getList().size());
        assertEquals("member-006", result.getList().get(0).getMemberId());
        assertEquals("杨静", result.getList().get(0).getMemberName());
        assertEquals("member-010", result.getList().get(4).getMemberId());
        assertEquals("吴倩", result.getList().get(4).getMemberName());
    }

    @Test
    void findsMockMemberByMemberId() {
        MemberDTO member = new MemberApplicationService().getMockMember("member-003");

        assertEquals("member-003", member.getMemberId());
        assertEquals("王芳", member.getMemberName());
    }

    @Test
    void rejectsUnknownMockMember() {
        assertThrows(BizException.class,
                () -> new MemberApplicationService().getMockMember("member-999"));
    }
}
