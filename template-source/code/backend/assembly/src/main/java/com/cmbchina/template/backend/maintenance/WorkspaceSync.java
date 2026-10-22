package com.cmbchina.template.backend.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class WorkspaceSync {
    private final Path root;
    public WorkspaceSync(Path root) { this.root=root; }
    public void sync(String profile) {
        WorkspaceState state=WorkspaceState.read(root.resolve("workspace"));
        if(!state.profile.equals(profile)) throw new TemplateException("WORKSPACE_PROFILE_MISMATCH", "assembled " + state.profile + ", requested " + profile);
        WorkspaceStatus.Report report=WorkspaceStatus.scan(root, state); Path workspace=root.resolve("workspace");
        if(!report.created.isEmpty() && state.editTarget==null) throw new TemplateException("NO_EDIT_TARGET", "full profile cannot own new files");
        assertGeneratedContributions(workspace, state);
        syncYamlTargets(workspace, state, new HashSet<String>(report.modified));
        for(String key:report.modified) { if (state.yamlContributions.containsKey(key)) continue; String owner=state.owners.get(key); Path source=WorkspaceStatus.source(root, owner, key); createParent(source); String text=key.endsWith("Application.java") ? WorkspaceStatus.normalizedApplication(workspace.resolve(key), state) : key.equals("pom.xml") ? WorkspaceStatus.normalizedPom(workspace.resolve(key), state) : WorkspaceStatus.read(workspace.resolve(key)); write(source, text); }
        for(String key:report.deleted) { try { Files.deleteIfExists(WorkspaceStatus.source(root, state.owners.get(key), key)); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
        for(String key:report.created) { Path source=WorkspaceStatus.source(root, state.editTarget, key); createParent(source); try { Files.copy(workspace.resolve(key), source); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
        state.write(workspace);
    }
    private void syncYamlTargets(Path workspace, WorkspaceState state, Set<String> modified) {
        for (Map.Entry<String, Map<String, Map<String,Object>>> item : state.yamlContributions.entrySet()) {
            String target=item.getKey(); Map<String,Map<String,Object>> owners=item.getValue();
            if (!modified.contains(target)) continue;
            Map<String,Object> base=YamlContributionCompiler.read(WorkspaceStatus.source(root, "base", target));
            Map<String,Object> actual=YamlContributionCompiler.read(workspace.resolve(target));
            Set<String> allOwned=new TreeSet<String>(); for(Map<String,Object> value:owners.values()) allOwned.addAll(YamlContributionCompiler.leaves(value));
            for (Map.Entry<String,Map<String,Object>> owner : new ArrayList<Map.Entry<String,Map<String,Object>>>(owners.entrySet()))
                owners.put(owner.getKey(), YamlContributionCompiler.synchronizeContribution(actual, base, owner.getValue(), YamlContributionCompiler.leaves(owner.getValue()), false));
            if (state.editTarget != null) {
                Map<String,Object> editable=owners.get(state.editTarget); if(editable==null) editable=new LinkedHashMap<String,Object>();
                for (String path : YamlContributionCompiler.leaves(actual)) if (!YamlContributionCompiler.containsPath(base, path) && !allOwned.contains(path)) {
                    String[] parts=path.split("\\."); Object value=actual; for(String part:parts) value=((Map<String,Object>)value).get(part); YamlContributionCompiler.putPath(editable, path, value);
                }
                owners.put(state.editTarget, editable);
            }
            List<Map<String,Object>> values=new ArrayList<Map<String,Object>>(owners.values());
            Map<String,Object> stripped=YamlContributionCompiler.strip(actual, values);
            YamlContributionCompiler.write(WorkspaceStatus.source(root, "base", target), stripped);
            for (Map.Entry<String,Map<String,Object>> owner:owners.entrySet()) {
                if ("base".equals(owner.getKey())) throw new TemplateException("WORKSPACE_SYNC_FAILED", "Base cannot own YAML contribution");
                ExtensionManifestLoader.writeYamlContribution(root, owner.getKey(), profileName(target), owner.getValue());
            }
        }
    }
    private static String profileName(String target) { return "src/main/resources/application.yml".equals(target) ? null : target.substring("src/main/resources/application-".length(), target.length()-".yml".length()); }
    private static void createParent(Path file) { try { Files.createDirectories(file.getParent()); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
    private static void write(Path file, String text) { try { Files.write(file, text.getBytes(StandardCharsets.UTF_8)); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
    private static void assertGeneratedContributions(Path workspace, WorkspaceState state) {
        ApplicationAnnotationCompiler.assertContributionsIntact(workspace.resolve("src/main/java/com/cmbchina/backend/Application.java"), state.applicationAnnotations);
        PomDependencyCompiler.assertContributionsIntact(workspace.resolve("pom.xml"), state.mavenDependencies);
    }
}
