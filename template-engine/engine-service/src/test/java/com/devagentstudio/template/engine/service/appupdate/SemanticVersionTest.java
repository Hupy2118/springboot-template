package com.devagentstudio.template.engine.service.appupdate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticVersionTest {
    private static final List<String> INVALID = Arrays.asList("", "1", "1.0", "v1.0.0", "01.0.0", "1.01.0",
            "1.0.01", "-1.0.0", "1.0.0-01", "1.0.0-beta.01", "1.0.0-", "1.0.0+", "1.0.0-a..b",
            "1.0.0+a..b", " 1.0.0", "1.0.0 ", "1.0.0\n", "1.0.0-中文", "1.0.0-a_b", "1.0.0+a_b");

    @Test
    void followsTheSemverSpecificationPrecedenceSequence() {
        List<String> ordered = Arrays.asList("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.9.0", "1.10.0", "2.0.0");
        for (int left = 0; left < ordered.size(); left++) {
            for (int right = 0; right < ordered.size(); right++) {
                assertEquals(Integer.signum(left - right), Integer.signum(SemanticVersion.parse(ordered.get(left))
                        .compareTo(SemanticVersion.parse(ordered.get(right)))), ordered.get(left) + " vs " + ordered.get(right));
            }
        }
    }

    @Test
    void ignoresMetadataAndAcceptsLeadingZeroesOnlyWhereAllowed() {
        assertEquals(0, SemanticVersion.parse("1.0.0+001").compareTo(SemanticVersion.parse("1.0.0+other")));
        assertEquals(0, SemanticVersion.parse("1.0.0-beta+001").compareTo(SemanticVersion.parse("1.0.0-beta")));
        assertNotNull(SemanticVersion.parse("0.0.0-01a.0.x-y.--+001"));
        assertTrue(SemanticVersion.parse("1.0.0-BETA").compareTo(SemanticVersion.parse("1.0.0-beta")) < 0);
    }

    @Test
    void comparesNumbersBeyondLongRange() {
        assertTrue(SemanticVersion.parse("18446744073709551616.0.0")
                .compareTo(SemanticVersion.parse("18446744073709551615.9.9")) > 0);
        assertTrue(SemanticVersion.parse("1.0.0-18446744073709551616")
                .compareTo(SemanticVersion.parse("1.0.0-18446744073709551615")) > 0);
        assertTrue(SemanticVersion.parse("1.18446744073709551616.0")
                .compareTo(SemanticVersion.parse("1.18446744073709551615.0")) > 0);
        assertTrue(SemanticVersion.parse("1.0.18446744073709551616")
                .compareTo(SemanticVersion.parse("1.0.18446744073709551615")) > 0);
    }

    @Test
    void rejectsInvalidVersionsAndKeepsTheOpenApiPatternConsistent() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/openapi/engine-service-v1.yaml")) {
            assertNotNull(input);
            JsonNode api = new ObjectMapper(new YAMLFactory()).readTree(input);
            Pattern schema = Pattern.compile(api.at("/components/schemas/SemanticVersion/pattern").asText());
            assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(null));
            for (String value : INVALID) {
                assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(value), value);
                assertFalse(schema.matcher(value).matches(), value);
            }
            for (String value : Arrays.asList("1.0.0", "0.0.0-01a.0.x-y.--+001", "1.0.0-beta.11+build.001",
                    "18446744073709551616.0.0", "1.0.0-18446744073709551616")) {
                assertNotNull(SemanticVersion.parse(value));
                assertTrue(schema.matcher(value).matches(), value);
            }
        }
    }
}
