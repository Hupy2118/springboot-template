package com.devagentstudio.template.engine.core.v3;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministically materializes frontend and backend Base + Extension Runtime Source. */
public final class V3ProjectMaterializer {
    public static final List<String> REGISTRY_TARGETS = Collections.unmodifiableList(Arrays.asList(
            "frontend/src/extensions/providers.ts", "frontend/src/extensions/rootRoutes.tsx",
            "frontend/src/extensions/systemPageRoutes.ts", "frontend/src/extensions/initializers.ts",
            "frontend/src/extensions/errorReporters.ts"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CodeTemplateRelease release;

    public V3ProjectMaterializer(CodeTemplateRelease release) { this.release = release; }

    public Map<String, byte[]> materialize(ExtensionResolver.ResolvedExtensions resolved) {
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        add(files, "frontend/", release.frontendBase());
        add(files, "backend/", release.backendBase());
        for (String id : resolved.order()) {
            ExtensionRelease extension = release.extensions().get(id);
            add(files, "frontend/", extension.frontendFiles());
            add(files, "backend/", extension.backendFiles());
        }
        for (Map.Entry<String, byte[]> registry : registries(resolved).entrySet()) files.put(registry.getKey(), registry.getValue());
        projectAnnotations(files, resolved.order());
        projectMavenDependencies(files, resolved.order());
        return files;
    }

    public Map<String, Object> templateState(ExtensionResolver.ResolvedExtensions resolved) {
        Map<String, Map<String, Object>> artifacts = new java.util.TreeMap<String, Map<String, Object>>();
        for (String id : resolved.order()) {
            ExtensionRelease extension = release.extensions().get(id);
            addArtifactStates(artifacts, id, "frontend", extension.frontendFiles());
            addArtifactStates(artifacts, id, "backend", extension.backendFiles());
        }
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("schemaVersion", 3); state.put("templateRevision", release.revision());
        state.put("requested", resolved.requested()); state.put("effective", resolved.effective());
        state.put("installedArtifacts", artifacts); state.put("managedContributions", resolved.managedContributions());
        return state;
    }

    private void addArtifactStates(Map<String, Map<String, Object>> result, String id, String side, Map<String, byte[]> files) {
        for (String path : files.keySet()) {
            Map<String, Object> state = new LinkedHashMap<String, Object>();
            state.put("capabilityId", id); state.put("target", side + "/" + path); state.put("installedRevision", release.revision());
            result.put(side + ":" + id + ":" + path, state);
        }
    }

    private void add(Map<String, byte[]> target, String prefix, Map<String, byte[]> files) {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String path = prefix + entry.getKey();
            if (target.put(path, entry.getValue().clone()) != null) throw sourceError("file collision during materialization: " + path);
        }
    }

    private Map<String, byte[]> registries(ExtensionResolver.ResolvedExtensions resolved) {
        List<Map<String, Object>> providers = collect(resolved.order(), "providers");
        List<Map<String, Object>> rootRoutes = collect(resolved.order(), "rootRoutes");
        List<Map<String, Object>> pageRoutes = collect(resolved.order(), "pageRoutes");
        List<Map<String, Object>> initializers = collect(resolved.order(), "initializers");
        List<Map<String, Object>> reporters = collect(resolved.order(), "errorReporters");
        Map<String, byte[]> output = new LinkedHashMap<String, byte[]>();
        output.put(REGISTRY_TARGETS.get(0), utf8(providerRegistry(providers)));
        output.put(REGISTRY_TARGETS.get(1), utf8(rootRouteRegistry(rootRoutes)));
        output.put(REGISTRY_TARGETS.get(2), utf8(pageRouteRegistry(pageRoutes)));
        output.put(REGISTRY_TARGETS.get(3), utf8(emptyFunctionRegistry(initializers, "Initializer", "extensionInitializers", "Array<() => void | Promise<void>>")));
        output.put(REGISTRY_TARGETS.get(4), utf8(emptyFunctionRegistry(reporters, "ErrorReporter", "extensionErrorReporters", "Array<(error: Error, info?: unknown) => void>")));
        return output;
    }

    private List<Map<String, Object>> collect(List<String> order, String type) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Set<String> ids = new HashSet<String>();
        Set<String> routePaths = new HashSet<String>();
        for (String id : order) {
            ExtensionRelease extension = release.extensions().get(id);
            List<Map<String, Object>> values = "providers".equals(type) ? extension.providers()
                    : "rootRoutes".equals(type) ? extension.rootRoutes()
                    : "pageRoutes".equals(type) ? extension.pageRoutes()
                    : "initializers".equals(type) ? extension.initializers() : extension.errorReporters();
            for (Map<String, Object> value : values) {
                if (!ids.add(String.valueOf(value.get("id")))) throw sourceError("duplicate " + type + " id " + value.get("id"));
                if (("rootRoutes".equals(type) || "pageRoutes".equals(type)) && !routePaths.add(String.valueOf(value.get("path"))))
                    throw sourceError("duplicate route path " + value.get("path"));
                result.add(value);
            }
        }
        return result;
    }

