package com.cmbchina.managedb.infrastructure.datasource;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.ParsedCreateTable;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.SqlExecutionResult;
import com.cmbchina.managedb.domain.TableMetadata;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.infrastructure.sql.MySqlSchemaAccess;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 进程级目标挂载注册表，内部连接池不是 Spring 管理库 DataSource Bean。
 */
public final class ConfiguredDatabaseSchemas implements DatabaseSchemaPort, AutoCloseable {
    private final Map<Long, HikariDataSource> pools = new HashMap<>();
    private final Map<Long, String> schemas = new HashMap<>();
    private final int configuredCount;
    private final int validationSeconds;
    private final MySqlSchemaAccess sqlAccess;

    /**
     * 先校验全部配置再初始化池，连接失败的挂载不参与新应用分配。
     */
    public ConfiguredDatabaseSchemas(DatabaseMountProperties properties,
                                     int timeoutMillis, String sslMode, int sqlSeconds, int metadataSeconds, int networkMillis) {
        if (timeoutMillis < 1000 || sqlSeconds < 1 || metadataSeconds < 1 || networkMillis <= sqlSeconds * 1000L || !allowedSslMode(sslMode)) {
            throw new IllegalArgumentException("挂载超时或 TLS 模式配置无效");
        }
        List<DatabaseMountProperties.Mount> mounts = properties.getList();
        if (mounts == null) {
            throw new IllegalArgumentException("挂载列表配置无效");
        }
        validate(mounts);
        configuredCount = mounts.size();
        validationSeconds = Math.max(1, timeoutMillis / 1000);
        sqlAccess = new MySqlSchemaAccess(this, sqlSeconds, metadataSeconds);
        try {
            for (DatabaseMountProperties.Mount mount : mounts) {
                HikariDataSource pool = createPool(mount, timeoutMillis, sslMode, networkMillis);
                if (accessible(pool, mount.getDbName())) {
                    pools.put(mount.getDatasourceId(), pool);
                    schemas.put(mount.getDatasourceId(), mount.getDbName());
                } else {
                    pool.close();
                }
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /**
     * 限定驱动认可的 TLS 模式，默认由部署配置要求校验目标身份。
     */
    private static boolean allowedSslMode(String value) {
        return "DISABLED".equals(value) || "PREFERRED".equals(value)
                || "REQUIRED".equals(value) || "VERIFY_CA".equals(value)
                || "VERIFY_IDENTITY".equals(value);
    }

    /**
     * 校验重复 ID、重复位置和必填项，错误消息不包含原配置内容。
     */
    private static void validate(List<DatabaseMountProperties.Mount> mounts) {
        Set<Long> ids = new HashSet<>();
        Set<String> locations = new HashSet<>();
        for (DatabaseMountProperties.Mount mount : mounts) {
            if (mount == null || mount.getDatasourceId() == null || mount.getDatasourceId() <= 0
                    || !ids.add(mount.getDatasourceId()) || blank(mount.getHost())
                    || !mount.getHost().matches("[A-Za-z0-9._:-]+")
                    || mount.getPort() == null || mount.getPort() <= 0 || mount.getPort() > 65535
                    || blank(mount.getDbName()) || mount.getDbName().length() > 64
                    || blank(mount.getUsername()) || blank(mount.getPassword())) {
                throw new IllegalArgumentException("数据库挂载配置无效或 ID 重复");
            }
            if (mount.getPassword().startsWith("BEE_ENC_")) {
                throw new IllegalArgumentException("挂载密码必须由部署侧解密后提供");
            }
            String location = mount.getHost().toLowerCase(Locale.ROOT) + ":"
                    + mount.getPort() + "/" + mount.getDbName();
            if (!locations.add(location)) {
                throw new IllegalArgumentException("同一数据库位置不能重复挂载");
            }
        }
    }

    /**
     * 判定必须由部署显式提供的字符串是否为空。
     */
    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 创建有界池和驱动超时，不向 URL 拼接账号密码或启用自动建库。
     */
    private static HikariDataSource createPool(DatabaseMountProperties.Mount mount,
                                               int timeoutMillis, String sslMode, int networkMillis) {
        HikariConfig config = new HikariConfig();
        String database;
        try {
            database = URLEncoder.encode(mount.getDbName(), "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException("运行环境不支持 UTF-8");
        }
        String host = mount.getHost().contains(":") ? "[" + mount.getHost() + "]" : mount.getHost();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + mount.getPort() + "/" + database);
        config.setUsername(mount.getUsername());
        config.setPassword(mount.getPassword());
        config.setPoolName("managed-target-" + mount.getDatasourceId());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(timeoutMillis);
        config.setValidationTimeout(timeoutMillis);
        config.setInitializationFailTimeout(-1);
        config.setConnectionInitSql("SET SESSION time_zone = '+00:00'");
        config.addDataSourceProperty("connectTimeout", timeoutMillis);
        config.addDataSourceProperty("socketTimeout", networkMillis);
        config.addDataSourceProperty("allowMultiQueries", false);
        config.addDataSourceProperty("connectionTimeZone", "UTC");
        config.addDataSourceProperty("sslMode", sslMode);
        return new HikariDataSource(config);
    }

    /**
     * 精确核对配置 Schema 与连接有效性，不执行建库或建表 SQL。
     */
    private boolean accessible(HikariDataSource pool, String schema) {
        try (Connection connection = pool.getConnection()) {
            return schema.equals(connection.getCatalog()) && connection.isValid(validationSeconds);
        } catch (SQLException exception) {
            return false;
        }
    }

    /**
     * 返回不可修改的候选 ID 快照，不向外暴露失败挂载或连接配置。
     */
    @Override
    public List<Long> availableDatasourceIds() {
        if (configuredCount == 0) {
            throw new BizException(ResourceErrorCode.RESOURCE_SERVICE_NOT_CONFIGURED);
        }
        List<Long> result = new ArrayList<>(pools.keySet());
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    /**
     * 选中挂载失败就明确报错，不切换至其他位置。
     */
    @Override
    public void verifyAccessible(long datasourceId) {
        HikariDataSource pool = pools.get(datasourceId);
        if (pool == null || !accessible(pool, schemas.get(datasourceId))) {
            throw new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE);
        }
    }

    /**
     * 内部 SQL 适配使用固定池，领域端口不公开这个连接方法。
     */
    public Connection openConnection(long datasourceId) throws SQLException {
        HikariDataSource pool = pools.get(datasourceId);
        if (pool == null) {
            throw new SQLException("数据库挂载不可用", "08001");
        }
        Connection c = pool.getConnection();
        if (!schemas.get(datasourceId).equals(c.getCatalog())) {
            c.close();
            throw new SQLException("目标 Schema 不一致", "08001");
        }
        return c;
    }

    /**
     * 仅向基础设施返回当前固定 Schema，不落库或作为用户结果。
     */
    public String schemaName(long datasourceId) {
        String schema = schemas.get(datasourceId);
        if (schema == null) {
            throw new BizException(ResourceErrorCode.DATABASE_UNAVAILABLE);
        }
        return schema;
    }

    /**
     * 解析原文，不访问数据库。
     */
    @Override
    public ParsedCreateTable parseCreate(String sql) {
        return sqlAccess.parse(sql);
    }

    /**
     * 按服务器规则规范位置键。
     */
    @Override
    public String tableKey(long datasourceId, String name) {
        return sqlAccess.tableKey(datasourceId, name);
    }

    /**
     * 精确读取真实表。
     */
    @Override
    public Optional<TableStructure> readTable(long datasourceId, String name) {
        return sqlAccess.read(datasourceId, name);
    }

    /**
     * 精确读取供 Studio 展示和 API 映射使用的真实元数据。
     */
    @Override
    public Optional<TableMetadata> readTableMetadata(long datasourceId, String name) {
        return sqlAccess.metadata(datasourceId, name);
    }

    /**
     * 检查其他表的引用。
     */
    @Override
    public boolean hasIncomingReferences(long datasourceId, String name) {
        return sqlAccess.incoming(datasourceId, name);
    }

    /**
     * 执行单条受控 SQL。
     */
    @Override
    public SqlExecutionResult execute(long datasourceId, String sql) {
        return sqlAccess.execute(datasourceId, sql);
    }

    /**
     * 应用关闭或初始化异常时释放已创建的目标连接池。
     */
    @Override
    public void close() {
        for (HikariDataSource pool : pools.values()) {
            pool.close();
        }
    }
}
