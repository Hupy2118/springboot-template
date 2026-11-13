package com.cmbchina.managedb.domain;

import com.cmb.bee.commons.exceptions.BizException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

/**
 * 解析与元数据读取共用的规范化规则，不执行 SQL。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SchemaValues {
    /**
     * 规范化类型，拒绝未覆盖语义的类型。
     */
    public static String type(String raw) {
        String t =
                raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").replace("integer", "int").replace("boolean", "tinyint").replace("bool", "tinyint")
                        .replace("numeric", "decimal");
        if (!t.matches(
                "(tinyint|smallint|mediumint|int|bigint|decimal|float|double|char|varchar|binary|varbinary|tinytext|text|mediumtext|longtext|tinyblob|blob|mediumblob|longblob|date|time|datetime|timestamp|bit)(\\([0-9]+(,[0-9]+)?\\))?(unsigned)?")) {
            throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
        }
        if (t.matches("(tinyint|smallint|mediumint|int|bigint)\\([0-9]+\\)(unsigned)?")) {
            t = t.replaceAll("\\([0-9]+\\)", "");
        }
        if (t.matches("(time|datetime|timestamp)")) {
            t += "(0)";
        }
        if (t.equals("decimal")) {
            t = "decimal(10,0)";
        }
        if (t.equals("decimalunsigned")) {
            t = "decimal(10,0)unsigned";
        }
        if (t.equals("char") || t.equals("binary")) {
            t += "(1)";
        }
        if (t.matches("decimal\\([0-9]+\\)(unsigned)?")) {
            t = t.replace(")", ",0)");
        }
        if (t.equals("bit")) {
            t = "bit(1)";
        }
        if (t.matches("(float|double)\\(.*")) {
            throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
        }
        return t;
    }

    /**
     * 数值默认值按数值比较，文本默认值保持原样。
     */
    public static String literal(String value, String type) {
        if (type.matches("^(tinyint|smallint|mediumint|int|bigint|decimal|float|double).*")) {
            try {
                return "L:" + new BigDecimal(value).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
            }
        }
        if (type.startsWith("bit(")) {
            try {
                String v = value.toLowerCase(Locale.ROOT);
                return "L:" + (v.matches("b'[01]+'") ? new BigInteger(v.substring(2, v.length() - 1), 2) : new BigInteger(v)).toString();
            } catch (NumberFormatException e) {
                throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
            }
        }
        return "L:" + value;
    }

    /**
     * 规范时间函数和精度，其他表达式不默默通过。
     */
    public static String timestamp(String value) {
        String v = value.toUpperCase(Locale.ROOT).replace(" ", "");
        if (v.equals("CURRENT_TIMESTAMP") || v.equals("CURRENT_TIMESTAMP()")) {
            return "CURRENT_TIMESTAMP(0)";
        }
        if (v.matches("CURRENT_TIMESTAMP\\([0-6]\\)")) {
            return v;
        }
        throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
    }

    /**
     * 外键缺省及等价禁止动作归一。
     */
    public static String action(String value) {
        return value == null || value.equalsIgnoreCase("NO ACTION") ? "RESTRICT" : value.toUpperCase(Locale.ROOT);
    }
}
