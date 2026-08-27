INSERT INTO `resource` (
    `key`, `name`, `description`, `origin`, `type`, `target_resource_ref`, `created_at`
) VALUES (
    'system_authorization_management',
    '权限管理',
    '访问并维护角色、资源授权和成员角色关系',
    'system',
    'system',
    'authorization-api.v2#management-control-plane',
    CURRENT_TIMESTAMP
);

INSERT INTO `role` (
    `role_id`, `role_name`, `description`, `enable`, `is_system`,
    `created_at`, `created_by`, `is_deleted`
) VALUES (
    'SYSTEM_ADMIN',
    '系统管理员',
    '内置权限系统管理员',
    1,
    1,
    CURRENT_TIMESTAMP,
    'SYSTEM',
    0
);

INSERT INTO `role_resource` (
    `role_id`, `resource_key`, `created_at`, `created_by`
) VALUES (
    'SYSTEM_ADMIN',
    'system_authorization_management',
    CURRENT_TIMESTAMP,
    'SYSTEM'
);

INSERT INTO `role_member` (
    `role_id`, `member_id`, `member_name`, `created_at`, `created_by`
) VALUES (
    'SYSTEM_ADMIN',
    'member-001',
    '张伟',
    CURRENT_TIMESTAMP,
    'SYSTEM'
);
