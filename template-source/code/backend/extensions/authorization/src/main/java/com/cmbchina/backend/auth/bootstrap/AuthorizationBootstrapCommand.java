package com.cmbchina.backend.auth.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 从已确认 TechnicalPlan 以只增不改方式初始化权限表和最小管理员数据。
 */
public final class AuthorizationBootstrapCommand {
    private static final List<String> PERMISSION_TABLES = Collections.unmodifiableList(
            Arrays.asList("resource", "role", "role_resource", "role_member"));
    private static final String SYSTEM_ADMIN_ROLE_ID = "SYSTEM_ADMIN";
    private static final String SYSTEM_MANAGEMENT_RESOURCE = "system_authorization_management";
    private static final String LOCK_NAME = "xcode_authorization_bootstrap";

    private static final Map<String, Set<String>> REQUIRED_COLUMNS = requiredColumns();
    private static final Map<String, Set<List<String>>> REQUIRED_UNIQUE_INDEXES = requiredIndexes();

    private AuthorizationBootstrapCommand() {
    }

    /**
     * Bootstrap 命令入口；失败时返回非零退出码。
     */
    public static void main(String[] args) {
        try {
            Arguments arguments = Arguments.parse(scriptArguments(args));
            BootstrapInput input = loadBootstrapInput(arguments.technicalPlan, arguments.application);
            BootstrapDataSource.Settings yamlSettings = BootstrapDataSource.load(arguments.applicationYml, arguments.profile);
            splitDdl(arguments.ddl);
            String dbUrl = firstNonBlank(arguments.dbUrl, yamlSettings.url);
            String dbUser = firstNonBlank(arguments.dbUser, yamlSettings.username);
            if (!dbUrl.startsWith("jdbc:tdsql-mysql://") || dbUser.trim().isEmpty())
                throw new BootstrapException("Bootstrap 必须使用有效的 Bee/TDSQL 数据源");
            if (arguments.dryRun) {
                System.out.printf(
                        "Dry-run 成功：fingerprint=%s，resources=%d，initialAdministrators=%d，业务 role_resource 将不会初始化%n",
                        input.fingerprint, input.resources.size(), input.administratorSubjects.size());
                return;
            }

            Class.forName(yamlSettings.driver);
            String dbPassword = yamlSettings.password == null ? "" : yamlSettings.password;

            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                new BootstrapExecutor(connection, arguments.ddl, input).run();
            }
        } catch (BootstrapException exception) {
            System.err.println("Bootstrap 失败：" + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Bootstrap 失败：" + exception.getClass().getSimpleName());
            System.exit(3);
        }
    }

    private static BootstrapInput loadBootstrapInput(Path technicalPlanPath, Path applicationPath) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode technicalPlan;
        JsonNode application;
        try {
            technicalPlan = mapper.readTree(technicalPlanPath.toFile());
            application = mapper.readTree(applicationPath.toFile());
        } catch (IOException exception) {
            throw new BootstrapException("无法读取初始化输入 JSON", exception);
        }

        if (!"technical-plan".equals(textValue(technicalPlan.get("artifact_type")))
                || !"confirmed".equals(textValue(technicalPlan.get("confirmation_status")))) {
            throw new BootstrapException("TechnicalPlan 尚未确认，拒绝初始化权限数据");
        }
        JsonNode manifest = technicalPlan.get("authorization_manifest");
        if (manifest == null || !manifest.isObject()) {
            throw new BootstrapException("TechnicalPlan.authorization_manifest 缺失");
        }
        if (!"authorization-manifest.v3".equals(textValue(manifest.get("schema_version")))) {
            throw new BootstrapException("仅支持 authorization-manifest.v3");
        }
        JsonNode system = manifest.path("systemAuthorization");
        if (!"system_admin".equals(textValue(system.get("adminRoleSeedKey")))
                || !SYSTEM_MANAGEMENT_RESOURCE.equals(textValue(system.get("managementResourceKey")))) {
            throw new BootstrapException("systemAuthorization 必须指定 system_admin 与 system_authorization_management");
        }

