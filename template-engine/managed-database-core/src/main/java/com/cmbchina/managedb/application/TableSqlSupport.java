package com.cmbchina.managedb.application;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.DatabaseSchemaPort;
import com.cmbchina.managedb.domain.ParsedCreateTable;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableBinding;
import com.cmbchina.managedb.domain.TableStructure;
import com.cmbchina.managedb.domain.TableSyncSettings;
import com.cmbchina.managedb.domain.repository.TableOperationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 三个用例共用解析和引用校验，业务依赖仍指向领域契约。
 */
@Service
@RequiredArgsConstructor
public class TableSqlSupport {
    private final DatabaseSchemaPort schemas;
    private final TableOperationRepository repository;
    private final TableSyncSettings settings;

    /**
     * 解析后先校验显式主键，再规范化表位置；原文保持不变，不自动补主键。
     */
    public ParsedCreateTable parse(long datasourceId, String sql) {
        settings.validateSql(sql);
        ParsedCreateTable parsed = schemas.parseCreate(sql);
        parsed.getStructure().requireExplicitPrimaryKey();
        return new ParsedCreateTable(sql,
                parsed.getStructure().canonicalTables(name -> schemas.tableKey(datasourceId, name)));
    }

    /**
     * 外键只能引用本应用建成表或本次新表自身，先检查归属再读结构。
     */
    public void requireReferences(String datasourceKey, long datasourceId, TableStructure expected) {
        for (TableStructure.ForeignKey key : expected.getForeignKeys()) {
            TableStructure target = resolveTarget(datasourceKey, datasourceId, expected, key);
            verifyColumnCompatibility(expected, key, target);
            requireTargetIndexed(key, target);
        }
    }

    /**
     * 解析外键目标：自引用直接复用本次结构，其余必须归属本应用且已建成。
     */
    private TableStructure resolveTarget(String datasourceKey, long datasourceId,
                                         TableStructure expected, TableStructure.ForeignKey key) {
        if (key.getTargetTable().equals(expected.getTableName())) {
            return expected;
        }
        TableBinding binding = repository.binding(datasourceId, key.getTargetTable())
                .orElseThrow(() -> new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT));
        binding.requireOwner(datasourceKey, datasourceId);
        if (binding.getStatus() != TableBinding.Status.CREATED) {
            throw new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT);
        }
        return schemas.readTable(datasourceId, key.getTargetTable())
                .orElseThrow(() -> new BizException(ResourceErrorCode.TABLE_BINDING_CONFLICT));
    }

    /**
     * 逐列核对类型、字符集与排序规则一致。
     */
    private void verifyColumnCompatibility(TableStructure expected, TableStructure.ForeignKey key,
                                           TableStructure target) {
        for (int i = 0; i < key.getColumns().size(); i++) {
            TableStructure.Column local = expected.getColumns().get(key.getColumns().get(i));
            TableStructure.Column remote = target.getColumns().get(key.getTargetColumns().get(i));
            if (local == null || remote == null
                    || !local.getType().equals(remote.getType())
                    || local.getCharset() != null && !local.getCharset().equals(remote.getCharset())
                    || local.getCollation() != null && !local.getCollation().equals(remote.getCollation())) {
                throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
            }
        }
    }

    /**
     * 目标列必须被唯一键或普通索引以外的键覆盖。
     */
    private void requireTargetIndexed(TableStructure.ForeignKey key, TableStructure target) {
        boolean indexed = false;
        for (TableStructure.Index index : target.getIndexes()) {
            if (!index.getKind().equals("INDEX") && index.getColumns().equals(key.getTargetColumns())) {
                indexed = true;
            }
        }
        if (!indexed) {
            throw new BizException(ResourceErrorCode.SQL_UNSUPPORTED);
        }
    }
}
