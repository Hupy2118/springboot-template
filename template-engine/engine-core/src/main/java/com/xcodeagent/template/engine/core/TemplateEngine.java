package com.xcodeagent.template.engine.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.source.TemplateSourceContext;
import com.xcodeagent.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic Stage2 resolver, renderer and file-level diff engine. */
public final class TemplateEngine {
    private final TemplateSourceContext source;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public TemplateEngine(TemplateSourceContext source) { this.source = source; }

    public CorePlanResult plan(TemplateState current, RequestedConfig requested) {
        Map<String, Object> requestedState = normalizeRequested(requested);
        Map<String, Manifest> manifests = manifests();
        List<String> effectiveIds = resolveEffective(manifests, requestedState);
        Map<String, Object> effectiveState = new LinkedHashMap<String, Object>();
        for (String id : effectiveIds) effectiveState.put(id, Collections.<String, Object>singletonMap("enabled", Boolean.TRUE));
        Map<String, String> target = targetFiles(manifests, effectiveIds);
        TemplateState next = new TemplateState(source.getTemplateRevision(), target, requestedState, effectiveState);
        if (current != null && current.templateRevision().equals(source.getTemplateRevision())
                && current.managedFiles().equals(target) && current.requested().equals(requestedState)) {
            return new CorePlanResult(CorePlanResult.Kind.NO_CHANGE, next, Collections.<FileOperation>emptyList());
        }
        List<FileOperation> operations = diff(current, target, current != null && !source.getTemplateRevision().equals(current.templateRevision()));
        return new CorePlanResult(CorePlanResult.Kind.CHANGE, next, operations);
    }

