package com.xcodeagent.template.engine.service;

import com.xcodeagent.template.engine.core.v2.ModificationStrategy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireStrategyCompilerTest {
    @Test
    void compilesEverySupportedWireTypeWithStrictShape() {
        WireStrategyCompiler compiler = new WireStrategyCompiler();
        assertWire(compiler, "ADD_FILE", map(), "payload/a.txt");
        assertWire(compiler, "ENSURE_IMPORT", map("importStatement", "import X from 'x'"), null);
        assertWire(compiler, "ENSURE_NPM_DEPENDENCY", map("name", "x", "version", "1.0.0", "section", "dependencies"), null);
        assertWire(compiler, "ENSURE_MAVEN_DEPENDENCY", map("groupId", "g", "artifactId", "a", "version", "1.0.0"), null);
        assertWire(compiler, "TEXT_ANCHOR_INSERT", textParameters("// anchor", "text-marker"), null);
        for (String type : Arrays.asList("ENSURE_REACT_PROVIDER", "ENSURE_ROUTE", "ENSURE_MENU_ITEM", "ENSURE_SPRING_BEAN", "ENSURE_INTERCEPTOR"))
            assertWire(compiler, type, structuralParameters("structural-marker"), null);
    }

    @Test
    void rejectsUnknownOrMalformedWireParameters() {
        WireStrategyCompiler compiler = new WireStrategyCompiler();
        assertThrows(ServiceException.class, () -> compiler.compile(strategy("ADD_FILE"), 0, map("unexpected", true), "payload/a.txt"));
        assertThrows(ServiceException.class, () -> compiler.compile(strategy("TEXT_ANCHOR_INSERT"), 0, map("anchor", "// anchor", "position", "before", "managedMarker", "bad marker", "content", "text"), null));
        assertThrows(ServiceException.class, () -> compiler.compile(strategy("ENSURE_IMPORT"), 0, map("importStatement", "import X", "extra", true), null));
        assertThrows(ServiceException.class, () -> compiler.compile(strategy("ENSURE_REACT_PROVIDER"), 0, map("managedMarker", "marker", "astSelector", map("nodeType", "x"), "content", marked("marker")), null));
        assertThrows(ServiceException.class, () -> compiler.compile(strategy("ENSURE_REACT_PROVIDER"), 0, map("managedMarker", "marker", "astSelector", map("nodeType", "x", "position", "beforeEnd", "name", ""), "content", marked("marker")), null));
        assertThrows(ServiceException.class, () -> compiler.compile(strategy("ADD_FILE"), 0, map(), "outside-payload.txt"));
    }

    private static void assertWire(WireStrategyCompiler compiler, String type, Map<String, Object> parameters, String payloadRef) {
        Map<String, Object> wire = compiler.compile(strategy(type), 3, parameters, payloadRef);
        assertEquals(type, wire.get("type"));
        assertEquals(3, wire.get("index"));
        assertEquals(Collections.emptyMap(), wire.get("precondition"));
    }
    private static ModificationStrategy strategy(String type) { return new ModificationStrategy(type, "strategy-" + type.toLowerCase(), "frontend/file.ts", 1, map()); }
    private static Map<String, Object> textParameters(String anchor, String marker) { return map("anchor", anchor, "position", "before", "managedMarker", marker, "content", marked(marker)); }
    private static Map<String, Object> structuralParameters(String marker) { return map("managedMarker", marker, "astSelector", map("nodeType", "identifier", "position", "beforeEnd", "name", "root"), "content", marked(marker)); }
    private static String marked(String marker) { return "/* xcodeagent:" + marker + ":begin */\ncontent\n/* xcodeagent:" + marker + ":end */"; }
    private static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<String, Object>(); for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result; }
}
