package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.v2.TemplateStateV2;
import com.xcodeagent.template.engine.core.v2.UpdateResult;
import com.xcodeagent.template.engine.core.v2.ValidationPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidatorCompilerTest {
    @Test
    void sortsAndCompilesEverySupportedValidatorType() {
        List<Map<String, Object>> templates = new ArrayList<Map<String, Object>>();
        templates.add(template("maven", 80, common("MAVEN_PACKAGE", "SANDBOX")));
        templates.add(template("post", 10, postcondition()));
        templates.add(template("file", 20, real("FILE_EXISTS", "path", "a.txt")));
        templates.add(template("structure", 30, real("STRUCTURE_CHECK", "path", "a.txt", "containsAll", Arrays.asList("x"))));
        templates.add(template("json", 40, real("JSON_STRUCTURE_CHECK", "path", "a.json", "pointer", "/x", "expected", "value")));
        templates.add(template("npm-build", 50, common("NPM_BUILD", "SANDBOX")));
        templates.add(template("npm-test", 60, common("NPM_TEST", "SANDBOX")));
        templates.add(template("maven-test", 70, common("MAVEN_TEST", "SANDBOX")));
        templates.add(template("npm-build", 50, common("NPM_BUILD", "SANDBOX")));
        List<Map<String, Object>> compiled = new ValidatorCompiler().compile(result(templates));
        assertEquals(8, compiled.size());
        assertEquals("post", compiled.get(0).get("validationId"));
        for (int index = 0; index < compiled.size(); index++) {
            Map<String, Object> item = compiled.get(index);
            assertEquals(index, item.get("index"));
            assertFalse(item.containsKey("validatorId"));
            assertFalse(item.containsKey("parameters"));
            assertTrue(item.containsKey("executionMode"));
            assertTrue(item.containsKey("blocking"));
            assertTrue(item.containsKey("timeoutSeconds"));
            assertTrue(item.containsKey("workingDirectory"));
            assertFalse(item.containsKey("order"));
            assertEquals(expectedWireFieldCount((String) item.get("type")), item.size());
        }
        Map<String, Object> postcondition = compiled.get(0);
        assertEquals("login", postcondition.get("capabilityId"));
        assertTrue(postcondition.get("checks") instanceof List);
        assertEquals("a.txt", compiled.get(1).get("path"));
        assertEquals(Arrays.asList("x"), compiled.get(2).get("containsAll"));
        assertEquals("/x", compiled.get(3).get("pointer"));
        assertEquals("value", compiled.get(3).get("expected"));
    }

    @Test
    void rejectsUnknownParametersAndConflictingDuplicates() {
        assertThrows(ServiceException.class, () -> new ValidatorCompiler().compile(result(Collections.singletonList(template("bad", 1, real("FILE_EXISTS", "path", "a", "extra", true))))));
        List<Map<String, Object>> conflict = Arrays.asList(template("same", 1, common("NPM_BUILD", "SANDBOX")), template("same", 2, common("NPM_BUILD", "SANDBOX")));
        assertThrows(ServiceException.class, () -> new ValidatorCompiler().compile(result(conflict)));
    }

    private static UpdateResult result(List<Map<String, Object>> validators) {
        TemplateStateV2 state = new TemplateStateV2("r", Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        return UpdateResult.change(Collections.emptyList(), Collections.emptyList(), state, new ValidationPlan(validators));
    }
    private static Map<String, Object> template(String id, int order, Map<String, Object> parameters) { return map("validatorId", id, "order", order, "parameters", parameters); }
    private static int expectedWireFieldCount(String type) {
        if ("CAPABILITY_POSTCONDITION".equals(type)) return 9;
        if ("FILE_EXISTS".equals(type)) return 8;
        if ("STRUCTURE_CHECK".equals(type)) return 9;
        if ("JSON_STRUCTURE_CHECK".equals(type)) return 10;
        return 7;
    }
    private static Map<String, Object> postcondition() { return real("CAPABILITY_POSTCONDITION", "capabilityId", "login", "checks", Collections.singletonList(map("type", "FILE_EXISTS", "path", "a.txt"))); }
    private static Map<String, Object> common(String type, String executionMode) { return map("type", type, "executionMode", executionMode, "blocking", true, "timeoutSeconds", 1, "workingDirectory", "."); }
    private static Map<String, Object> real(String type, Object... more) { Map<String, Object> value = common(type, "REAL_WORKSPACE"); for (int index = 0; index < more.length; index += 2) value.put((String) more[index], more[index + 1]); return value; }
    private static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
}
