package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ParsedCreateTable;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.domain.TableStructureComparator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实解析器固定 SQL 边界，不访问数据库。
 */
class MySqlCreateTableParserTest {
    private final MySqlCreateTableParser parser = new MySqlCreateTableParser();
    private final TableStructureComparator comparator = new TableStructureComparator();

    /**
     * 用户的原文不改写，主键、审计列和字符选项能够规范化。
     */
    @Test
    void parsesMysqlDefinitionWithoutRewriting() {
        String sql = "CREATE TABLE `orders` (`id` bigint NOT NULL AUTO_INCREMENT COMMENT '标识', "
                + "`title` varchar(64) DEFAULT 'A;B', `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                + "PRIMARY KEY (`id`), UNIQUE KEY `uk_title` (`title`)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单表';";
        ParsedCreateTable parsed = parser.parse(sql);
        assertEquals(sql, parsed.getSql());
        TableStructure structure = parsed.getStructure();
        assertEquals("orders", structure.getTableName());
        assertTrue(structure.getColumns().get("id").isAutoIncrement());
        assertFalse(structure.getColumns().get("id").isNullable());
        assertEquals("L:A;B", structure.getColumns().get("title").getDefaultValue());
        assertEquals("utf8mb4_bin", structure.getColumns().get("title").getCollation());
        assertEquals("CURRENT_TIMESTAMP(0)", structure.getColumns().get("created_at").getDefaultValue());
        assertEquals(2, structure.getIndexes().size());
    }

    /**
     * 整数字宽、主键隐式不可空、数值默认和注释差异不误判。
     */
    @Test
    void normalizesEquivalentDefinitions() {
        TableStructure expected =
                parser.parse("CREATE TABLE orders (id INTEGER PRIMARY KEY, qty int DEFAULT '01', title varchar(20) COMMENT '旧')").getStructure();
        TableStructure actual = parser.parse(
                        "CREATE TABLE orders (title varchar(20) DEFAULT NULL COMMENT '新', qty int(11) DEFAULT 1, id int NOT NULL, PRIMARY KEY(id))")
                .getStructure();
        assertTrue(comparator.compare(expected, actual).isEmpty());
    }

    /**
     * 索引前缀和外键动作参与比较，额外普通索引可以保留。
     */
    @Test
    void comparesIndexesAndReferences() {
        String base = "CREATE TABLE orders (id bigint PRIMARY KEY, parent_id bigint, title varchar(30), "
                + "KEY idx_title (title(10)), CONSTRAINT fk_parent FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE)";
        TableStructure expected = parser.parse(base).getStructure();
        assertEquals("title(10)", expected.getIndexes().get(1).getColumns().get(0));
        assertEquals("CASCADE", expected.getForeignKeys().get(0).getOnDelete());
        List<TableStructure.Index> indexes = new ArrayList<>(expected.getIndexes());
        indexes.add(new TableStructure.Index("INDEX", "extra", Collections.singletonList("parent_id"), "BTREE"));
        TableStructure actual = new TableStructure("orders", expected.getColumns(), indexes, expected.getForeignKeys(), expected.getOptions());
        assertTrue(comparator.compare(expected, actual).isEmpty());
        indexes.add(new TableStructure.Index("UNIQUE", "extra_unique", Collections.singletonList("title"), "BTREE"));
        assertFalse(comparator.compare(expected,
                new TableStructure("orders", expected.getColumns(), indexes, expected.getForeignKeys(), expected.getOptions())).isEmpty());
    }

    /**
     * 不允许无法完整校验的 SQL 绕过单表及固定挂载边界。
     */
    @Test
    void rejectsUnsafeOrUnsupportedStatements() {
        for (String sql : Arrays.asList(
                "CREATE TABLE orders(id bigint); DROP TABLE orders",
                "CREATE TABLE inner1.orders(id bigint)",
                "CREATE TABLE IF NOT EXISTS orders(id bigint)",
                "CREATE TABLE orders LIKE other_table",
                "CREATE TABLE orders AS SELECT 1",
                "CREATE TABLE orders(id bigint CHECK(id>0))",
                "CREATE TABLE orders(id bigint, parent_id bigint, FOREIGN KEY(parent_id) REFERENCES other_schema.parents(id))",
                "CREATE TABLE md_datasource_resource(id bigint)",
                "CREATE TABLE orders(id bigint) /*! ENGINE=InnoDB */",
                "DELETE FROM orders")) {
            BizException error = assertThrows(BizException.class, () -> parser.parse(sql));
            assertSame(ResourceErrorCode.SQL_UNSUPPORTED, error.getErrorCode());
        }
    }

    /**
     * 自增、默认值与明确的字符要求不同会阻止同步通过。
     */
    @Test
    void detectsMaterialColumnDifferences() {
        TableStructure expected =
                parser.parse("CREATE TABLE orders(id bigint PRIMARY KEY, title varchar(20) DEFAULT 'A') DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin")
                        .getStructure();
        TableStructure actual = parser.parse(
                        "CREATE TABLE orders(id bigint PRIMARY KEY AUTO_INCREMENT, title varchar(20) DEFAULT 'a') DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci")
                .getStructure();
        assertTrue(comparator.compare(expected, actual).stream().anyMatch(d -> d.getTarget().equals("id.autoIncrement")));
        assertTrue(comparator.compare(expected, actual).stream().anyMatch(d -> d.getTarget().equals("title.default")));
        assertTrue(comparator.compare(expected, actual).stream().anyMatch(d -> d.getTarget().equals("title.collation")));
    }
}
