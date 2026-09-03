CREATE TABLE `role` (
                        `id` int NOT NULL AUTO_INCREMENT COMMENT '自增id标志',
                        `role_id` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '角色id',
                        `role_name` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '角色名称',
                        `description` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '说明描述',
                        `enable` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
                        `is_system` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否是系统角色',
                        `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        `created_by` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
                        `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        `updated_by` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '更新人',
                        `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否删除',
                        `deleted_at` timestamp NULL DEFAULT NULL COMMENT '删除时间',
                        `deleted_by` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '删除人',
                        PRIMARY KEY (`id`),
                        UNIQUE KEY `role_id_unique` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='角色表';

CREATE TABLE `resource` (
                            `id` int NOT NULL AUTO_INCREMENT COMMENT '自增id标志',
                            `key` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '资源点key',
                            `name` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '资源点名称',
                            `description` varchar(128) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '说明描述',
                            `origin` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '资源点来源：system/business',
                            `type` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '资源点类型：system/page/operation',
                            `target_resource_ref` varchar(255) COLLATE utf8mb4_bin DEFAULT NULL COMMENT 'page、action 或系统控制面引用',
                            `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                            PRIMARY KEY (`id`),
                            UNIQUE KEY `resource_key_unique` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='资源点';

CREATE TABLE `role_resource` (
                                 `role_id` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '角色id',
                                 `resource_key` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '资源点key',
                                 `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                 `created_by` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
                                 UNIQUE KEY `role_resource_unique` (`role_id`,`resource_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='角色资源点';

CREATE TABLE `role_member` (
                               `role_id` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '角色id',
                               `member_id` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '成员id',
                               `member_name` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '成员名称',
                               `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                               `created_by` varchar(32) COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
                               UNIQUE KEY `role_member_unique` (`role_id`,`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='角色成员';