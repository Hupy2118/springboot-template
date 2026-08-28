package com.cmbchina.backend.auth.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
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
            Arguments arguments = Arguments.parse(args);
            BootstrapInput input = loadBootstrapInput(arguments.technicalPlan, arguments.application);
            if (arguments.dryRun) {
                System.out.printf(
                        "Dry-run 成功：fingerprint=%s，resources=%d，initialAdministrators=%d，业务 role_resource 将不会初始化%n",
                        input.fingerprint, input.resources.size(), input.administratorSubjects.size());
                return;
            }

            DataSourceSettings yamlSettings = loadDataSourceSettings(arguments.applicationYml);
            Class.forName("com.mysql.cj.jdbc.Driver");
            String dbUrl = firstNonBlank(arguments.dbUrl, yamlSettings.url);
            String dbUser = firstNonBlank(arguments.dbUser, yamlSettings.username);
            String dbPassword = yamlSettings.password == null ? "" : yamlSettings.password;

            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
                new BootstrapExecutor(connection, arguments.ddl, input).run();
            }
        } catch (BootstrapException exception) {
            System.err.println("Bootstrap 失败：" + exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Bootstrap 失败：" + exception.getClass().getSimpleName() + ": " + exception.getMessage());
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
            throw new BootstrapException("无法读取初始化输入 JSON：" + exception.getMessage(), exception);
        }

        if (!"confirmed".equals(textValue(technicalPlan.get("confirmation_status")))) {
            throw new BootstrapException("TechnicalPlan 尚未确认，拒绝初始化权限数据");
        }
        JsonNode manifest = technicalPlan.get("authorization_manifest");
        if (manifest == null || !manifest.isObject()) {
            throw new BootstrapException("TechnicalPlan.authorization_manifest 缺失");
        }
        if (!"authorization-manifest.v2".equals(textValue(manifest.get("schema_version")))) {
            throw new BootstrapException("仅支持 authorization-manifest.v2");
        }
        if (!manifest.path("enabled").asBoolean(false)) {
            throw new BootstrapException("authorization_manifest.enabled 不是 true");
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
                    optionalText(item.get("targetResourceRef"), prefix + ".targetResourceRef", 255));
            if (!resourceKeys.add(resource.key)) {
                throw new BootstrapException("manifest 存在重复 resourceKey：" + resource.key);
            }
            if (SYSTEM_MANAGEMENT_RESOURCE.equals(resource.key)) {
                systemResourceCount++;
                if (!"system".equals(resource.origin) || !"system".equals(resource.type)) {
                    throw new BootstrapException("system_authorization_management 必须为 origin=system,type=system");
                }
            }
            resources.add(resource);
        }
        if (systemResourceCount != 1) {
            throw new BootstrapException("manifest 必须恰好包含一个 system_authorization_management");
        }

        JsonNode authorization = application.get("authorization");
        if (authorization == null || !authorization.isObject() || !authorization.path("enabled").asBoolean(false)) {
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
        return new BootstrapInput(resources, subjects, fingerprint);
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

    private static DataSourceSettings loadDataSourceSettings(Path applicationYml) {
        if (!Files.isRegularFile(applicationYml)) {
            throw new BootstrapException("后端数据库配置文件不存在：" + applicationYml);
        }
        Map<?, ?> root;
        try (InputStream input = Files.newInputStream(applicationYml)) {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map)) {
                throw new BootstrapException("application.yml 根节点必须是对象");
            }
            root = (Map<?, ?>) loaded;
        } catch (IOException exception) {
            throw new BootstrapException("无法读取后端数据库配置：" + applicationYml, exception);
        }
        Map<?, ?> spring = childMap(root, "spring");
        Map<?, ?> datasource = childMap(spring, "datasource");
        String url = resolveEnvironmentPlaceholder(stringValue(datasource.get("url"), "spring.datasource.url"));
        String username = resolveEnvironmentPlaceholder(
                stringValue(datasource.get("username"), "spring.datasource.username"));
        String password = resolveEnvironmentPlaceholder(
                stringValue(datasource.get("password"), "spring.datasource.password"));
        if (url == null || url.trim().isEmpty()) {
            throw new BootstrapException("application.yml 缺少 spring.datasource.url");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new BootstrapException("application.yml 缺少 spring.datasource.username");
        }
        return new DataSourceSettings(url, username, password);
    }

    private static Map<?, ?> childMap(Map<?, ?> parent, String key) {
        Object child = parent.get(key);
        if (!(child instanceof Map)) {
            throw new BootstrapException("application.yml 缺少对象节点：" + key);
        }
        return (Map<?, ?>) child;
    }

    private static String stringValue(Object value, String field) {
        if (!(value instanceof String)) {
            throw new BootstrapException("application.yml 的 " + field + " 必须是字符串");
        }
        return (String) value;
    }

    private static String resolveEnvironmentPlaceholder(String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String expression = value.substring(2, value.length() - 1);
        int separator = expression.indexOf(':');
        String variable = separator >= 0 ? expression.substring(0, separator) : expression;
        String environmentValue = System.getenv(variable);
        if (environmentValue != null) {
            return environmentValue;
        }
        if (separator >= 0) {
            return expression.substring(separator + 1);
        }
        throw new BootstrapException("缺少 application.yml 引用的环境变量：" + variable);
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
                System.err.println("警告：释放 Bootstrap 锁失败：" + exception.getMessage());
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
        private final String dbUrl;
        private final String dbUser;
        private final boolean dryRun;

        private Arguments(Path technicalPlan, Path application, Path ddl, Path applicationYml,
                          String dbUrl, String dbUser, boolean dryRun) {
            this.technicalPlan = technicalPlan;
            this.application = application;
            this.ddl = ddl;
            this.applicationYml = applicationYml;
            this.dbUrl = dbUrl;
            this.dbUser = dbUser;
            this.dryRun = dryRun;
        }

        private static Arguments parse(String[] args) {
            Path backend = Paths.get("").toAbsolutePath().normalize();
            Path demo = backend.getParent();
            Path technicalPlan = demo.resolve(".xcodeagent/plans/technical-plan.json");
            Path application = demo.resolve(".xcodeagent/application.json");
            Path ddl = backend.resolve("docs/auth/sql/ddl.sql");
            Path applicationYml = backend.resolve("src/main/resources/application.yml");
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
                } else if (argument.startsWith("--db-url=")) {
                    dbUrl = argument.substring("--db-url=".length());
                } else if (argument.startsWith("--db-user=")) {
                    dbUser = argument.substring("--db-user=".length());
                } else {
                    throw new BootstrapException("未知参数：" + argument);
                }
            }
            return new Arguments(technicalPlan, application, ddl, applicationYml,
                    dbUrl, dbUser, dryRun);
        }
    }

    private static final class DataSourceSettings {
        private final String url;
        private final String username;
        private final String password;

        private DataSourceSettings(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
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
