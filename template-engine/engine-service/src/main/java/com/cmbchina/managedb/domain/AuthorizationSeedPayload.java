package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.valueobject.DatasourceKey;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** 唯一支持的 Seed 数据及固定写入白名单。 */
@Getter
public final class AuthorizationSeedPayload {
    public static final String PURPOSE = "AUTHORIZATION_SEED_V1";
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_RESOURCES = 500;
    public static final int MAX_ADMINS = 100;
    public static final int MAX_ROWS = MAX_RESOURCES + MAX_ADMINS + 2;
    private static final String ADMIN = "SYSTEM_ADMIN";
    private static final String SYSTEM_RESOURCE = "system_authorization_management";
    private static final String SYSTEM_REF = "system:authorization_management";
    private static final Pattern PAGE_KEY = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");
    private static final Pattern ACTION_REF = Pattern.compile("action:[a-z][a-z0-9_]*:[a-z][a-z0-9_]*");

    /** 长度为零代表整数；列顺序用于构造固定 SQL。 */
    @Getter
    public enum Table {
        ROLE("role", 1, 1, Arrays.asList("role_id", "enable", "is_system", "is_deleted"),
                "role_id:32", "role_name:32", "description:128",
                "enable:0", "is_system:0", "is_deleted:0", "created_by:32"),
        RESOURCE("resource", MAX_RESOURCES, 1, Arrays.asList("key", "origin", "type", "target_resource_ref"),
                "key:64", "name:32", "description:128",
                "origin:8", "type:9", "target_resource_ref:255"),
        ROLE_RESOURCE("role_resource", 1, 2, Arrays.asList("role_id", "resource_key"),
                "role_id:32", "resource_key:64", "created_by:32"),
        ROLE_MEMBER("role_member", MAX_ADMINS, 2, Arrays.asList("role_id", "member_id"),
                "role_id:32", "member_id:32",
                "member_name:32", "created_by:32");

        private final String suffix;
        private final int maxRows;
        private final List<String> naturalKey;
        private final Map<String, Integer> columns;
        private final List<String> identityColumns;

        /** 固定自然键、列类型和已存在记录需要精确核对的列。 */
        Table(String suffix, int maxRows, int keySize, List<String> identityColumns, String... fields) {
            this.suffix = suffix;
            this.maxRows = maxRows;
            Map<String, Integer> sizes = new LinkedHashMap<>();
            for (String field : fields) {
                String[] parts = field.split(":");
                sizes.put(parts[0], Integer.parseInt(parts[1]));
            }
            columns = Collections.unmodifiableMap(sizes);
            List<String> names = new ArrayList<>(sizes.keySet());
            naturalKey = Collections.unmodifiableList(names.subList(0, keySize));
            this.identityColumns = Collections.unmodifiableList(identityColumns);
        }

        /** 表名只能由已校验的资源标识和固定后缀组成。 */
        public String physicalName(String key) {
            return new DatasourceKey(key).getValue() + "_" + suffix;
        }
    }

    private final String datasourceKey;
    private final List<List<Map<String, Object>>> rows;
    private final List<String> tableNames;
    private final String fingerprint;

    /** 验证完整批次，复制为不可修改的按表和请求行顺序排列的数据。 */
    public AuthorizationSeedPayload(String datasourceKey, String purpose, List<String> tableNames,
                                    List<List<Map<String, Object>>> rows) {
        this.datasourceKey = new DatasourceKey(datasourceKey).getValue();
        if (!PURPOSE.equals(purpose) || tableNames.size() != Table.values().length
                || rows.size() != Table.values().length) {
            throw invalid();
        }
        List<List<Map<String, Object>>> validated = new ArrayList<>();
        for (Table table : Table.values()) {
            if (!table.physicalName(datasourceKey).equals(tableNames.get(table.ordinal()))) {
                throw invalid();
            }
            validated.add(validateRows(table, rows.get(table.ordinal())));
        }
        this.rows = Collections.unmodifiableList(validated);
        this.tableNames = Collections.unmodifiableList(new ArrayList<>(tableNames));
        fingerprint = computeFingerprint();
    }

