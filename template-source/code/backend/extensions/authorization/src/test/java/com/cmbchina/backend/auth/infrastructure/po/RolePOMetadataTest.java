package com.cmbchina.backend.auth.infrastructure.po;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePOMetadataTest {

    @Test
    void generatedSelectDoesNotUseMysqlKeywordsAsAliases() {
        TableInfoHelper.remove(RolePO.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "RolePOMetadataTest");
        assistant.setCurrentNamespace("RolePOMetadataTest");
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, RolePO.class);

        String columns = tableInfo.getAllSqlSelect();
        assertTrue(columns.contains("is_system"), columns);
        assertTrue(columns.contains("is_deleted"), columns);
        assertFalse(columns.contains("is_system AS"), columns);
        assertFalse(columns.contains("is_deleted AS"), columns);
    }
}
