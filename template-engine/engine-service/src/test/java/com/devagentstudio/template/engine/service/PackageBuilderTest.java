package com.devagentstudio.template.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class PackageBuilderTest {
    @Test void generatedZipUsesPositiveFixedTimestampsAndIsIndependentOfTimezone() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        byte[] first = null;
        try {
            Map<String, byte[]> files = Collections.singletonMap("backend/src/main/resources/mapper/auth/RoleMemberMapper.xml",
                    "<mapper/>".getBytes(StandardCharsets.UTF_8));
            for (String zone : new String[] {"UTC", "Asia/Shanghai", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                byte[] actual = new PackageBuilder(new ObjectMapper(), null).generatedProject(files, Collections.<String, Object>emptyMap());
                if (first == null) first = actual;
                else assertArrayEquals(first, actual, "ZIP bytes changed with timezone " + zone);
                try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(actual))) {
                    ZipEntry entry;
                    int count = 0;
                    while ((entry = input.getNextEntry()) != null) {
                        assertTrue(entry.getTime() > 0L, entry.getName());
                        assertNull(entry.getExtra(), "DOS timestamps should not need timezone-specific extras");
                        count++;
                    }
                    assertEquals(2, count);
                }
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
