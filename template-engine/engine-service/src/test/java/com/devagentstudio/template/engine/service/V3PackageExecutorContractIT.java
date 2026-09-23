package com.devagentstudio.template.engine.service;

import com.devagentstudio.template.engine.core.v2.StateDigest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test-only executor for proving the frozen operation preconditions and transaction contract. */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = EngineServiceApplication.class)
class V3PackageExecutorContractIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MANAGED_REGISTRIES = new HashSet<String>(Arrays.asList(
            "frontend/src/extensions/providers.ts", "frontend/src/extensions/rootRoutes.tsx",
            "frontend/src/extensions/systemPageRoutes.ts", "frontend/src/extensions/initializers.ts",
            "frontend/src/extensions/errorReporters.ts"));
    @Autowired private WebApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("devagentstudio.template-engine.source-root", () -> repositoryRoot().resolve("template-source").toString());
    }

    @Test
    void applyingBaseToAuthorizationMatchesGenerateAndRollsBackOnLateConflict() throws Exception {
        MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
        Map<String, Object> baseCapabilities = Collections.emptyMap();
        Map<String, Object> authCapabilities = capabilities("authorization");
        byte[] baseZip = generate(mvc, baseCapabilities);
        byte[] authZip = generate(mvc, authCapabilities);
        JsonNode currentStateNode = zipJson(baseZip, ".devagentstudio/template-state.json");
        Map<String, Object> currentState = JSON.convertValue(currentStateNode, Map.class);
        Map<String, byte[]> baseWorkspace = projectFiles(baseZip);
        byte[] updateZip = update(mvc, currentStateNode, authCapabilities);

        ReferenceExecutor executor = new ReferenceExecutor();
        AppliedPackage applied = executor.apply(updateZip, baseWorkspace, currentState);
        Map<String, byte[]> expected = projectFiles(authZip);
        assertByteMapsEqual(expected, applied.workspace);
        assertEquals(JSON.convertValue(zipJson(authZip, ".devagentstudio/template-state.json"), Map.class), applied.nextState);

        Map<String, byte[]> conflictingWorkspace = copy(baseWorkspace);
        String pomPath = "backend/pom.xml";
        String originalPom = new String(conflictingWorkspace.get(pomPath), StandardCharsets.UTF_8);
        String conflictingDependency = "<dependency><groupId>ZA21</groupId><artifactId>bee-starter-auth</artifactId><version>9.9</version></dependency>";
        conflictingWorkspace.put(pomPath, originalPom.replace("</dependencies>", conflictingDependency + "</dependencies>\n" ).getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> beforeWorkspace = copy(conflictingWorkspace);
        Map<String, Object> beforeState = copyObject(currentState);
        ReferenceExecutor.Failure failure = assertThrows(ReferenceExecutor.Failure.class,
                () -> executor.apply(updateZip, conflictingWorkspace, currentState));
        assertEquals("MANAGED_SURFACE_CONFLICT", failure.code);
        assertByteMapsEqual(beforeWorkspace, conflictingWorkspace);
        assertEquals(beforeState, currentState);
    }

    private byte[] generate(MockMvc mvc, Map<String, Object> capabilities) throws Exception {
        Map<String, Object> config = new LinkedHashMap<String, Object>(); config.put("capabilities", capabilities);
        Map<String, Object> request = new LinkedHashMap<String, Object>(); request.put("requestedConfig", config);
        return mvc.perform(post("/v1/generate-next").contentType(MediaType.APPLICATION_JSON).accept("application/zip")
                        .content(JSON.writeValueAsBytes(request))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
    private byte[] update(MockMvc mvc, JsonNode state, Map<String, Object> capabilities) throws Exception {
        Map<String, Object> config = new LinkedHashMap<String, Object>(); config.put("capabilities", capabilities);
        Map<String, Object> request = new LinkedHashMap<String, Object>(); request.put("protocolVersion", "3"); request.put("currentTemplateState", JSON.convertValue(state, Map.class));
        request.put("requestedConfig", config); request.put("mode", "APPLY");
        return mvc.perform(post("/v1/update-next").contentType(MediaType.APPLICATION_JSON).accept("application/zip")
                        .content(JSON.writeValueAsBytes(request))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }
    private Map<String, Object> capabilities(String id) {
        Map<String, Object> value = new LinkedHashMap<String, Object>(); value.put("enabled", Boolean.TRUE); value.put("config", new LinkedHashMap<String, Object>());
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(id, value); return result;
    }
    private Map<String, byte[]> projectFiles(byte[] zip) throws Exception {
        Map<String, byte[]> result = new TreeMap<String, byte[]>(); ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip)); ZipEntry entry;
        while ((entry = input.getNextEntry()) != null) if (entry.getName().startsWith("frontend/") || entry.getName().startsWith("backend/") || entry.getName().startsWith(".devagentstudio/")) result.put(entry.getName(), readAll(input));
        return result;
    }
    private JsonNode zipJson(byte[] zip, String path) throws Exception { ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip)); ZipEntry entry; while ((entry = input.getNextEntry()) != null) if (path.equals(entry.getName())) return JSON.readTree(readAll(input)); throw new AssertionError("missing " + path); }
    private byte[] readAll(java.io.InputStream input) throws Exception { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); return output.toByteArray(); }
    private Map<String, byte[]> copy(Map<String, byte[]> input) { Map<String, byte[]> result = new TreeMap<String, byte[]>(); for (Map.Entry<String, byte[]> item : input.entrySet()) result.put(item.getKey(), item.getValue().clone()); return result; }
    @SuppressWarnings("unchecked") private Map<String, Object> copyObject(Map<String, Object> input) throws Exception { return JSON.convertValue(JSON.readTree(JSON.writeValueAsBytes(input)), Map.class); }
    private void assertByteMapsEqual(Map<String, byte[]> expected, Map<String, byte[]> actual) { assertEquals(expected.keySet(), actual.keySet()); for (String path : expected.keySet()) assertArrayEquals(expected.get(path), actual.get(path), path); }
    private static Path repositoryRoot() { Path path = Paths.get(System.getProperty("user.dir")).toAbsolutePath(); while (path != null && !Files.isDirectory(path.resolve("template-source"))) path = path.getParent(); if (path == null) throw new AssertionError("repository root not found"); return path; }

    private static final class AppliedPackage {
        private final Map<String, byte[]> workspace;
        private final Map<String, Object> nextState;
        private AppliedPackage(Map<String, byte[]> workspace, Map<String, Object> nextState) { this.workspace = workspace; this.nextState = nextState; }
    }

    private static final class ReferenceExecutor {
        private AppliedPackage apply(byte[] packageZip, Map<String, byte[]> workspace, Map<String, Object> currentState) throws Exception {
            Map<String, byte[]> entries = zipMap(packageZip);
            byte[] packageBytes = entries.get("extension-update-package.json");
            if (packageBytes == null) throw new Failure("PACKAGE_SCHEMA_INVALID");
            JsonNode metadata = JSON.readTree(packageBytes);
            if (!StateDigest.of(currentState).equals(metadata.path("currentStateDigest").asText())) throw new Failure("CURRENT_STATE_DIGEST_MISMATCH");
            Map<String, byte[]> payloads = new TreeMap<String, byte[]>();
            JsonNode manifest = metadata.path("payloadManifest");
            java.util.Iterator<Map.Entry<String, JsonNode>> payloadMetadata = manifest.fields();
            while (payloadMetadata.hasNext()) {
                Map.Entry<String, JsonNode> item = payloadMetadata.next(); String ref = item.getKey(); byte[] content = entries.get(ref);
                if (content == null || item.getValue().path("size").asInt(-1) != content.length || !StateDigest.sha256(content).equals(item.getValue().path("sha256").asText()))
                    throw new Failure("PAYLOAD_DIGEST_MISMATCH");
                payloads.put(ref, content);
            }
            JsonNode operations = metadata.path("operations"); Set<String> ids = new HashSet<String>();
            for (int index = 0; index < operations.size(); index++) {
                JsonNode operation = operations.get(index);
                if (operation.path("index").asInt(-1) != index || !ids.add(operation.path("operationId").asText()) || !safe(operation.path("target").asText()))
                    throw new Failure("PACKAGE_SCHEMA_INVALID");
                String type = operation.path("type").asText();
                if (!Arrays.asList("ADD_FILE", "REPLACE_MANAGED_FILE", "ENSURE_JAVA_ANNOTATION", "REMOVE_JAVA_ANNOTATION", "ENSURE_MAVEN_DEPENDENCY", "REMOVE_MAVEN_DEPENDENCY").contains(type)) throw new Failure("OPERATION_UNSUPPORTED");
                String payloadRef = operation.path("payloadRef").isNull() ? null : operation.path("payloadRef").asText();
                if ("ADD_FILE".equals(type) || "REPLACE_MANAGED_FILE".equals(type)) {
                    if (payloadRef == null || !payloads.containsKey(payloadRef) || !payloadRef.equals("payload/" + operation.path("target").asText())) throw new Failure("PACKAGE_SCHEMA_INVALID");
                } else if (payloadRef != null) throw new Failure("PACKAGE_SCHEMA_INVALID");
            }
            if (payloads.size() != entries.size() - 1) throw new Failure("PACKAGE_SCHEMA_INVALID");

            Map<String, byte[]> staged = copy(workspace);
            for (JsonNode operation : operations) applyOne(staged, operation, payloads);
            JsonNode nextStateNode = metadata.path("nextTemplateState");
            Map<String, Object> nextState = JSON.convertValue(nextStateNode, Map.class);
            if (!StateDigest.of(nextState).equals(metadata.path("nextStateDigest").asText())) throw new Failure("PACKAGE_SCHEMA_INVALID");
            staged.put(".devagentstudio/template-state.json", JSON.writeValueAsBytes(nextState));
            return new AppliedPackage(staged, nextState);
        }

        private void applyOne(Map<String, byte[]> staged, JsonNode operation, Map<String, byte[]> payloads) throws Exception {
            String type = operation.path("type").asText(); String target = operation.path("target").asText();
            if ("ADD_FILE".equals(type)) {
                if (staged.containsKey(target)) throw new Failure("ADD_FILE_TARGET_EXISTS");
                staged.put(target, payloads.get(operation.path("payloadRef").asText()).clone());
            } else if ("REPLACE_MANAGED_FILE".equals(type)) {
                if (!MANAGED_REGISTRIES.contains(target)) throw new Failure("UNSAFE_TARGET_PATH");
                staged.put(target, payloads.get(operation.path("payloadRef").asText()).clone());
            } else if (type.endsWith("JAVA_ANNOTATION")) {
                applyAnnotation(staged, target, operation.path("parameters").path("annotationClass").asText(), type.startsWith("ENSURE_"));
            } else if (type.endsWith("MAVEN_DEPENDENCY")) {
                applyMaven(staged, target, operation.path("parameters"), type.startsWith("ENSURE_"));
            } else throw new Failure("OPERATION_UNSUPPORTED");
        }
        private void applyAnnotation(Map<String, byte[]> staged, String target, String fqcn, boolean ensure) {
            byte[] bytes = staged.get(target); if (bytes == null) throw new Failure("MANAGED_SURFACE_CONFLICT");
            String source = new String(bytes, StandardCharsets.UTF_8); String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            String importLine = "import " + fqcn + ";"; String annotation = "@" + simple;
            int imports = occurrences(source, importLine); int annotations = occurrences(source, annotation);
            if (ensure) {
                if (imports == 0 && annotations == 0) {
                    if (!source.contains("@SpringBootApplication")) throw new Failure("MANAGED_SURFACE_CONFLICT");
                    source = insertImport(source, importLine).replace("@SpringBootApplication", annotation + "\n@SpringBootApplication");
                } else if (imports != 1 || annotations != 1) throw new Failure("MANAGED_SURFACE_CONFLICT");
            } else {
                if (imports == 0 && annotations == 0) return;
                if (imports != 1 || annotations != 1) throw new Failure("MANAGED_SURFACE_CONFLICT");
                source = source.replace(importLine + "\n", "").replace(annotation + "\n", "");
            }
            staged.put(target, source.getBytes(StandardCharsets.UTF_8));
        }
        private String insertImport(String source, String line) {
            int last = source.lastIndexOf("import ");
            if (last < 0) { int end = source.indexOf(';'); return source.substring(0, end + 1) + "\n\n" + line + source.substring(end + 1); }
            int end = source.indexOf('\n', last); return source.substring(0, end + 1) + line + "\n" + source.substring(end + 1);
        }
        private void applyMaven(Map<String, byte[]> staged, String target, JsonNode parameters, boolean ensure) throws Exception {
            byte[] bytes = staged.get(target); if (bytes == null) throw new Failure("MANAGED_SURFACE_CONFLICT");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            String groupId = parameters.path("groupId").asText(); String artifactId = parameters.path("artifactId").asText();
            String version = nullableText(parameters.get("version")); String scope = nullableText(parameters.get("scope"));
            String coordinate = groupId + ":" + artifactId;
            List<Element> matches = new ArrayList<Element>(); NodeList list = document.getElementsByTagName("dependency");
            for (int i = 0; i < list.getLength(); i++) { Element element = (Element) list.item(i); if (coordinate.equals(text(element, "groupId") + ":" + text(element, "artifactId"))) matches.add(element); }
            if (matches.size() > 1) throw new Failure("MANAGED_SURFACE_CONFLICT");
            if (ensure && matches.isEmpty()) append(document, groupId, artifactId, version, scope);
            else if (!matches.isEmpty()) {
                boolean exact = equals(text(matches.get(0), "version"), version) && equals(text(matches.get(0), "scope"), scope);
                if (ensure && !exact || !ensure && !exact) throw new Failure("MANAGED_SURFACE_CONFLICT");
                if (!ensure) matches.get(0).getParentNode().removeChild(matches.get(0));
            } else return;
            stripWhitespace(document.getDocumentElement());
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes"); transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4"); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter writer = new StringWriter(); transformer.transform(new DOMSource(document), new StreamResult(writer));
            staged.put(target, writer.toString().getBytes(StandardCharsets.UTF_8));
        }
        private void append(Document document, String groupId, String artifactId, String version, String scope) {
            NodeList list = document.getDocumentElement().getElementsByTagName("dependencies");
            Element deps = list.getLength() == 0 ? document.createElement("dependencies") : (Element) list.item(0);
            if (list.getLength() == 0) document.getDocumentElement().appendChild(deps);
            Element dependency = document.createElement("dependency"); deps.appendChild(dependency);
            child(document, dependency, "groupId", groupId); child(document, dependency, "artifactId", artifactId);
            if (version != null) child(document, dependency, "version", version); if (scope != null) child(document, dependency, "scope", scope);
        }
        private void child(Document document, Element parent, String name, String value) { Element element = document.createElement(name); element.setTextContent(value); parent.appendChild(element); }
        private String text(Element element, String name) { NodeList list = element.getElementsByTagName(name); return list.getLength() == 0 ? null : ((Element) list.item(0)).getTextContent().trim(); }
        private void stripWhitespace(Node node) { NodeList children = node.getChildNodes(); for (int i = children.getLength() - 1; i >= 0; i--) { Node child = children.item(i); if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().trim().isEmpty()) node.removeChild(child); else if (child.getNodeType() == Node.ELEMENT_NODE) stripWhitespace(child); } }
        private String nullableText(JsonNode value) { return value == null || value.isNull() ? null : value.asText(); }
        private boolean equals(String left, String right) { return left == null ? right == null : left.equals(right); }
        private int occurrences(String value, String token) { return (value.length() - value.replace(token, "").length()) / token.length(); }
        private boolean safe(String value) { if (value.isEmpty() || value.startsWith("/") || value.contains("\\")) return false; for (String part : value.split("/")) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false; return true; }
        private Map<String, byte[]> zipMap(byte[] zip) throws Exception { Map<String, byte[]> result = new TreeMap<String, byte[]>(); ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip)); ZipEntry entry; while ((entry = input.getNextEntry()) != null) result.put(entry.getName(), readAll(input)); return result; }
        private static byte[] readAll(java.io.InputStream input) throws Exception { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count; while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count); return output.toByteArray(); }
        private Map<String, byte[]> copy(Map<String, byte[]> input) { Map<String, byte[]> result = new TreeMap<String, byte[]>(); for (Map.Entry<String, byte[]> item : input.entrySet()) result.put(item.getKey(), item.getValue().clone()); return result; }
        private Failure fail(String code) { return new Failure(code); }
        private static final class Failure extends RuntimeException { private final String code; private Failure(String code) { super(code); this.code = code; } }
    }
}