    private Map<String, Object> normalizeRequested(RequestedConfig requested) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<String> ids = new ArrayList<String>(requested.capabilities().keySet());
        Collections.sort(ids);
        for (String id : ids) {
            Map<String, Object> value = requested.capabilities().get(id);
            if (Boolean.TRUE.equals(value.get("enabled"))) result.put(id, Collections.<String, Object>singletonMap("enabled", Boolean.TRUE));
        }
        return result;
    }

    private Map<String, Manifest> manifests() {
        Map<String, Manifest> result = new LinkedHashMap<String, Manifest>();
        Map<String, Object> catalog = yamlMap(source.getRoot().resolve("catalog.yaml"));
        for (Object raw : list(catalog.get("capabilities"), "catalog capabilities")) {
            Map<String, Object> entry = map(raw, "catalog entry");
            String id = string(entry.get("id"), "catalog id");
            String path = string(entry.get("path"), "catalog path");
            result.put(id, new Manifest(id, source.getRoot().resolve(path), yamlMap(source.getRoot().resolve(path).resolve("capability.yaml"))));
        }
        return result;
    }

    private List<String> resolveEffective(Map<String, Manifest> manifests, Map<String, Object> requested) {
        Set<String> result = new HashSet<String>();
        for (String id : requested.keySet()) resolve(id, manifests, result, new HashSet<String>());
        List<String> ids = new ArrayList<String>(result); Collections.sort(ids); return ids;
    }

    private void resolve(String id, Map<String, Manifest> manifests, Set<String> result, Set<String> visiting) {
        Manifest manifest = manifests.get(id);
        if (manifest == null) throw new TemplateSourceException("CAPABILITY_UNKNOWN: " + id);
        if (!visiting.add(id)) throw new TemplateSourceException("CAPABILITY_CYCLE: " + id);
        for (Object raw : list(manifest.data.get("requires"), "requires")) {
            String required = raw instanceof String ? (String) raw : string(map(raw, "require").get("id"), "require id");
            String mode = raw instanceof Map && map(raw, "require").get("mode") != null ? string(map(raw, "require").get("mode"), "require mode") : "required";
            if ("forbidden".equals(mode) && result.contains(required)) throw new TemplateSourceException("CAPABILITY_FORBIDDEN: " + id + ":" + required);
            if ("required".equals(mode)) resolve(required, manifests, result, visiting);
            else if (!"optional".equals(mode) && !"forbidden".equals(mode)) throw new TemplateSourceException("CAPABILITY_REQUIRE_MODE_INVALID: " + mode);
        }
        visiting.remove(id); result.add(id);
    }

    private Map<String, String> targetFiles(Map<String, Manifest> manifests, List<String> effectiveIds) {
        Map<String, String> target = new LinkedHashMap<String, String>();
        copyFiles(source.getRoot().resolve("base"), yamlMap(source.getRoot().resolve("base/base.yaml")), target);
        List<Map<String, Object>> contributions = new ArrayList<Map<String, Object>>();
        for (String id : effectiveIds) {
            Manifest manifest = manifests.get(id);
            copyFiles(manifest.root, manifest.data, target);
            for (Object migration : list(manifest.data.get("migrations"), "migrations")) copy(target, manifest.root, map(migration, "migration"));
            for (Object extension : list(manifest.data.get("extensions"), "extensions")) {
                Map<String, Object> envelope = new LinkedHashMap<String, Object>(map(extension, "extension")); envelope.put("capabilityId", id); contributions.add(envelope);
            }
        }
        Collections.sort(contributions, contributionOrder());
        render(target, contributions);
        mergeNpmDependencies(target, manifests, effectiveIds);
        return target;
    }

    private void copyFiles(Path root, Map<String, Object> manifest, Map<String, String> target) {
        for (Object file : list(manifest.get("files"), "files")) copy(target, root, map(file, "file"));
    }
    private void copy(Map<String, String> target, Path root, Map<String, Object> entry) {
        String targetPath = string(entry.get("target"), "target");
        if (target.containsKey(targetPath)) throw new TemplateSourceException("FILE_OWNERSHIP_CONFLICT: " + targetPath);
        try { target.put(targetPath, new String(Files.readAllBytes(root.resolve(string(entry.get("source"), "source"))), StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: " + e.getMessage()); }
    }

    private void mergeNpmDependencies(Map<String, String> target, Map<String, Manifest> manifests, List<String> ids) {
        Map<String, String> versions = new HashMap<String, String>();
        // V1 source nests npm beneath dependencies; retain Base package key order and deterministically append versions.
        for (String id : ids) {
            Object dependencyBlock = manifests.get(id).data.get("dependencies");
            if (!(dependencyBlock instanceof Map)) continue;
            Object npm = map(dependencyBlock, "dependencies").get("npm");
            if (!(npm instanceof List)) continue;
            for (Object raw : (List<?>) npm) {
                Map<String, Object> dependency = map(raw, "npm dependency"); String name = string(dependency.get("name"), "npm name"); String version = string(dependency.get("version"), "npm version");
                if (versions.containsKey(name) && !versions.get(name).equals(version)) throw new TemplateSourceException("DEPENDENCY_CONFLICT: " + name);
                versions.put(name, version);
            }
        }
        if (versions.isEmpty()) return;
        try {
            ObjectMapper json = new ObjectMapper(); Map<String, Object> pkg = json.readValue(target.get("frontend/package.json"), new TypeReference<LinkedHashMap<String, Object>>() {});
            Map<String, Object> dependencies = pkg.get("dependencies") instanceof Map ? new LinkedHashMap<String, Object>(map(pkg.get("dependencies"), "package dependencies")) : new LinkedHashMap<String, Object>();
            List<String> names = new ArrayList<String>(versions.keySet()); Collections.sort(names); for (String name : names) dependencies.put(name, versions.get(name));
            pkg.put("dependencies", dependencies); target.put("frontend/package.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(pkg) + "\n");
        } catch (IOException e) { throw new TemplateSourceException("DEPENDENCY_INVALID: " + e.getMessage()); }
    }

    private void render(Map<String, String> target, List<Map<String, Object>> all) {
        List<Map<String, Object>> providers = point(all, "frontend.providers"), roots = point(all, "frontend.root-routes"), pages = point(all, "frontend.page-routes"), wrappers = point(all, "frontend.page-wrappers"), menus = point(all, "frontend.menu-hooks"), interceptors = point(all, "backend.spring-interceptors");
        target.put("frontend/src/generated/capabilityProviders.tsx", providers(providers));
        target.put("frontend/src/generated/capabilityRoutes.tsx", routes(roots, pages, wrappers));
        target.put("frontend/src/generated/capabilityMenus.ts", menus(menus));
        target.put("backend/src/main/java/com/cmbchina/backend/common/config/CapabilityWebMvcConfiguration.java", interceptors(interceptors));
    }

    private String providers(List<Map<String, Object>> items) {
        StringBuilder out = new StringBuilder("import type { PropsWithChildren } from 'react';\n");
        for (Map<String, Object> item : items) { Map<String, Object> p = payload(item); out.append("import { ").append(p.get("export")).append(" } from '").append(p.get("module")).append("';\n"); }
        out.append("\nexport function CapabilityProviders({ children }: PropsWithChildren) {\n  return ");
        for (Map<String, Object> item : items) out.append("<").append(payload(item).get("export")).append(">");
        out.append("{children}"); for (int i = items.size() - 1; i >= 0; i--) out.append("</").append(payload(items.get(i)).get("export")).append(">"); return out.append(";\n}\n").toString();
    }
    private String routes(List<Map<String, Object>> roots, List<Map<String, Object>> pages, List<Map<String, Object>> wrappers) {
        assertUniqueRoute(roots, true); assertUniqueRoute(pages, false);
        StringBuilder out = new StringBuilder("import { lazy, Suspense, type ReactNode } from 'react';\nimport type { RouteObject } from 'react-router-dom';\nimport type { PageRouteDefinition } from '@/typings/routes';\n");
        List<Map<String, Object>> components = new ArrayList<Map<String, Object>>(); components.addAll(roots); components.addAll(pages);
        for (Map<String, Object> item : components) { Map<String, Object> p = payload(item); String name = componentName(item); out.append("const ").append(name).append(" = lazy(() => import('").append(p.get("module")).append("').then(m => ({ default: ").append("default".equals(p.get("exportType")) ? "m.default" : "m." + p.get("export")).append(" })));\n"); }
        for (Map<String, Object> item : wrappers) { Map<String, Object> p = payload(item); out.append("import { ").append(p.get("export")).append(" } from '").append(p.get("module")).append("';\n"); }
        out.append("\nexport const capabilityRootRoutes: RouteObject[] = [\n"); for (Map<String, Object> item : roots) { Map<String, Object> p = payload(item); out.append("  { path: '").append(p.get("path")).append("', element: <Suspense><").append(componentName(item)).append(" /></Suspense> },\n"); } out.append("];\n");
        Map<String, Object> entry = null; for (Map<String, Object> item : roots) if (Boolean.TRUE.equals(payload(item).get("entry"))) { if (entry != null) throw new TemplateSourceException("EXTENSION_CONFLICT: multiple entry routes"); entry = item; }
        out.append("export const capabilityEntryPath: string | undefined = ").append(entry == null ? "undefined" : "'" + payload(entry).get("path") + "'").append(";\n");
        out.append("export const capabilityPageRoutes: PageRouteDefinition[] = [\n"); for (Map<String, Object> item : pages) { Map<String, Object> p = payload(item); out.append("  { routeId: '").append(item.get("id")).append("', path: '").append(p.get("path")).append("', name: '").append(p.get("name")).append("', component: ").append(componentName(item)); if (p.get("resourceKey") != null) out.append(", resourceKey: '").append(p.get("resourceKey")).append("'"); out.append(" },\n"); } out.append("];\n");
        String wrapped = "element"; for (int i = wrappers.size() - 1; i >= 0; i--) wrapped = payload(wrappers.get(i)).get("export") + "(" + wrapped + ", page)";
        return out.append("export const wrapCapabilityPage = (element: ReactNode, page: PageRouteDefinition): ReactNode => ").append(wrapped).append(";\n").toString();
    }
    private String menus(List<Map<String, Object>> items) { StringBuilder out = new StringBuilder("import type { Route } from '@/typings/workbench';\n"); for (Map<String, Object> item : items) { Map<String, Object> p = payload(item); out.append("import { ").append(p.get("export")).append(" } from '").append(p.get("module")).append("';\n"); } out.append("\nexport const useCapabilityMenus = (menus: Route[]): Route[] => "); for (int i = items.size() - 1; i >= 0; i--) out.append(payload(items.get(i)).get("export")).append("("); out.append("menus"); for (int i = 0; i < items.size(); i++) out.append(")"); return out.append(";\n").toString(); }
    private String interceptors(List<Map<String, Object>> items) { StringBuilder out = new StringBuilder("package com.cmbchina.backend.common.config;\n\nimport org.springframework.context.annotation.Configuration;\nimport org.springframework.web.servlet.config.annotation.InterceptorRegistry;\nimport org.springframework.web.servlet.config.annotation.WebMvcConfigurer;\n"); for (Map<String, Object> item : items) out.append("import ").append(payload(item).get("className")).append(";\n"); out.append("\n@Configuration\npublic class CapabilityWebMvcConfiguration implements WebMvcConfigurer {\n"); for (Map<String, Object> item : items) out.append("    private final ").append(simple(String.valueOf(payload(item).get("className")))).append(" ").append(variable(item)).append(";\n"); if (!items.isEmpty()) { out.append("\n    public CapabilityWebMvcConfiguration("); for (int i=0;i<items.size();i++) { if(i>0) out.append(", "); String type=simple(String.valueOf(payload(items.get(i)).get("className"))); out.append(type).append(" ").append(variable(items.get(i))); } out.append(") {\n"); for (Map<String,Object> item:items) out.append("        this.").append(variable(item)).append(" = ").append(variable(item)).append(";\n"); out.append("    }\n"); } out.append("\n    @Override public void addInterceptors(InterceptorRegistry registry) {\n"); for (Map<String,Object> item:items) { Map<String,Object> p=payload(item); out.append("        registry.addInterceptor(").append(variable(item)).append(").order(").append(p.get("order")).append(")"); for(Object path:list(p.get("pathPatterns"),"paths")) out.append(".addPathPatterns(\"").append(path).append("\")"); for(Object path:list(p.get("excludePathPatterns"),"excluded")) out.append(".excludePathPatterns(\"").append(path).append("\")"); out.append(";\n"); } return out.append("    }\n}\n").toString(); }

    private List<FileOperation> diff(TemplateState current, Map<String, String> target, boolean refresh) { Map<String,String> before=current==null?Collections.<String,String>emptyMap():current.managedFiles(); List<FileOperation> ops=new ArrayList<FileOperation>(); List<String> removed=new ArrayList<String>(before.keySet()); removed.removeAll(target.keySet()); Collections.sort(removed); for(String path:removed) ops.add(new FileOperation(FileOperation.Type.DELETE_FILE,path,null)); List<String> paths=new ArrayList<String>(target.keySet()); Collections.sort(paths); for(String path:paths) { String old=before.get(path); if(old==null) ops.add(new FileOperation(FileOperation.Type.ADD_FILE,path,target.get(path))); else if(refresh || !old.equals(target.get(path))) ops.add(new FileOperation(FileOperation.Type.UPDATE_FILE,path,target.get(path))); } return ops; }
    private List<Map<String,Object>> point(List<Map<String,Object>> all,String point){List<Map<String,Object>> r=new ArrayList<Map<String,Object>>();for(Map<String,Object> e:all)if(point.equals(e.get("point")))r.add(e);return r;}
    private Comparator<Map<String,Object>> contributionOrder(){return new Comparator<Map<String,Object>>(){public int compare(Map<String,Object>a,Map<String,Object>b){int c=Integer.compare(((Number)a.get("order")).intValue(),((Number)b.get("order")).intValue());if(c!=0)return c;c=String.valueOf(a.get("capabilityId")).compareTo(String.valueOf(b.get("capabilityId")));return c!=0?c:String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")));}};}
    private void assertUniqueRoute(List<Map<String,Object>> items,boolean root){Set<String> seen=new HashSet<String>();for(Map<String,Object> e:items){String path=String.valueOf(payload(e).get("path"));if(!seen.add(path)||root!=path.startsWith("/"))throw new TemplateSourceException("EXTENSION_CONFLICT: route "+path);}}
    private String componentName(Map<String,Object> item){String id=String.valueOf(item.get("capabilityId"))+"_"+String.valueOf(item.get("id"));return "Capability_"+id.replaceAll("[^A-Za-z0-9_]","_")+"Page";}
    private String variable(Map<String,Object> item){return String.valueOf(item.get("id")).replaceAll("[^A-Za-z0-9_]","_")+"Interceptor";}
    private String simple(String name){return name.substring(name.lastIndexOf('.')+1);}
    private Map<String,Object> payload(Map<String,Object> e){return map(e.get("payload"),"payload");}
    private Map<String,Object> yamlMap(Path p){try{return yaml.readValue(new String(Files.readAllBytes(p),StandardCharsets.UTF_8),new TypeReference<Map<String,Object>>(){});}catch(IOException e){throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: "+e.getMessage());}}
    @SuppressWarnings("unchecked") private Map<String,Object> map(Object v,String l){if(!(v instanceof Map))throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: "+l);return (Map<String,Object>)v;}
    private List<?> list(Object v,String l){if(v==null)return Collections.emptyList();if(!(v instanceof List))throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: "+l);return (List<?>)v;}
    private String string(Object v,String l){if(!(v instanceof String)||((String)v).trim().isEmpty())throw new TemplateSourceException("TEMPLATE_SOURCE_INVALID: "+l);return (String)v;}
    private static final class Manifest { final String id; final Path root; final Map<String,Object> data; Manifest(String id,Path root,Map<String,Object> data){this.id=id;this.root=root;this.data=data;} }
}
