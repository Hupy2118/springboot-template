package com.cmbchina.backend.auth.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Resource {
    private Integer id;
    private String key;
    private String name;
    private String description;
    private String origin;
    private String type;
    private String targetResourceRef;
    private LocalDateTime createdAt;
}