    private String providerRegistry(List<Map<String, Object>> values) {
        List<String> imports = imports(values, "Provider");
        return "import type { ComponentType, PropsWithChildren } from 'react';\n" + join(imports, "\n")
                + "\n\nexport const extensionProviders: ComponentType<PropsWithChildren>[] = [" + locals(values.size(), "Provider") + "];\n";
    }
    private String rootRouteRegistry(List<Map<String, Object>> values) {
        List<String> imports = new ArrayList<String>();
        Set<String> localNames = new HashSet<String>();
        StringBuilder routes = new StringBuilder();
        for (Map<String, Object> value : values) {
            String source = String.valueOf(value.get("source")).substring(4).replaceAll("\\.(tsx?|jsx?)$", "");
            String export = String.valueOf(value.get("export"));
            String localName = String.valueOf(value.get("localName"));
            if (!localNames.add(localName)) throw sourceError("duplicate root route local name " + localName);
            imports.add("default".equals(export) ? "import " + localName + " from '@/" + source + "';"
                    : "import { " + export + " as " + localName + " } from '@/" + source + "';");
            routes.append("\n  { path: '").append(value.get("path")).append("', element: <").append(localName).append(" /> },");
        }
        return "import type { RouteObject } from 'react-router-dom';\n" + join(imports, "\n")
                + "\n\nexport const extensionRootRoutes: RouteObject[] = [" + routes + "\n];\n";
    }
    private String pageRouteRegistry(List<Map<String, Object>> values) {
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> route : values) {
            body.append("  { path: '").append(route.get("path")).append("', label: ").append(json(route.get("label")));
            if (route.get("icon") != null) body.append(", icon: '").append(route.get("icon")).append("'");
            if (route.get("resourceKey") != null) body.append(", resourceKey: '").append(route.get("resourceKey")).append("'");
            body.append(" },\n");
        }
        if (body.length() > 0) body.setLength(body.length() - 1);
        return "import type { PageRouteDefinition } from '@/typings/routes';\n\nexport const extensionSystemPageRoutes: PageRouteDefinition[] = [\n" + body + "\n];\n";
    }
    private String emptyFunctionRegistry(List<Map<String, Object>> values, String prefix, String symbol, String type) {
        List<String> imports = imports(values, prefix);
        return join(imports, "\n") + "\n\nexport const " + symbol + ": " + type + " = [" + locals(values.size(), prefix) + "];\n";
    }
    private List<String> imports(List<Map<String, Object>> values, String prefix) {
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> value = values.get(i);
            String source = String.valueOf(value.get("source")).substring(4).replaceAll("\\.(tsx?|jsx?)$", "");
            String export = String.valueOf(value.get("export"));
            String local = prefix + i;
            lines.add("default".equals(export) ? "import " + local + " from '@/" + source + "';"
                    : "import { " + export + " as " + local + " } from '@/" + source + "';");
        }
        return lines;
    }
    private String locals(int count, String prefix) {
        List<String> values = new ArrayList<String>(); for (int i = 0; i < count; i++) values.add(prefix + i); return join(values, ", ");
    }
    private String join(List<String> values, String separator) { StringBuilder result = new StringBuilder(); for (int i = 0; i < values.size(); i++) { if (i > 0) result.append(separator); result.append(values.get(i)); } return result.toString(); }
    private String json(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new V3Exception("TEMPLATE_SOURCE_INVALID", "cannot render registry", 500); } }
    private byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private void projectAnnotations(Map<String, byte[]> files, List<String> order) {
        List<String> annotations = new ArrayList<String>();
        for (String id : order) annotations.addAll(release.extensions().get(id).applicationAnnotations());
        Set<String> unique = new LinkedHashSet<String>(annotations);
        if (unique.isEmpty()) return;
        String path = "backend/src/main/java/com/cmbchina/backend/Application.java";
        byte[] original = files.get(path);
        if (original == null) throw sourceError("backend Application.java is required for annotation contributions");
        String source = new String(original, StandardCharsets.UTF_8);
        for (String fqcn : unique) {
            String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            String importLine = "import " + fqcn + ";";
            if (!source.contains(importLine)) source = insertImport(source, importLine);
            String annotation = "@" + simple;
            if (!source.contains(annotation)) source = source.replace("@SpringBootApplication", annotation + "\n@SpringBootApplication");
        }
        files.put(path, utf8(source));
    }

    private String insertImport(String source, String line) {
        int last = source.lastIndexOf("import ");
        if (last < 0) { int packageEnd = source.indexOf(';'); return source.substring(0, packageEnd + 1) + "\n\n" + line + source.substring(packageEnd + 1); }
        int end = source.indexOf('\n', last); return source.substring(0, end + 1) + line + "\n" + source.substring(end + 1);
    }

    private void projectMavenDependencies(Map<String, byte[]> files, List<String> order) {
        List<Map<String, Object>> dependencies = new ArrayList<Map<String, Object>>();
        Map<String, Map<String, Object>> byCoordinate = new LinkedHashMap<String, Map<String, Object>>();
        for (String id : order) for (Map<String, Object> item : release.extensions().get(id).mavenDependencies()) {
            String coordinate = item.get("groupId") + ":" + item.get("artifactId");
            Map<String, Object> previous = byCoordinate.putIfAbsent(coordinate, item);
            if (previous == null) dependencies.add(item);
            else if (!previous.equals(item)) throw new V3Exception("MAVEN_DEPENDENCY_CONFLICT", "conflicting Maven dependency " + coordinate, 500);
        }
        String path = "backend/pom.xml";
        byte[] original = files.get(path);
        if (original == null) throw sourceError("backend pom.xml is required for Maven contributions");
        try { files.put(path, utf8(transformPom(new String(original, StandardCharsets.UTF_8), dependencies))); }
        catch (Exception e) { throw new V3Exception("TEMPLATE_SOURCE_INVALID", "cannot project Maven dependencies: " + e.getMessage(), 500); }
    }

    private String transformPom(String content, Collection<Map<String, Object>> dependencies) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        Element root = document.getDocumentElement();
        for (Map<String, Object> value : dependencies) {
            String coordinate = value.get("groupId") + ":" + value.get("artifactId");
            List<Element> matches = dependencies(document, coordinate);
            if (matches.isEmpty()) appendDependency(document, value);
            else if (!same(matches.get(0), value)) throw new V3Exception("MAVEN_DEPENDENCY_CONFLICT", coordinate, 500);
        }
        stripWhitespace(root);
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes"); transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4"); transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter(); transformer.transform(new DOMSource(document), new StreamResult(writer)); return writer.toString();
    }
    private List<Element> dependencies(Document document, String coordinate) {
        List<Element> found = new ArrayList<Element>(); NodeList list = document.getElementsByTagName("dependency");
        for (int i = 0; i < list.getLength(); i++) { Element element = (Element) list.item(i); if (coordinate.equals(text(element, "groupId") + ":" + text(element, "artifactId"))) found.add(element); }
        return found;
    }
    private boolean same(Element element, Map<String, Object> value) {
        return equal(text(element, "version"), value.get("version")) && equal(text(element, "scope"), value.get("scope"));
    }
    private boolean equal(String text, Object value) { return text == null ? value == null : text.equals(value); }
    private String text(Element element, String name) {
        NodeList list = element.getElementsByTagName(name); return list.getLength() == 0 ? null : ((Element) list.item(0)).getTextContent().trim();
    }
    private Element first(Element parent, String name) { NodeList list = parent.getElementsByTagName(name); return list.getLength() == 0 ? null : (Element) list.item(0); }
    private void appendDependency(Document document, Map<String, Object> value) {
        Element dependencies = first(document.getDocumentElement(), "dependencies");
        if (dependencies == null) { dependencies = document.createElement("dependencies"); document.getDocumentElement().appendChild(dependencies); }
        Element dependency = document.createElement("dependency"); dependencies.appendChild(dependency);
        child(document, dependency, "groupId", String.valueOf(value.get("groupId")));
        child(document, dependency, "artifactId", String.valueOf(value.get("artifactId")));
        if (value.get("version") != null) child(document, dependency, "version", String.valueOf(value.get("version")));
        if (value.get("scope") != null) child(document, dependency, "scope", String.valueOf(value.get("scope")));
    }
    private void child(Document document, Element parent, String name, String value) { Element child = document.createElement(name); child.setTextContent(value); parent.appendChild(child); }
    private void stripWhitespace(Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) { Node child = children.item(i); if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().trim().isEmpty()) node.removeChild(child); else if (child.getNodeType() == Node.ELEMENT_NODE) stripWhitespace(child); }
    }
    private V3Exception sourceError(String message) { return new V3Exception("TEMPLATE_SOURCE_INVALID", message, 500); }
}