        JsonNode resourceNodes = manifest.get("resources");
        if (resourceNodes == null || !resourceNodes.isArray()) {
            throw new BootstrapException("authorization_manifest.resources 必须是数组");
        }
        List<ResourceSeed> resources = new ArrayList<ResourceSeed>();
        Set<String> resourceKeys = new HashSet<String>();
        int systemResourceCount = 0;
        for (int index = 0; index < resourceNodes.size(); index++) {
            JsonNode item = resourceNodes.get(index);
            if (!item.isObject()) {
                throw new BootstrapException("resources[" + index + "] 必须是对象");
            }
            String prefix = "resources[" + index + "]";
            ResourceSeed resource = new ResourceSeed(
                    requiredText(item.get("resourceKey"), prefix + ".resourceKey", 64),
                    requiredText(item.get("name"), prefix + ".name", 32),
                    optionalText(item.get("description"), prefix + ".description", 128),
                    requiredText(item.get("origin"), prefix + ".origin", 32),
                    optionalText(item.get("type"), prefix + ".type", 32),
                    requiredText(item.get("targetResourceRef"), prefix + ".targetResourceRef", 255));
            if (!resource.key.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*")
                    || !("system".equals(resource.origin) || "business".equals(resource.origin))) {
                throw new BootstrapException("资源键或 origin 非法");
            }
            if (!SYSTEM_MANAGEMENT_RESOURCE.equals(resource.key)
                    && (!"business".equals(resource.origin)
                    || !("page".equals(resource.type) || "operation".equals(resource.type)))) {
                throw new BootstrapException("业务资源必须为 business/page 或 business/operation");
            }
            if (!resourceKeys.add(resource.key)) {
                throw new BootstrapException("manifest 存在重复 resourceKey：" + resource.key);
            }
            if (SYSTEM_MANAGEMENT_RESOURCE.equals(resource.key)) {
                systemResourceCount++;
                if (!"system".equals(resource.origin) || !"system".equals(resource.type)) {
                    throw new BootstrapException("system_authorization_management 必须为 origin=system,type=system");
                }
            }
            if (("system".equals(resource.type) && !"system:authorization_management".equals(resource.targetResourceRef))
                    || ("page".equals(resource.type) && !("page:" + resource.key).equals(resource.targetResourceRef))
                    || ("operation".equals(resource.type) && !resource.targetResourceRef.matches("action:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*"))) {
                throw new BootstrapException("资源 targetResourceRef 与 type 不匹配");
            }
            resources.add(resource);
        }
        if (systemResourceCount != 1) {
            throw new BootstrapException("manifest 必须恰好包含一个 system_authorization_management");
        }

        validateDefaultRoles(manifest.path("defaultRoleAuthorization"), resourceKeys);

        JsonNode authorization = application.get("authorization");
        if (authorization == null || !authorization.isObject() || !authorization.path("enabled").isBoolean() || !authorization.path("enabled").asBoolean(false)) {
            throw new BootstrapException("application.authorization.enabled 不是 true");
        }
        JsonNode subjectNodes = authorization.get("initialAdministratorSubjects");
        if (subjectNodes == null || !subjectNodes.isArray() || subjectNodes.size() == 0) {
            throw new BootstrapException("application.authorization.initialAdministratorSubjects 不能为空");
        }
        List<String> subjects = new ArrayList<String>();
        Set<String> subjectKeys = new HashSet<String>();
        for (int index = 0; index < subjectNodes.size(); index++) {
            String subject = requiredText(subjectNodes.get(index),
                    "initialAdministratorSubjects[" + index + "]", 32);
            if (!subjectKeys.add(subject)) {
                throw new BootstrapException("初始管理员 subject 重复：" + subject);
            }
            subjects.add(subject);
        }

