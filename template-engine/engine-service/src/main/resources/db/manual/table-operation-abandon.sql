-- MySQL 8.0 管理库运维工具：仅安装过程，不自动处置，不修改管理表结构。
-- 在专用新连接中使用；不得在已有事务中安装或调用，不使用 mysql --force。
-- 只授予受控运维账号 CREATE ROUTINE / EXECUTE 及三张表的必要权限。
-- 业务服务账号不得调用。先读 docs/operation-recovery-runbook.md。
-- 同名过程已存在时安装会失败；禁止自动 DROP / 覆盖。
DELIMITER
$$
CREATE PROCEDURE md_abandon_database_operation(
    IN p_datasource_key VARCHAR (64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin,
    IN p_operation_id BIGINT,
    IN p_datasource_id INT,
    IN p_operator LONGTEXT,
    IN p_reason LONGTEXT,
    IN p_evidence LONGTEXT,
    IN p_workers_stopped TINYINT,
    IN p_sql_ended TINYINT,
    IN p_observed_tables JSON,
    IN p_commit TINYINT
)
    SQL SECURITY INVOKER
procedure_body:
BEGIN
    DECLARE
v_resource_id BIGINT;
    DECLARE
v_resource_datasource INT;
    DECLARE
v_resource_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_resource_deleted TINYINT;
    DECLARE
v_active BIGINT;
    DECLARE
v_operation_datasource INT;
    DECLARE
v_operation_deleted TINYINT;
    DECLARE
v_action VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_locked_action VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_content JSON;
    DECLARE
v_locked_content JSON;
    DECLARE
v_result JSON;
    DECLARE
v_snapshot_result JSON;
    DECLARE
v_next_result JSON;
    DECLARE
v_names JSON;
    DECLARE
v_seen JSON;
    DECLARE
v_item JSON;
    DECLARE
v_observation JSON;
    DECLARE
v_directory_before JSON;
    DECLARE
v_name LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_previous_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_next_name VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_binding_id BIGINT;
    DECLARE
v_binding_owner VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_binding_deleted TINYINT;
    DECLARE
v_binding_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
    DECLARE
v_count INT;
    DECLARE
v_steps INT;
    DECLARE
v_i INT DEFAULT 0;
    DECLARE
v_j INT DEFAULT 0;
    DECLARE
v_current_directory_count INT;

    -- SELECT INTO 查无记录和任何 SQL 异常均结束调用并回滚，不能部分释放。
    DECLARE
EXIT HANDLER FOR NOT FOUND
BEGIN
ROLLBACK;
SIGNAL
SQLSTATE '45000' SET MESSAGE_TEXT = '资源、目录或操作不存在，处置已回滚';
END;
    DECLARE
EXIT HANDLER FOR SQLEXCEPTION
BEGIN
ROLLBACK;
RESIGNAL;
END;

    -- NULL / 0 的提交开关只预演；真实执行结束由运维核对，不由这些标记证明。
    IF
p_datasource_key IS NULL OR CHAR_LENGTH(p_datasource_key) <> 12
       OR p_operation_id IS NULL OR p_operation_id <= 0
       OR p_datasource_id IS NULL OR p_datasource_id NOT BETWEEN 1 AND 255
       OR p_operator IS NULL OR CHAR_LENGTH(TRIM(p_operator)) NOT BETWEEN 1 AND 128
       OR p_reason IS NULL OR CHAR_LENGTH(TRIM(p_reason)) NOT BETWEEN 1 AND 500
       OR p_evidence IS NULL OR CHAR_LENGTH(TRIM(p_evidence)) NOT BETWEEN 1 AND 1000
       OR p_workers_stopped IS NULL OR p_workers_stopped <> 1
       OR p_sql_ended IS NULL OR p_sql_ended <> 1
       OR (p_commit IS NOT NULL AND p_commit NOT IN (0, 1))
       OR NOT (JSON_TYPE(p_observed_tables) <=> 'ARRAY') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '处置参数无效，必须签署停止执行及目标SQL结束核对';
END IF;

START TRANSACTION;
-- 应用执行也首先锁资源；普通快照只用于获得冻结清单，之后再次锁定操作核对。
SELECT id, datasource_id, status, is_deleted, active_operation_id
INTO v_resource_id, v_resource_datasource, v_resource_status, v_resource_deleted, v_active
FROM md_datasource_resource
WHERE datasource_key = p_datasource_key FOR UPDATE;
SELECT datasource_id, action_type, status, is_deleted, execution_json, execution_result
INTO v_operation_datasource, v_action, v_status, v_operation_deleted, v_content, v_result
FROM md_database_operation
WHERE id = p_operation_id
  AND datasource_key = p_datasource_key;
SET
v_snapshot_result = v_result;
    IF
v_resource_datasource <> p_datasource_id OR v_operation_datasource <> p_datasource_id
       OR v_resource_deleted <> 0 OR v_operation_deleted <> 0
       OR v_action NOT IN ('CREATE_TABLE', 'CREATE_TABLE_BATCH', 'REBUILD_TABLE', 'DROP_TABLE', 'RELEASE_DATASOURCE')
       OR NOT (JSON_TYPE(v_content) <=> 'OBJECT') OR NOT (JSON_TYPE(v_result) <=> 'OBJECT') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '资源归属、挂载、有效性或操作内容不符合当前合同';
END IF;

    -- 重复调用在核对新占用前直接返回原审计，允许后续资源已回收或已认领新操作。
    IF
v_status = 'FAILED'
       AND JSON_UNQUOTE(JSON_EXTRACT(v_result, '$.errorCode')) = 'MANUALLY_ABANDONED'
       AND JSON_TYPE(JSON_EXTRACT(v_result, '$.manualResolution')) = 'OBJECT'
       AND JSON_LENGTH(JSON_EXTRACT(v_result, '$.manualResolution')) > 0 THEN
        ROLLBACK;
SELECT p_operation_id                               AS operation_id,
       'ALREADY_RESOLVED'                           AS outcome,
       JSON_EXTRACT(v_result, '$.manualResolution') AS manual_resolution;
LEAVE
procedure_body;
END IF;
    IF
v_resource_status <> 'REGISTERED' OR v_status NOT IN ('RUNNING', 'UNKNOWN')
       OR v_active IS NULL OR v_active <> p_operation_id
       OR JSON_CONTAINS_PATH(v_result, 'one', '$.manualResolution') = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '资源状态、原操作状态、当前占用或既有处置记录不允许人工结束';
END IF;

    SET
v_names = JSON_EXTRACT(v_content, '$.tableNames');
    IF
NOT (JSON_TYPE(v_names) <=> 'ARRAY')
       OR NOT (JSON_TYPE(JSON_EXTRACT(v_result, '$.steps')) <=> 'ARRAY') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '原操作缺少完整表清单或步骤证据，禁止猜测处置';
END IF;
    SET
v_count = JSON_LENGTH(v_names);
    SET
v_steps = CASE v_action WHEN 'REBUILD_TABLE' THEN 2
                  WHEN 'RELEASE_DATASOURCE' THEN GREATEST(1, v_count) ELSE 1
END;
    IF
v_count > 200 OR (v_action <> 'RELEASE_DATASOURCE' AND v_count <> 1)
       OR JSON_LENGTH(JSON_EXTRACT(v_result, '$.steps')) <> v_steps
       OR JSON_LENGTH(p_observed_tables) <> v_count THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '原清单、步骤数量或逐表核对数量不匹配';
END IF;

    -- 原始清单拒绝重复、错误类型和非法名称；不从前缀或目标库重新枚举范围。
    SET
v_seen = JSON_ARRAY();
    SET
v_i = 0;
    WHILE
v_i < v_count DO
        SET v_item = JSON_EXTRACT(v_names, CONCAT('$[', v_i, ']'));
        SET
v_name = JSON_UNQUOTE(v_item);
        IF
NOT (JSON_TYPE(v_item) <=> 'STRING') OR CHAR_LENGTH(v_name) NOT BETWEEN 1 AND 64
           OR BINARY v_name <> BINARY TRIM(v_name) OR LOCATE('.', v_name) > 0
           OR v_name REGEXP '[[:cntrl:]]'
           OR JSON_CONTAINS(v_seen, v_item) = 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '原操作表清单存在非法名称或重复项';
END IF;
        SET
v_seen = JSON_ARRAY_APPEND(v_seen, '$', v_item);
        SET
v_i = v_i + 1;
END WHILE;

    -- 输入必须为精确表名与 JSON 布尔存在性；遗漏、额外表、重复和数字布尔均拒绝。
    SET
v_seen = JSON_ARRAY();
    SET
v_i = 0;
    WHILE
v_i < v_count DO
        SET v_item = JSON_EXTRACT(p_observed_tables, CONCAT('$[', v_i, ']'));
        IF
NOT (JSON_TYPE(v_item) <=> 'OBJECT') OR JSON_LENGTH(v_item) <> 2
           OR JSON_CONTAINS_PATH(v_item, 'all', '$.tableName', '$.exists') <> 1
           OR NOT (JSON_TYPE(JSON_EXTRACT(v_item, '$.tableName')) <=> 'STRING')
           OR NOT (JSON_TYPE(JSON_EXTRACT(v_item, '$.exists')) <=> 'BOOLEAN') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '逐表结果必须仅包含tableName和布尔exists';
END IF;
        SET
v_item = JSON_EXTRACT(v_item, '$.tableName');
        IF
JSON_CONTAINS(v_names, v_item) <> 1 OR JSON_CONTAINS(v_seen, v_item) = 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '逐表核对含有额外表或重复表，禁止部分处置';
END IF;
        SET
v_seen = JSON_ARRAY_APPEND(v_seen, '$', v_item);
        SET
v_i = v_i + 1;
END WHILE;
    SET
v_i = 0;
    WHILE
v_i < v_steps DO
        SET v_item = JSON_EXTRACT(v_result, CONCAT('$.steps[', v_i, ']'));
        IF
NOT (JSON_TYPE(v_item) <=> 'OBJECT')
           OR NOT (JSON_TYPE(JSON_EXTRACT(v_item, '$.sequence')) <=> 'INTEGER')
           OR NOT (JSON_EXTRACT(v_item, '$.sequence') <=> v_i + 1)
           OR NOT (JSON_TYPE(JSON_EXTRACT(v_item, '$.verification')) <=> 'OBJECT')
           OR COALESCE(JSON_CONTAINS(JSON_ARRAY('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'),
                       JSON_EXTRACT(v_item, '$.status')), 0) <> 1 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '原步骤证据不完整或已损坏，禁止改写后释放';
END IF;
        SET
v_i = v_i + 1;
END WHILE;

    -- RELEASE 必须仍覆盖资源的全部保留目录；空清单只允许原资源确实无目录。
    IF
v_action = 'RELEASE_DATASOURCE' THEN
SELECT COUNT(*)
INTO v_current_directory_count
FROM md_datasource_table
WHERE datasource_key = p_datasource_key;
IF
v_current_directory_count <> v_count THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '整份资源目录已偏离原操作范围，禁止遗漏目录处置';
END IF;
END IF;

    -- 按表名固定顺序锁定目录，保持资源、目录、操作的锁顺序；此处只读不修正。
    SET
v_directory_before = JSON_ARRAY();
    SET
v_previous_name = NULL;
    SET
v_i = 0;
    WHILE
v_i < v_count DO
SELECT MIN(table_name)
INTO v_next_name
FROM md_datasource_table
WHERE datasource_id = p_datasource_id
  AND JSON_CONTAINS(v_names, JSON_QUOTE(table_name)) = 1
  AND (v_previous_name IS NULL OR BINARY table_name > BINARY v_previous_name);
IF
v_next_name IS NULL THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '原操作目标缺少精确目录，禁止猜测归属';
END IF;
SELECT id, datasource_key, is_deleted, status
INTO v_binding_id, v_binding_owner, v_binding_deleted, v_binding_status
FROM md_datasource_table
WHERE datasource_id = p_datasource_id
  AND table_name = v_next_name FOR UPDATE;
IF
v_binding_owner <> p_datasource_key OR v_binding_deleted <> 0
           OR v_binding_status NOT IN ('REGISTERED', 'CREATED', 'DROPPED') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '目标目录归属、有效性或状态不允许处置';
END IF;
        SET
v_directory_before = JSON_ARRAY_APPEND(v_directory_before, '$',
                JSON_OBJECT('tableName', v_next_name, 'status', v_binding_status));
        SET
v_previous_name = v_next_name;
        SET
v_i = v_i + 1;
END WHILE;
SELECT action_type, execution_json, status, execution_result, datasource_id, is_deleted
INTO v_locked_action, v_locked_content, v_status, v_result, v_operation_datasource, v_operation_deleted
FROM md_database_operation
WHERE id = p_operation_id
  AND datasource_key = p_datasource_key FOR UPDATE;
IF
v_locked_action <> v_action OR NOT (v_locked_content <=> v_content)
       OR NOT (v_result <=> v_snapshot_result)
       OR v_status NOT IN ('RUNNING', 'UNKNOWN') OR v_operation_datasource <> p_datasource_id
       OR v_operation_deleted <> 0 OR JSON_CONTAINS_PATH(v_result, 'one', '$.manualResolution') = 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '操作在核对期间发生变化，全部处置回滚';
END IF;

    -- 审计时间独立；保留原SQL、steps、startedAt、finishedAt和abandonRequest。
    SET
v_next_result = JSON_SET(v_result,
        '$.errorCode', 'MANUALLY_ABANDONED',
        '$.errorSummary', '操作已人工结束，请重新检查真实结构后继续操作',
        '$.manualResolution', JSON_OBJECT(
            'resolvedAt', CONCAT(DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%f'), 'Z'),
            'resolvedBy', TRIM(p_operator), 'databaseActor', SUBSTRING_INDEX(CURRENT_USER(), '@', 1),
            'reason', TRIM(p_reason), 'evidence', TRIM(p_evidence), 'previousStatus', v_status,
            'previousErrorCode', JSON_EXTRACT(v_result, '$.errorCode'),
            'previousErrorSummary', JSON_EXTRACT(v_result, '$.errorSummary'),
            'actionType', v_action, 'workersStopped', CAST('true' AS JSON), 'sqlEnded', CAST('true' AS JSON),
            'tableObservations', p_observed_tables, 'directoryBefore', v_directory_before));
    IF
OCTET_LENGTH(CAST(v_next_result AS CHAR CHARACTER SET utf8mb4)) > 262144 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '追加审计超过当前结果容量，未结束操作或释放占用';
END IF;
UPDATE md_database_operation
SET status           = 'FAILED',
    execution_result = v_next_result
WHERE id = p_operation_id
  AND datasource_key = p_datasource_key
  AND datasource_id = p_datasource_id
  AND status = v_status
  AND is_deleted = 0;
IF
ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '操作条件写入失败，全部处置回滚';
END IF;

    -- 只更新已锁定目录的存在事实；不声称结构一致，不删目录、不释放表名。
    SET
v_i = 0;
    WHILE
v_i < v_count DO
        SET v_observation = JSON_EXTRACT(p_observed_tables, CONCAT('$[', v_i, ']'));
        SET
v_name = JSON_UNQUOTE(JSON_EXTRACT(v_observation, '$.tableName'));
        SET
v_binding_status = IF(JSON_EXTRACT(v_observation, '$.exists') = CAST('true' AS JSON), 'CREATED', 'DROPPED');
UPDATE md_datasource_table
SET status = v_binding_status
WHERE datasource_id = p_datasource_id
  AND datasource_key = p_datasource_key
  AND table_name = v_name
  AND is_deleted = 0
  AND status <> v_binding_status;
-- 状态未变化时允许零行；重新锁读并确认写后事实，不能靠影响行数猜测成功。
SELECT status
INTO v_binding_status
FROM md_datasource_table
WHERE datasource_id = p_datasource_id
  AND datasource_key = p_datasource_key
  AND table_name = v_name
  AND is_deleted = 0 FOR UPDATE;
IF
v_binding_status <> IF(JSON_EXTRACT(v_observation, '$.exists') = CAST('true' AS JSON), 'CREATED', 'DROPPED') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '目录事实写入失败，全部处置回滚';
END IF;
        SET
v_i = v_i + 1;
END WHILE;
UPDATE md_datasource_resource
SET active_operation_id = NULL
WHERE id = v_resource_id
  AND datasource_key = p_datasource_key
  AND datasource_id = p_datasource_id
  AND status = 'REGISTERED'
  AND is_deleted = 0
  AND active_operation_id = p_operation_id;
IF
ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '原操作占用释放失败，全部处置回滚';
END IF;

    -- 人工放弃RELEASE仍保持REGISTERED；后续通过正常清理流程证明回收完成。
    IF
p_commit = 1 THEN
        COMMIT;
SELECT p_operation_id                                    AS operation_id,
       'MANUALLY_ABANDONED'                              AS outcome,
       JSON_EXTRACT(v_next_result, '$.manualResolution') AS manual_resolution;
ELSE
        ROLLBACK;
SELECT p_operation_id                                    AS operation_id,
       'DRY_RUN_ROLLED_BACK'                             AS outcome,
       JSON_EXTRACT(v_next_result, '$.manualResolution') AS proposed_manual_resolution;
END IF;
END$$
DELIMITER ;

-- 安装不CALL。完整调用样例与提交前核对步骤见运维文档。
-- 最后参数NULL或0仅预演；只有明确传1才提交。结束维护后按需由运维删除过程。
