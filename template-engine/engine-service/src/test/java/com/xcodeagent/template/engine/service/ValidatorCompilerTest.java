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

class ValidatorCompilerTest {
    @Test
    void sortsAndCompilesEverySupportedValidatorType() {
        List<Map<String, Object>> templates = new ArrayList<Map<String, Object>>();
        templates.add(template("maven", 80, common("MAVEN_PACKAGE", "SANDBOX")));
        templates.add(template("post", 10, postcondition()));
        templates.add(template("file", 20, real("FILE_EXISTS", "path", "a.txt")));
        templates.add(template("structure", 30, real("STRUCTURE_CHECK", "path", "a.txt", "containsAll", Arrays.asList("x"))));
        templates.add(template("json", 40, real("JSON_STRUCTURE_CHECK", "path", "a.json", "pointer", "/x")));
        templates.add(template("npm-build", 50, common("NPM_BUILD", "SANDBOX")));
        templates.add(template("npm-test", 60, common("NPM_TEST", "SANDBOX")));
        templates.add(template("maven-test", 70, common("MAVEN_TEST", "SANDBOX")));
        templates.add(template("npm-build", 50, common("NPM_BUILD", "SANDBOX")));
        List<Map<String, Object>> compiled = new ValidatorCompiler().compile(result(templates));
        assertEquals(8, compiled.size());
        assertEquals("post", compiled.get(0).get("validatorId"));
        for (int index = 0; index < compiled.size(); index++) assertEquals(index, compiled.get(index).get("index"));
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
    private static Map<String, Object> postcondition() { return real("CAPABILITY_POSTCONDITION", "capabilityId", "login", "checks", Collections.singletonList(map("type", "FILE_EXISTS", "path", "a.txt"))); }
    private static Map<String, Object> common(String type, String executionMode) { return map("type", type, "executionMode", executionMode, "blocking", true, "timeoutSeconds", 1, "workingDirectory", "."); }
    private static Map<String, Object> real(String type, Object... more) { Map<String, Object> value = common(type, "REAL_WORKSPACE"); for (int index = 0; index < more.length; index += 2) value.put((String) more[index], more[index + 1]); return value; }
    private static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
}