        String fingerprint = requiredText(manifest.get("fingerprint"), "manifest.fingerprint", 128);
        if (!fingerprint.matches("sha256:[0-9a-f]{64}")) throw new BootstrapException("manifest.fingerprint 格式非法");
        return new BootstrapInput(resources, subjects, fingerprint);
    }

    /** Validate business grants, but leave their persistence to a separately modelled workflow. */
    private static void validateDefaultRoles(JsonNode defaults, Set<String> resources) {
        if (!defaults.isObject() || !defaults.path("roles").isArray() || !defaults.path("roleResourceGrants").isArray())
            throw new BootstrapException("defaultRoleAuthorization 必须包含 roles 与 roleResourceGrants 数组");
        Set<String> roles = new HashSet<String>();
        for (JsonNode role : defaults.path("roles")) {
            String key = requiredText(role.get("roleSeedKey"), "roleSeedKey", 64);
            requiredText(role.get("name"), "role.name", 32);
            optionalText(role.get("description"), "role.description", 128);
            if ("system_admin".equals(key) || "SYSTEM_ADMIN".equals(key) || !roles.add(key))
                throw new BootstrapException("业务角色 seed key 重复或占用系统角色");
        }
        Set<String> granted = new HashSet<String>();
        for (JsonNode grant : defaults.path("roleResourceGrants")) {
            String key = requiredText(grant.get("roleSeedKey"), "grant.roleSeedKey", 64);
            if (!roles.contains(key) || !granted.add(key) || !grant.path("resourceKeys").isArray())
                throw new BootstrapException("业务角色授权引用非法");
            Set<String> unique = new HashSet<String>();
            for (JsonNode resource : grant.path("resourceKeys")) {
                String resourceKey = requiredText(resource, "grant.resourceKey", 64);
                if (!resources.contains(resourceKey) || SYSTEM_MANAGEMENT_RESOURCE.equals(resourceKey) || !unique.add(resourceKey))
                    throw new BootstrapException("业务角色授权资源非法");
            }
        }
        if (!roles.isEmpty()) System.out.println("业务默认角色与授权已校验，本命令不会写入这些数据");
    }

    private static String textValue(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String requiredText(JsonNode value, String field, int maxLength) {
        String text = textValue(value);
        if (text == null || text.trim().isEmpty()) {
            throw new BootstrapException(field + " 必须是非空字符串");
        }
        validateLength(text, field, maxLength);
        return text;
    }

    private static String optionalText(JsonNode value, String field, int maxLength) {
        if (value == null || value.isNull()) {
            return null;
        }
        String text = textValue(value);
        if (text == null) {
            throw new BootstrapException(field + " 必须是字符串或 null");
        }
        validateLength(text, field, maxLength);
        return text;
    }

    private static void validateLength(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new BootstrapException(field + " 长度 " + value.length() + " 超过数据库限制 " + maxLength);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return "";
    }

    // Numbered environment variables preserve spaces/quotes across Maven and both shells.
    private static String[] scriptArguments(String[] args) {
        String count = System.getenv("AUTHORIZATION_BOOTSTRAP_ARGUMENT_COUNT");
        if (count == null) return args;
        int size = Integer.parseInt(count);
        if (size < 0 || size > 100) throw new BootstrapException("脚本参数数量非法");
        String[] result = new String[size];
        for (int i = 0; i < size; i++) {
            result[i] = System.getenv("AUTHORIZATION_BOOTSTRAP_ARGUMENT_" + i);
            if (result[i] == null) throw new BootstrapException("脚本参数缺失");
        }
        return result;
    }

    private static Map<String, Set<String>> requiredColumns() {
        Map<String, Set<String>> result = new HashMap<String, Set<String>>();
        result.put("role", setOf("id", "role_id", "role_name", "description", "enable", "is_system",
                "created_at", "created_by", "updated_at", "updated_by", "is_deleted", "deleted_at", "deleted_by"));
        result.put("resource", setOf("id", "key", "name", "description", "origin", "type",
                "target_resource_ref", "created_at"));
        result.put("role_resource", setOf("role_id", "resource_key", "created_at", "created_by"));
        result.put("role_member", setOf("role_id", "member_id", "member_name", "created_at", "created_by"));
        return result;
    }

    private static Map<String, Set<List<String>>> requiredIndexes() {
        Map<String, Set<List<String>>> result = new HashMap<String, Set<List<String>>>();
        result.put("role", indexSet(Arrays.asList("id"), Arrays.asList("role_id")));
        result.put("resource", indexSet(Arrays.asList("id"), Arrays.asList("key")));
        result.put("role_resource", indexSet(Arrays.asList("role_id", "resource_key")));
        result.put("role_member", indexSet(Arrays.asList("role_id", "member_id")));
        return result;
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    @SafeVarargs
    private static Set<List<String>> indexSet(List<String>... values) {
        return new HashSet<List<String>>(Arrays.asList(values));
    }

    private static final class BootstrapExecutor {
        private final Connection connection;
        private final Path ddlPath;
        private final BootstrapInput input;

        private BootstrapExecutor(Connection connection, Path ddlPath, BootstrapInput input) {
            this.connection = connection;
            this.ddlPath = ddlPath;
            this.input = input;
        }

        private void run() throws SQLException {
            boolean locked = false;
            try {
                locked = acquireLock();
                if (!locked) {
                    throw new BootstrapException("30 秒内未获取权限 Bootstrap 数据库锁");
                }
                Set<String> existingTables = existingPermissionTables();
                Set<String> expectedTables = new LinkedHashSet<String>(PERMISSION_TABLES);
                if (!existingTables.isEmpty() && !existingTables.equals(expectedTables)) {
                    Set<String> missing = new LinkedHashSet<String>(expectedTables);
                    missing.removeAll(existingTables);
                    throw new BootstrapException("权限 Schema 不完整；已存在=" + existingTables
                            + "，缺失=" + missing + "，拒绝自动补表");
                }
                if (existingTables.isEmpty()) {
                    createPermissionTables();
                    System.out.println("已创建四张权限表");
                } else {
                    System.out.println("四张权限表均已存在，跳过建表");
                }

                validateSchema();
                connection.setAutoCommit(false);
                try {
                    boolean roleCreated = ensureAdminRole();
                    int resourcesCreated = ensureResources();
                    boolean systemGrantCreated = ensureSystemRoleResource();
                    int membersCreated = ensureInitialAdministrators();
                    connection.commit();
                    System.out.printf(
                            "Bootstrap 成功：role新增=%d，resource新增=%d，system授权新增=%d，管理员关系新增=%d%n",
                            roleCreated ? 1 : 0, resourcesCreated, systemGrantCreated ? 1 : 0, membersCreated);
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            } finally {
                if (locked) {
                    releaseLock();
                }
            }
        }

        private boolean acquireLock() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 30)")) {
                statement.setString(1, LOCK_NAME);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() && result.getInt(1) == 1;
                }
            }
        }

        private void releaseLock() {
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, LOCK_NAME);
                statement.executeQuery();
            } catch (SQLException exception) {
                System.err.println("警告：释放 Bootstrap 锁失败：" + exception.getClass().getSimpleName());
            }
        }

        private Set<String> existingPermissionTables() throws SQLException {
            String sql = "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                    + "AND table_name IN ('resource','role','role_resource','role_member')";
            Set<String> tables = new LinkedHashSet<String>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
            return tables;
        }

        private void createPermissionTables() {
            List<String> statements = splitDdl(ddlPath);
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            } catch (SQLException exception) {
                throw new BootstrapException("权限表创建失败；MySQL DDL 非事务性，若只创建了部分表，"
                        + "下次执行会 fail closed，请人工核对 Schema", exception);
            }
        }

        private void validateSchema() throws SQLException {
            for (String table : PERMISSION_TABLES) {
                Set<String> columns = new HashSet<String>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = ?")) {
                    statement.setString(1, table);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            columns.add(result.getString(1));
                        }
                    }
                }
                Set<String> missingColumns = new HashSet<String>(REQUIRED_COLUMNS.get(table));
                missingColumns.removeAll(columns);
                if (!missingColumns.isEmpty()) {
                    throw new BootstrapException("权限表 " + table + " 缺少字段：" + missingColumns);
                }

                Map<String, TreeMap<Integer, String>> indexes = new HashMap<String, TreeMap<Integer, String>>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT index_name, column_name, seq_in_index FROM information_schema.statistics "
                                + "WHERE table_schema = DATABASE() AND table_name = ? AND non_unique = 0 "
                                + "ORDER BY index_name, seq_in_index")) {
                    statement.setString(1, table);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            String name = result.getString(1);
                            TreeMap<Integer, String> parts = indexes.get(name);
                            if (parts == null) {
                                parts = new TreeMap<Integer, String>();
                                indexes.put(name, parts);
                            }
                            parts.put(result.getInt(3), result.getString(2));
                        }
                    }
                }
                Set<List<String>> shapes = new HashSet<List<String>>();
                for (TreeMap<Integer, String> parts : indexes.values()) {
                    shapes.add(new ArrayList<String>(parts.values()));
                }
                Set<List<String>> missingIndexes = new HashSet<List<String>>(REQUIRED_UNIQUE_INDEXES.get(table));
                missingIndexes.removeAll(shapes);
                if (!missingIndexes.isEmpty()) {
                    throw new BootstrapException("权限表 " + table + " 缺少唯一索引：" + missingIndexes);
                }
            }
        }

        private boolean ensureAdminRole() throws SQLException {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT enable, is_system, is_deleted FROM `role` WHERE role_id = ?")) {
                query.setString(1, SYSTEM_ADMIN_ROLE_ID);
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        if (result.getInt(1) != 1 || result.getInt(2) != 1 || result.getInt(3) != 0) {
                            throw new BootstrapException("既有 SYSTEM_ADMIN 不是启用、未删除的系统角色，拒绝覆盖");
                        }
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO `role` (role_id, role_name, description, enable, is_system, created_by, is_deleted) "
                            + "VALUES (?, ?, ?, 1, 1, 'SYSTEM', 0)")) {
                insert.setString(1, SYSTEM_ADMIN_ROLE_ID);
                insert.setString(2, "系统管理员");
                insert.setString(3, "内置权限系统管理员");
                insert.executeUpdate();
                return true;
            }
        }

        private int ensureResources() throws SQLException {
            int created = 0;
            for (ResourceSeed resource : input.resources) {
                boolean exists = false;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT origin, type FROM `resource` WHERE `key` = ?")) {
                    query.setString(1, resource.key);
                    try (ResultSet result = query.executeQuery()) {
                        if (result.next()) {
                            exists = true;
                            if (SYSTEM_MANAGEMENT_RESOURCE.equals(resource.key)
                                    && (!"system".equals(result.getString(1)) || !"system".equals(result.getString(2)))) {
                                throw new BootstrapException(
                                        "既有 system_authorization_management 不是 system/system，拒绝覆盖");
                            }
                        }
                    }
                }
                if (exists) {
                    continue;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO `resource` (`key`, name, description, origin, type, target_resource_ref) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, resource.key);
                    insert.setString(2, resource.name);
                    insert.setString(3, resource.description);
                    insert.setString(4, resource.origin);
                    insert.setString(5, resource.type);
                    insert.setString(6, resource.targetResourceRef);
                    insert.executeUpdate();
                    created++;
                }
            }
            return created;
        }

        private boolean ensureSystemRoleResource() throws SQLException {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM role_resource WHERE role_id = ? AND resource_key = ?")) {
                query.setString(1, SYSTEM_ADMIN_ROLE_ID);
                query.setString(2, SYSTEM_MANAGEMENT_RESOURCE);
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO role_resource (role_id, resource_key, created_by) VALUES (?, ?, 'SYSTEM')")) {
                insert.setString(1, SYSTEM_ADMIN_ROLE_ID);
                insert.setString(2, SYSTEM_MANAGEMENT_RESOURCE);
                insert.executeUpdate();
                return true;
            }
        }

        private int ensureInitialAdministrators() throws SQLException {
            int created = 0;
            for (String subject : input.administratorSubjects) {
                boolean exists;
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT 1 FROM role_member WHERE role_id = ? AND member_id = ?")) {
                    query.setString(1, SYSTEM_ADMIN_ROLE_ID);
                    query.setString(2, subject);
                    try (ResultSet result = query.executeQuery()) {
                        exists = result.next();
                    }
                }
                if (exists) {
                    continue;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO role_member (role_id, member_id, member_name, created_by) "
                                + "VALUES (?, ?, ?, 'SYSTEM')")) {
                    insert.setString(1, SYSTEM_ADMIN_ROLE_ID);
                    insert.setString(2, subject);
                    insert.setString(3, subject);
                    insert.executeUpdate();
                    created++;
                }
            }
            return created;
        }
    }

    private static List<String> splitDdl(Path ddlPath) {
        String ddl;
        try {
            ddl = new String(Files.readAllBytes(ddlPath), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BootstrapException("DDL 文件不存在或不可读：" + ddlPath, exception);
        }
        List<String> statements = new ArrayList<String>();
        for (String statement : ddl.split(";")) {
            if (!statement.trim().isEmpty()) {
                statements.add(statement.trim());
            }
        }
        if (statements.size() != 4) {
            throw new BootstrapException("DDL 必须恰好包含四条建表语句，实际为 " + statements.size() + " 条");
        }
        return statements;
    }

    private static final class Arguments {
        private final Path technicalPlan;
        private final Path application;
        private final Path ddl;
        private final Path applicationYml;
        private final String profile;
        private final String dbUrl;
        private final String dbUser;
        private final boolean dryRun;

        private Arguments(Path technicalPlan, Path application, Path ddl, Path applicationYml,
                          String dbUrl, String dbUser, boolean dryRun, String profile) {
            this.technicalPlan = technicalPlan;
            this.application = application;
            this.ddl = ddl;
            this.applicationYml = applicationYml;
            this.profile = profile;
            this.dbUrl = dbUrl;
            this.dbUser = dbUser;
            this.dryRun = dryRun;
        }

        private static Arguments parse(String[] args) {
            Path backend = Paths.get("").toAbsolutePath().normalize();
            Path demo = backend.getParent();
            Path technicalPlan = demo.resolve(".devagentstudio/plans/technical-plan.json");
            Path application = demo.resolve(".devagentstudio/application.json");
            Path ddl = backend.resolve("sql/authorization/schema.sql");
            Path applicationYml = backend.resolve("src/main/resources/application.yml");
            String profile = null;
            String dbUrl = null;
            String dbUser = null;
            boolean dryRun = false;
            for (String argument : args) {
                if ("--dry-run".equals(argument)) {
                    dryRun = true;
                } else if (argument.startsWith("--technical-plan=")) {
                    technicalPlan = Paths.get(argument.substring("--technical-plan=".length()));
                } else if (argument.startsWith("--application=")) {
                    application = Paths.get(argument.substring("--application=".length()));
                } else if (argument.startsWith("--ddl=")) {
                    ddl = Paths.get(argument.substring("--ddl=".length()));
                } else if (argument.startsWith("--application-yml=")) {
                    applicationYml = Paths.get(argument.substring("--application-yml=".length()));
                } else if (argument.startsWith("--profile=")) {
                    profile = argument.substring("--profile=".length());
                } else if (argument.startsWith("--db-url=")) {
                    dbUrl = argument.substring("--db-url=".length());
                } else if (argument.startsWith("--db-user=")) {
                    dbUser = argument.substring("--db-user=".length());
                } else {
                    throw new BootstrapException("未知 Bootstrap 参数");
                }
            }
            return new Arguments(technicalPlan, application, ddl, applicationYml,
                    dbUrl, dbUser, dryRun, profile);
        }
    }

    private static final class BootstrapInput {
        private final List<ResourceSeed> resources;
        private final List<String> administratorSubjects;
        private final String fingerprint;

        private BootstrapInput(List<ResourceSeed> resources, List<String> administratorSubjects,
                               String fingerprint) {
            this.resources = resources;
            this.administratorSubjects = administratorSubjects;
            this.fingerprint = fingerprint;
        }
    }

    private static final class ResourceSeed {
        private final String key;
        private final String name;
        private final String description;
        private final String origin;
        private final String type;
        private final String targetResourceRef;

        private ResourceSeed(String key, String name, String description, String origin,
                             String type, String targetResourceRef) {
            this.key = key;
            this.name = name;
            this.description = description;
            this.origin = origin;
            this.type = type;
            this.targetResourceRef = targetResourceRef;
        }
    }

    private static final class BootstrapException extends RuntimeException {
        private BootstrapException(String message) {
            super(message);
        }

        private BootstrapException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
