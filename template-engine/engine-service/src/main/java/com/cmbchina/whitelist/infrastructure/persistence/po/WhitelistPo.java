package com.cmbchina.whitelist.infrastructure.persistence.po;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class WhitelistPo {
    private Long id;
    private String memberId;
    private LocalDateTime createdAt;
}
