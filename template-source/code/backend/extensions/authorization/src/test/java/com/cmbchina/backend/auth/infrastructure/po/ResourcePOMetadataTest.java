package com.cmbchina.backend.auth.infrastructure.po;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePOMetadataTest {

    @Test
    void generatedSelectQuotesKeyAndDoesNotCreateReservedAlias() {
        TableInfoHelper.remove(ResourcePO.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "ResourcePOMetadataTest");
        assistant.setCurrentNamespace("ResourcePOMetadataTest");
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, ResourcePO.class);

        String columns = tableInfo.getAllSqlSelect();
        assertTrue(columns.contains("`key`"), columns);
        assertFalse(columns.contains(" AS key"), columns);
        assertFalse(columns.contains(" AS resourceKey"), columns);
    }
}
