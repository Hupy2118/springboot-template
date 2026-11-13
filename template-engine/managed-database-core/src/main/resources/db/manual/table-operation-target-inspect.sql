-- 在原datasourceId对应的原目标Schema连接中执行；不得在管理库替代目标核对。
-- 从原操作的execution_json.tableNames复制完整精确清单，空资源RELEASE保持[]。
SET
@recovery_expected_names = JSON_ARRAY();
SELECT expected.table_name             AS tableName,
       (actual.TABLE_NAME IS NOT NULL) AS tableExists,
       actual.TABLE_TYPE               AS tableType
FROM JSON_TABLE(@recovery_expected_names, '$[*]'
    COLUMNS (table_name VARCHAR(64) PATH '$' ERROR ON EMPTY ERROR ON ERROR)) AS expected
         LEFT JOIN information_schema.TABLES actual
                   ON BINARY actual.TABLE_SCHEMA = BINARY DATABASE()
 AND ((@@lower_case_table_names = 0 AND BINARY actual.TABLE_NAME = BINARY expected.table_name)
   OR (@@lower_case_table_names IN (1, 2) AND BINARY LOWER(actual.TABLE_NAME) = BINARY expected.table_name))
ORDER BY expected.table_name;
-- tableType非空时必须为BASE TABLE；发现视图或其他对象需停止处理，不签署存在性结果。
-- 本查询只展示存在性，不验证结构一致，也不能证明在途DDL已结束。
-- 运维须另行核对原执行会话、语句与锁；无确定结束证据就继续保留占用。
-- 按原挂载大小写规则匹配；工具输入仍沿用管理目录的精确键，不自行改写。
