package com.cmbchina.backend.auth.application.service;

import com.cmbchina.backend.auth.application.dto.MemberDTO;
import com.cmbchina.backend.auth.domain.exception.AuthErrorCode;
import com.cmbchina.backend.common.exception.BizException;
import com.cmbchina.backend.common.page.PageParam;
import com.cmbchina.backend.common.page.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 成员列表临时适配服务。
 *
 * <p>正式成员查询接口接入前，使用固定数据支撑权限管理页面联调；正式接口接入后只需替换本服务实现。</p>
 */
@Service
@RequiredArgsConstructor
public class MemberApplicationService {

    private static final List<MemberDTO> MOCK_MEMBERS = Collections.unmodifiableList(Arrays.asList(
            new MemberDTO("member-001", "张伟"),
            new MemberDTO("member-002", "李娜"),
            new MemberDTO("member-003", "王芳"),
            new MemberDTO("member-004", "刘洋"),
            new MemberDTO("member-005", "陈杰"),
            new MemberDTO("member-006", "杨静"),
            new MemberDTO("member-007", "黄磊"),
            new MemberDTO("member-008", "赵敏"),
            new MemberDTO("member-009", "周涛"),
            new MemberDTO("member-010", "吴倩"),
            new MemberDTO("member-011", "徐强"),
            new MemberDTO("member-012", "孙丽"),
            new MemberDTO("member-013", "胡斌"),
            new MemberDTO("member-014", "朱琳"),
            new MemberDTO("member-015", "高峰"),
            new MemberDTO("member-016", "林雪"),
            new MemberDTO("member-017", "何军"),
            new MemberDTO("member-018", "郭欣"),
            new MemberDTO("member-019", "马超"),
            new MemberDTO("member-020", "郑洁")));

    public PageResult<MemberDTO> listMembers(PageParam query) {
        int safePage = query.getCurrent();
        int safePageSize = query.getPageSize();
        long offset = (long) (safePage - 1) * safePageSize;
        List<MemberDTO> pageItems;
        if (offset >= MOCK_MEMBERS.size()) {
            pageItems = Collections.emptyList();
        } else {
            int fromIndex = (int) offset;
            int toIndex = Math.min(fromIndex + safePageSize, MOCK_MEMBERS.size());
            pageItems = MOCK_MEMBERS.subList(fromIndex, toIndex);
        }
        return PageResult.of(MOCK_MEMBERS.size(), safePage, safePageSize, pageItems, item -> item);
    }

    /**
     * 按成员标识查找固定模拟成员，供本地模拟登录使用。
     */
    public MemberDTO getMockMember(String memberId) {
        return MOCK_MEMBERS.stream()
                .filter(member -> member.getMemberId().equals(memberId))
                .findFirst()
                .orElseThrow(() -> new BizException(AuthErrorCode.MOCK_MEMBER_NOT_FOUND));
    }
}
