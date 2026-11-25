package com.cmbchina.managedb.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 执行回读与启动检查共用的纯 Java 比较器。
 */
public final class TableStructureComparator {
    /**
     * 只比较支持的结构语义，额外普通索引不阻断启动。
     */
    public List<StructureDifference> compare(TableStructure expected, TableStructure actual) {
        List<StructureDifference> result = new ArrayList<>();
        for (String name : expected.getColumns().keySet()) {
            TableStructure.Column e = expected.getColumns().get(name), a = actual.getColumns().get(name);
            if (a == null) {
                difference(result, "COLUMN", name, "存在", "缺失");
                continue;
            }
            same(result, name + ".type", e.getType(), a.getType());
            same(result, name + ".nullable", e.isNullable(), a.isNullable());
            same(result, name + ".default", e.getDefaultValue(), a.getDefaultValue());
            same(result, name + ".autoIncrement", e.isAutoIncrement(), a.isAutoIncrement());
            same(result, name + ".onUpdate", e.getOnUpdate(), a.getOnUpdate());
            if (e.getCharset() != null) {
                same(result, name + ".charset", e.getCharset(), a.getCharset());
            }
            if (e.getCollation() != null) {
                same(result, name + ".collation", e.getCollation(), a.getCollation());
            }
        }
        for (String name : actual.getColumns().keySet()) {
            if (!expected.getColumns().containsKey(name)) {
                difference(result, "COLUMN", name, "不存在", "存在");
            }
        }
        List<TableStructure.Index> remaining = new ArrayList<>(actual.getIndexes());
        for (TableStructure.Index index : expected.getIndexes()) {
            TableStructure.Index match = null;
            for (TableStructure.Index candidate : remaining) {
                if (index.signature().equals(candidate.signature()) && (index.getName() == null || index.getName().equals(candidate.getName()))) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                difference(result, "INDEX", index.getName() == null ? index.getKind() : index.getName(), index.signature(), "缺失或不同");
            } else {
                remaining.remove(match);
            }
        }
        for (TableStructure.Index extra : remaining) {
            if (!"INDEX".equals(extra.getKind())) {
                difference(result, "INDEX", extra.getName(), "不存在", extra.signature());
            }
        }
        List<TableStructure.ForeignKey> foreign = new ArrayList<>(actual.getForeignKeys());
        for (TableStructure.ForeignKey key : expected.getForeignKeys()) {
            TableStructure.ForeignKey match = null;
            for (TableStructure.ForeignKey candidate : foreign) {
                if (key.signature().equals(candidate.signature()) && (key.getName() == null || key.getName().equals(candidate.getName()))) {
                    match = candidate;
                    break;
                }
            }
            if (match == null) {
                difference(result, "FOREIGN_KEY", key.getName(), key.signature(), "缺失或不同");
            } else {
                foreign.remove(match);
            }
        }
        for (TableStructure.ForeignKey extra : foreign) {
            difference(result, "FOREIGN_KEY", extra.getName(), "不存在", extra.signature());
        }
        for (Map.Entry<String, String> option : expected.getOptions().entrySet()) {
            same(result, "table." + option.getKey(), option.getValue(), actual.getOptions().get(option.getKey()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 统一记录字段属性差异。
     */
    private static void same(List<StructureDifference> result, String target, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            difference(result, "ATTRIBUTE", target, String.valueOf(expected), String.valueOf(actual));
        }
    }

    /**
     * 限制差异数量，避免结果无限增长。
     */
    private static void difference(List<StructureDifference> result, String category, String target, String expected, String actual) {
        if (result.size() < 100) {
            result.add(new StructureDifference(category, target, expected, actual));
        }
    }
}
