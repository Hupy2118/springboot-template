-- 仅在已有三张管理表的管理库手工执行；不在目标挂载执行。
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