    /** 幂等键使用严格小写十六进制。 */
    public static String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        return value;
    }

    /** 校验列集合、数据类型、自然键唯一性与系统资源基数。 */
    private static List<Map<String, Object>> validateRows(Table table, List<Map<String, Object>> input) {
        if (input.isEmpty() || input.size() > table.maxRows) {
            throw invalid();
        }
        Set<List<Object>> unique = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        int systemResources = 0;
        for (Map<String, Object> row : input) {
            if (!row.keySet().equals(table.columns.keySet())) {
                throw invalid();
            }
            Map<String, Object> canonical = new TreeMap<>();
            table.columns.forEach((name, size) -> {
                boolean allowEmpty = table == Table.RESOURCE && "description".equals(name);
                canonical.put(name, validateValue(row.get(name), size, allowEmpty));
            });
            List<Object> key = new ArrayList<>();
            table.naturalKey.forEach(name -> key.add(canonical.get(name)));
            if (!unique.add(key)) {
                throw invalid();
            }
            validateIdentity(table, canonical);
            if (table == Table.RESOURCE && SYSTEM_RESOURCE.equals(canonical.get("key"))) {
                systemResources++;
            }
            result.add(Collections.unmodifiableMap(canonical));
        }
        if (table == Table.RESOURCE && systemResources != 1) {
            throw invalid();
        }
        return Collections.unmodifiableList(result);
    }

    /** 只接受规范整数和有效 Unicode 字符串，仅指定列可保留空串。 */
    private static Object validateValue(Object value, int size, boolean allowEmpty) {
        if (size == 0 && value instanceof Integer) {
            return value;
        }
        if (size == 0 || !(value instanceof String)) {
            throw invalid();
        }
        String text = (String) value;
        if (text.isEmpty() && allowEmpty) {
            return text;
        }
        if (text.isEmpty() || !text.equals(text.trim()) || text.codePointCount(0, text.length()) > size
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF))
                || text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw invalid();
        }
        return text;
    }

    /** 禁止写入其他角色、授权关系和冒用审计身份。 */
    private static void validateIdentity(Table table, Map<String, Object> row) {
        if (table == Table.RESOURCE) {
            validateResource(row);
            return;
        }
        if (!ADMIN.equals(row.get("role_id")) || !"SYSTEM".equals(row.get("created_by"))) {
            throw invalid();
        }
        if (table == Table.ROLE && (!Integer.valueOf(1).equals(row.get("enable"))
                || !Integer.valueOf(1).equals(row.get("is_system")) || !Integer.valueOf(0).equals(row.get("is_deleted")))) {
            throw invalid();
        }
        if (table == Table.ROLE_RESOURCE && !SYSTEM_RESOURCE.equals(row.get("resource_key"))) {
            throw invalid();
        }
    }

    /** 系统引用固定；页面绑定自身资源键，操作按 action 引用校验且保留原值。 */
    private static void validateResource(Map<String, Object> row) {
        if (SYSTEM_RESOURCE.equals(row.get("key"))) {
            if (!"system".equals(row.get("origin")) || !"system".equals(row.get("type"))
                    || !SYSTEM_REF.equals(row.get("target_resource_ref"))) {
                throw invalid();
            }
            return;
        }
        if (!"business".equals(row.get("origin"))) {
            throw invalid();
        }
        String type = (String) row.get("type");
        String key = (String) row.get("key");
        String ref = (String) row.get("target_resource_ref");
        if ("page".equals(type) && PAGE_KEY.matcher(key).matches() && ("page:" + key).equals(ref)) {
            return;
        }
        if ("operation".equals(type) && ACTION_REF.matcher(ref).matches()) {
            return;
        }
        throw invalid();
    }

    /** 使用长度前缀编码保持跨客户端一致，无需依赖 JSON 库的转义行为。 */
    private String computeFingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addText(digest, PURPOSE);
            addText(digest, datasourceKey);
            for (Table table : Table.values()) {
                addText(digest, tableNames.get(table.ordinal()));
                List<Map<String, Object>> tableRows = rows.get(table.ordinal());
                addInt(digest, tableRows.size());
                for (Map<String, Object> row : tableRows) {
                    row.forEach((column, value) -> {
                        addText(digest, column);
                        addText(digest, value.toString());
                    });
                }
            }
            return String.format(Locale.ROOT, "%064x", new java.math.BigInteger(1, digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境不支持 SHA-256");
        }
    }

    /** 先写 UTF-8 字节数再写字节。 */
    private static void addText(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        addInt(digest, bytes.length);
        digest.update(bytes);
    }

    /** 写入四字节大端整数。 */
    private static void addInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    /** 验证失败不携带任何原始字段或数据。 */
    private static BizException invalid() {
        return new BizException(ResourceErrorCode.INVALID_ARGUMENT);
    }
}
