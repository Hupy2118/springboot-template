package com.xcodeagent.template.engine.core.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xcodeagent.template.engine.source.TemplateSourceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Materializes a new project solely from V2 source metadata and registry definitions. */
public final class V2ProjectGenerator {
    private final Path root; private final TemplateRelease release; private final ObjectMapper json = new ObjectMapper();
    public V2ProjectGenerator(Path root, TemplateRelease release) { this.root = root; this.release = release; }
    public Map<String, String> generate(TemplateStateV2 state) {
        Map<String, String> files = base(); List<StrategyDefinition> strategies = new ArrayList<StrategyDefinition>();
        for (String capabilityId : state.effective().keySet()) {
            CapabilityDefinitionV2 capability = release.capabilities().get(capabilityId);
            for (AdditionDefinition addition : capability.additions()) files.put(addition.target(), read(root.resolve("capabilities").resolve(capabilityId).resolve(addition.source())));
            for (MigrationDefinition migration : capability.migrations()) files.put(migration.target(), read(root.resolve("capabilities").resolve(capabilityId).resolve(migration.source())));
            for (ExistingTargetDefinition target : capability.existingTargets()) strategies.add(release.strategies().get(target.strategyId()));
        }
        Collections.sort(strategies, new Comparator<StrategyDefinition>() { public int compare(StrategyDefinition a, StrategyDefinition b) { int c = Integer.compare(a.order(), b.order()); return c != 0 ? c : a.id().compareTo(b.id()); } });
        render(files, strategies); return files;
    }
    private Map<String, String> base() {
        Map<String, String> result = new LinkedHashMap<String, String>(); Path base = root.resolve("base"); List<Path> paths = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(base)) { stream.filter(Files::isRegularFile).forEach(paths::add); } catch (IOException e) { throw failure(e); }
        Collections.sort(paths); for (Path path : paths) { String relative = base.relativize(path).toString().replace('\\', '/'); if (!"base.yaml".equals(relative) && !"extension-registry.yaml".equals(relative) && !relative.startsWith("schemas/")) result.put(relative, read(path)); }
        return result;
    }
    private void render(Map<String, String> files, List<StrategyDefinition> all) {
        for (StrategyDefinition strategy : all) {
            String current = files.get(strategy.target());
            if (current == null) throw new TemplateSourceException("BASE_SURFACE_TARGET_MISSING: " + strategy.target());
            if ("ENSURE_IMPORT".equals(strategy.type())) files.put(strategy.target(), ensureImport(current, String.valueOf(strategy.parameters().get("importStatement"))));
            else if ("TEXT_ANCHOR_INSERT".equals(strategy.type())) files.put(strategy.target(), managedInsert(current, strategy.parameters()));
            else throw new TemplateSourceException("GENERATE_STRATEGY_UNSUPPORTED: " + strategy.type());
        }
    }

    private static String ensureImport(String source, String statement) {
        if (source.contains(statement)) return source;
        int lastImport = source.lastIndexOf("import ");
        if (lastImport < 0) throw new TemplateSourceException("IMPORT_SURFACE_MISSING");
        int end = source.indexOf('\n', lastImport);
        if (end < 0) end = source.length();
        return source.substring(0, end + 1) + statement + "\n" + source.substring(end + 1);
    }

    private static String managedInsert(String source, Map<String, Object> parameters) {
        String anchor = String.valueOf(parameters.get("anchor"));
        String marker = String.valueOf(parameters.get("managedMarker"));
        String content = String.valueOf(parameters.get("content"));
        String begin = "/* xcodeagent:" + marker + ":begin */";
        String end = "/* xcodeagent:" + marker + ":end */";
        int firstBegin = source.indexOf(begin); int firstEnd = source.indexOf(end);
        if (firstBegin >= 0 || firstEnd >= 0) {
            if (firstBegin < 0 || firstEnd < firstBegin || source.indexOf(begin, firstBegin + begin.length()) >= 0 || source.indexOf(end, firstEnd + end.length()) >= 0)
                throw new TemplateSourceException("MANAGED_BLOCK_INVALID: " + marker);
            return source.substring(0, firstBegin) + content + source.substring(firstEnd + end.length());
        }
        int index = source.indexOf(anchor);
        if (index < 0 || source.indexOf(anchor, index + anchor.length()) >= 0) throw new TemplateSourceException("ANCHOR_NOT_UNIQUE: " + anchor);
        int insertion = "after".equals(parameters.get("position")) ? index + anchor.length() : index;
        return source.substring(0, insertion) + content + "\n" + source.substring(insertion);
    }
    private List<StrategyDefinition> point(List<StrategyDefinition> all, String point) { List<StrategyDefinition> result = new ArrayList<StrategyDefinition>(); for (StrategyDefinition strategy : all) if (point.equals(strategy.parameters().get("point"))) result.add(strategy); return result; }
    private String providers(List<StrategyDefinition> all) { StringBuilder out = new StringBuilder("import type { PropsWithChildren } from 'react';\n"); for (StrategyDefinition s : all) out.append("import { ").append(p(s,"export")).append(" } from '").append(p(s,"module")).append("';\n"); out.append("\nexport function CapabilityProviders({ children }: PropsWithChildren) {\n  return "); for (StrategyDefinition s : all) out.append("<").append(p(s,"export")).append(">"); out.append("{children}"); for (int i=all.size()-1;i>=0;i--) out.append("</").append(p(all.get(i),"export")).append(">"); return out.append(";\n}\n").toString(); }
    private String routes(List<StrategyDefinition> roots, List<StrategyDefinition> pages, List<StrategyDefinition> wrappers) { StringBuilder out = new StringBuilder("import { lazy, Suspense, type ReactNode } from 'react';\nimport type { RouteObject } from 'react-router-dom';\nimport type { PageRouteDefinition } from '@/typings/routes';\n"); List<StrategyDefinition> components=new ArrayList<StrategyDefinition>(); components.addAll(roots); components.addAll(pages); for(StrategyDefinition s:components) out.append("const ").append(name(s)).append(" = lazy(() => import('").append(p(s,"module")).append("').then(m => ({ default: ").append("default".equals(p(s,"exportType"))?"m.default":"m."+p(s,"export")).append(" })));\n"); for(StrategyDefinition s:wrappers)out.append("import { ").append(p(s,"export")).append(" } from '").append(p(s,"module")).append("';\n"); out.append("\nexport const capabilityRootRoutes: RouteObject[] = [\n"); for(StrategyDefinition s:roots)out.append("  { path: '").append(p(s,"path")).append("', element: <Suspense><").append(name(s)).append(" /></Suspense> },\n"); out.append("];\n"); StrategyDefinition entry=null;for(StrategyDefinition s:roots)if(Boolean.TRUE.equals(s.parameters().get("entry")))entry=s; out.append("export const capabilityEntryPath: string | undefined = ").append(entry==null?"undefined":"'"+p(entry,"path")+"'").append(";\nexport const capabilityPageRoutes: PageRouteDefinition[] = [\n"); for(StrategyDefinition s:pages){out.append("  { routeId: '").append(p(s,"contributionId")).append("', path: '").append(p(s,"path")).append("', name: '").append(p(s,"name")).append("', component: ").append(name(s));if(s.parameters().get("resourceKey")!=null)out.append(", resourceKey: '").append(p(s,"resourceKey")).append("'");out.append(" },\n");}out.append("];\n");String wrapped="element";for(int i=wrappers.size()-1;i>=0;i--)wrapped=p(wrappers.get(i),"export")+"("+wrapped+", page)";return out.append("export const wrapCapabilityPage = (element: ReactNode, page: PageRouteDefinition): ReactNode => ").append(wrapped).append(";\n").toString(); }
    private String menus(List<StrategyDefinition> all) { StringBuilder out=new StringBuilder("import type { Route } from '@/typings/workbench';\n");for(StrategyDefinition s:all)out.append("import { ").append(p(s,"export")).append(" } from '").append(p(s,"module")).append("';\n");out.append("\nexport const useCapabilityMenus = (menus: Route[]): Route[] => ");for(int i=all.size()-1;i>=0;i--)out.append(p(all.get(i),"export")).append("(");out.append("menus");for(int i=0;i<all.size();i++)out.append(")");return out.append(";\n").toString(); }
    private String interceptors(List<StrategyDefinition> all) { StringBuilder out=new StringBuilder("package com.cmbchina.backend.common.config;\n\nimport org.springframework.context.annotation.Configuration;\nimport org.springframework.web.servlet.config.annotation.InterceptorRegistry;\nimport org.springframework.web.servlet.config.annotation.WebMvcConfigurer;\n");for(StrategyDefinition s:all)out.append("import ").append(p(s,"className")).append(";\n");out.append("\n@Configuration\npublic class CapabilityWebMvcConfiguration implements WebMvcConfigurer {\n");for(StrategyDefinition s:all)out.append("    private final ").append(simple(p(s,"className"))).append(" ").append(variable(s)).append(";\n");if(!all.isEmpty()){out.append("\n    public CapabilityWebMvcConfiguration(");for(int i=0;i<all.size();i++){if(i>0)out.append(", ");out.append(simple(p(all.get(i),"className"))).append(" ").append(variable(all.get(i)));}out.append(") {\n");for(StrategyDefinition s:all)out.append("        this.").append(variable(s)).append(" = ").append(variable(s)).append(";\n");out.append("    }\n");}out.append("\n    @Override public void addInterceptors(InterceptorRegistry registry) {\n");for(StrategyDefinition s:all){out.append("        registry.addInterceptor(").append(variable(s)).append(").order(").append(p(s,"interceptorOrder")).append(")");for(Object path:list(s.parameters().get("pathPatterns")))out.append(".addPathPatterns(\"").append(path).append("\")");for(Object path:list(s.parameters().get("excludePathPatterns")))out.append(".excludePathPatterns(\"").append(path).append("\")");out.append(";\n");}return out.append("    }\n}\n").toString(); }
    private void npm(Map<String,String> files,Map<String,Object> p){try{Map<String,Object> pkg=json.readValue(files.get("frontend/package.json"),new TypeReference<LinkedHashMap<String,Object>>(){});Map<String,Object> deps=pkg.get("dependencies") instanceof Map?new LinkedHashMap<String,Object>((Map<String,Object>)pkg.get("dependencies")):new LinkedHashMap<String,Object>();deps.put(String.valueOf(p.get("name")),p.get("version"));pkg.put("dependencies",deps);files.put("frontend/package.json",json.writerWithDefaultPrettyPrinter().writeValueAsString(pkg)+"\n");}catch(IOException e){throw failure(e);}}
    private static String p(StrategyDefinition s,String key){return String.valueOf(s.parameters().get(key));} private static String name(StrategyDefinition s){return "Capability_"+s.id().replaceAll("[^A-Za-z0-9_]","_")+"Page";} private static String variable(StrategyDefinition s){return String.valueOf(s.parameters().get("contributionId")).replaceAll("[^A-Za-z0-9_]","_")+"Interceptor";} private static String simple(String v){return v.substring(v.lastIndexOf('.')+1);} private static List<?> list(Object value){return value instanceof List?(List<?>)value:Collections.emptyList();} private static String read(Path path){try{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}catch(IOException e){throw failure(e);}} private static TemplateSourceException failure(Exception e){return new TemplateSourceException("TEMPLATE_SOURCE_INVALID: "+e.getMessage());}
}
