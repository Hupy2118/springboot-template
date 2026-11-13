-- 内置数据源资源服务：管理表初始化脚本。
-- 在预先创建的管理库中手工执行，包含以下四张表：
-- md_datasource_resource：逻辑数据源资源及固定挂载。
-- md_datasource_table：资源拥有的精确物理表目录。
-- md_database_operation：建表、重建、删除和资源回收的执行记录。
-- md_data_operation：独立权限 Seed 执行记录。
-- datasource_key 为逻辑资源标识；datasource_id 为物理挂载编号，两者不能混用。
-- 本脚本在管理库中创建四张管理表，不操作业务挂载库。
-- 所有 bigint 主键由服务生成，业务表名总长度限制仍为 64 字符。

-- 由部署流程在管理库手工执行；应用不自动初始化，不在用户挂载库执行。

CREATE TABLE `md_datasource_resource`
(
    `id`                  bigint                          NOT NULL COMMENT '唯一标识',
    `datasource_key`      varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '数据源标识',
    `app_key`             varchar(64) COLLATE utf8mb4_bin          DEFAULT NULL COMMENT 'Studio应用标识，可空',
    `app_name`            varchar(255) COLLATE utf8mb4_bin         DEFAULT NULL COMMENT 'Studio应用名称',
    `datasource_id`       tinyint unsigned NOT NULL COMMENT '挂载ID',
    `status`              varchar(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'REGISTERED' COMMENT '资源状态',
    `active_operation_id` bigint                                   DEFAULT NULL COMMENT '当前执行ID',
    `created_by`          varchar(32) COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '创建人ID',
    `creator`             varchar(255) COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '创建人姓名',
    `created_at`          timestamp                       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`          timestamp                       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_by`          varchar(32) COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '删除人ID',
    `deleter`             varchar(255) COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '删除人姓名',
    `deleted_at`          timestamp NULL DEFAULT NULL COMMENT '删除时间',
    `is_deleted`          tinyint unsigned NOT NULL DEFAULT '0' COMMENT '删除标志',
    -- is_deleted：0 未删除，1 已删除；软删不代表真实删表或资源回收。
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_md_datasource_resource_datasource_key` (`datasource_key`),
    KEY                   `idx_md_datasource_resource_allocation` (`datasource_id`, `status`, `is_deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='数据源资源注册表';

-- datasource_id 首次注册时分配并固定，Schema 从该挂载配置的 db-name 读取。
-- datasourceKey 由 SaaS 创建时生成；后续按原 datasourceKey 访问，不再次注册或重新分配。
-- 已释放或软删记录不隐式恢复，也不复用其 datasourceKey。
-- status：REGISTERED 已注册，RELEASED 已完成回收；注册不代表建表成功。
-- active_operation_id：无占用时为 NULL，条件更新认领或释放，不因超时自动清空。
-- idx_md_datasource_resource_allocation：统计有效绑定资源数，用于分配时处理表数相同的情况。

CREATE TABLE `md_datasource_table`
(
    `id`             bigint                          NOT NULL COMMENT '唯一标识',
    `datasource_key` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '数据源标识',
    `datasource_id`  tinyint unsigned NOT NULL COMMENT '挂载ID',
    `table_name`     varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '表名',
    `status`         varchar(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'REGISTERED' COMMENT '表状态',
    `created_by`     varchar(32) COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '创建人ID',
    `creator`        varchar(255) COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '创建人姓名',
    `created_at`     timestamp                       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     timestamp                       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_by`     varchar(32) COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '删除人ID',
    `deleter`        varchar(255) COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '删除人姓名',
    `deleted_at`     timestamp NULL DEFAULT NULL COMMENT '删除时间',
    `is_deleted`     tinyint unsigned NOT NULL DEFAULT '0' COMMENT '删除标志',
    -- is_deleted：0 未删除，1 已删除；软删不代表真实删表或资源回收。
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_md_datasource_table_location` (`datasource_id`, `table_name`),
    KEY              `idx_md_datasource_table_owner` (`datasource_key`),
    KEY              `idx_md_datasource_table_count` (`datasource_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='数据源表目录';

-- datasource_id 从数据源资源绑定复制，保存时核对一致；Schema 不重复存储。
-- 位置查询和冲突核对使用 datasource_id、table_name。
-- status：REGISTERED 已登记，CREATED 建成并回读确认，DROPPED 删除已确认。
-- 执行中、失败和未知结果记在执行记录中，不作为表状态。
-- CREATED 表示最近一次确认建成，当前结构仍需读取真实数据库。
-- uk_md_datasource_table_location：同一挂载下的同名表只能登记一次。
-- 一个 datasource-id 固定对应一个配置 Schema；扩容新增 ID，不改变既有位置。
-- idx_md_datasource_table_count：统计 CREATED 表数，包括未确认真实删表的软删记录。
-- 建表和改表 SQL 保存在执行记录，不在目录中保存双份结构快照。

-- 由部署手工在管理库执行，应用不自动建表。

CREATE TABLE `md_database_operation`
(
    `id`               bigint                          NOT NULL COMMENT '唯一标识',
    `datasource_key`   varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '数据源标识',
    `datasource_id`    tinyint unsigned NOT NULL COMMENT '挂载ID',
    `action_type`      varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '操作类型',
    `execution_json`   json                            NOT NULL COMMENT '执行内容',
    `status`           varchar(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'RUNNING' COMMENT '执行状态',
    `execution_result` json                                     DEFAULT NULL COMMENT '执行结果',
    `created_by`       varchar(32) COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '创建人ID',
    `creator`          varchar(255) COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '创建人姓名',
    `created_at`       timestamp                       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`       timestamp                       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted_by`       varchar(32) COLLATE utf8mb4_bin          DEFAULT NULL COMMENT '删除人ID',
    `deleter`          varchar(255) COLLATE utf8mb4_bin         DEFAULT NULL COMMENT '删除人姓名',
    `deleted_at`       timestamp NULL DEFAULT NULL COMMENT '删除时间',
    `is_deleted`       tinyint unsigned NOT NULL DEFAULT '0' COMMENT '删除标志',
    PRIMARY KEY (`id`),
    KEY                `idx_md_database_operation_history` (`datasource_key`, `created_at`),
    KEY                `idx_md_database_operation_status` (`status`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='数据库执行记录表';

-- 独立的受控数据操作记录，由部署手工创建。
-- payload_json 含 Subject，仅 SaaS 服务与授权审计人员可访问。
CREATE TABLE `md_data_operation` (
  `id` bigint NOT NULL COMMENT '服务生成的操作 ID',
  `datasource_key` varchar(64) COLLATE utf8mb4_bin NOT NULL COMMENT '逻辑数据源标识',
  `datasource_id` tinyint unsigned NOT NULL COMMENT '固定挂载 ID',
  `purpose` varchar(32) COLLATE utf8mb4_bin NOT NULL COMMENT '受控 Seed 用途',
  `idempotency_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求幂等键',
  `request_fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范载荷 SHA-256',
  `status` varchar(24) COLLATE utf8mb4_bin NOT NULL DEFAULT 'RUNNING' COMMENT '执行状态',
  `payload_json` json NOT NULL COMMENT '受限内部执行载荷',
  `result_json` json DEFAULT NULL COMMENT '安全结果摘要',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '受理时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `completed_at` timestamp NULL DEFAULT NULL COMMENT '记录终态或未知的时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_md_data_operation_idempotency` (`datasource_key`, `idempotency_key`),
  KEY `idx_md_data_operation_status` (`status`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='受控数据操作审计表';
