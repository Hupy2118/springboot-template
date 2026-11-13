-- 管理库只读核对。空参数不返回目标明细；不输出原SQL、连接信息或凭据。
SET
@recovery_datasource_key = '';
SET
@recovery_operation_id = 0;
SELECT r.datasource_key,
       r.datasource_id,
       r.status                                               AS resource_status,
       r.active_operation_id,
       o.id                                                   AS operation_id,
       o.datasource_id                                        AS operation_datasource_id,
       o.action_type,
       o.status                                               AS operation_status,
       o.created_at,
       o.is_deleted,
       JSON_EXTRACT(o.execution_json, '$.tableNames')         AS expected_table_names,
       JSON_EXTRACT(o.execution_result, '$.steps')            AS steps,
       JSON_EXTRACT(o.execution_result, '$.abandonRequest')   AS abandon_request,
       JSON_EXTRACT(o.execution_result, '$.manualResolution') AS manual_resolution
FROM md_datasource_resource r
         JOIN md_database_operation o ON o.datasource_key = r.datasource_key AND o.id = @recovery_operation_id
WHERE r.datasource_key = @recovery_datasource_key;

-- 明确查看整个资源的保留目录，含DROPPED及软删，以便发现异常，不按前缀扫描。
SELECT datasource_key, datasource_id, table_name, status, is_deleted
FROM md_datasource_table
WHERE datasource_key = @recovery_datasource_key
ORDER BY table_name;

-- 仅供人工排查：超过30分钟不代表SQL已结束，也不触发任何清理。
SELECT id, datasource_key, datasource_id, action_type, status, created_at
FROM md_database_operation
WHERE status IN ('RUNNING', 'UNKNOWN')
  AND created_at < UTC_TIMESTAMP() - INTERVAL 30 MINUTE
ORDER BY created_at, id LIMIT 100;
