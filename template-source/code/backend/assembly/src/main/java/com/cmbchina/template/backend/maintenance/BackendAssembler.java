package com.cmbchina.template.backend.maintenance;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class BackendAssembler {
    private final Path root;
    public BackendAssembler(Path root) { this.root = root; }
    public WorkspaceState assemble(String profileName, boolean reset) {
        Path workspace = root.resolve("workspace");
        if (Files.exists(WorkspaceState.file(workspace)) && !reset && WorkspaceStatus.hasChanges(root, WorkspaceState.read(workspace)))
            throw new TemplateException("UNSYNCED_WORKSPACE", "run status and sync before assembling, or use reset");
        deleteContents(workspace); create(workspace);
        ProfileResolver.Profile profile = new ProfileResolver(root.resolve("assembly/profiles.yaml")).get(profileName);
        List<ExtensionManifestLoader.Manifest> manifests = new ExtensionManifestLoader(root.resolve("extensions")).resolve(profile.requested);
        WorkspaceState state = new WorkspaceState(); state.profile = profile.name; state.editTarget = profile.editTarget;
        copyTree(root.resolve("base"), workspace, "base", state);
        List<String> annotations = new ArrayList<String>(); List<MavenDependency> dependencies = new ArrayList<MavenDependency>();
        for (ExtensionManifestLoader.Manifest manifest : manifests) {
            state.extensions.add(manifest.id); annotations.addAll(manifest.annotations); dependencies.addAll(manifest.dependencies);
            for (String directory : Arrays.asList("src", "docs", "migrations")) if (Files.exists(manifest.root.resolve(directory)))
                copyTree(manifest.root.resolve(directory), workspace.resolve(directory), manifest.id, state);
        }
        state.applicationAnnotations = new ArrayList<String>(new LinkedHashSet<String>(annotations));
        state.mavenDependencies = new ArrayList<MavenDependency>(new LinkedHashSet<MavenDependency>(dependencies));
        ApplicationAnnotationCompiler.apply(workspace.resolve("src/main/java/com/cmbchina/backend/Application.java"), state.applicationAnnotations);
        PomDependencyCompiler.apply(workspace.resolve("pom.xml"), state.mavenDependencies);
        state.write(workspace); AssemblyContract.verify(workspace); return state;
    }
    private void copyTree(final Path from, final Path to, final String owner, final WorkspaceState state) {
        try { Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { Files.createDirectories(to.resolve(from.relativize(dir))); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = from.relativize(file); Path destination = to.resolve(relative); String key = root.resolve("workspace").relativize(destination).toString().replace('\\','/');
                if (state.owners.containsKey(key)) throw new TemplateException("FILE_COLLISION", key + " owned by " + state.owners.get(key) + " and " + owner);
                Files.copy(file, destination); state.owners.put(key, owner); return FileVisitResult.CONTINUE;
            }
        }); } catch(IOException e) { throw new TemplateException("ASSEMBLY_COPY_FAILED", e.getMessage()); }
    }
    private static void create(Path path) { try { Files.createDirectories(path); } catch(IOException e) { throw new TemplateException("WORKSPACE_CREATE_FAILED", e.getMessage()); } }
    private static void deleteContents(final Path directory) {
        if(!Files.exists(directory)) return;
        try { Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if(!dir.equals(directory)) Files.delete(dir); return FileVisitResult.CONTINUE; }
        }); } catch(IOException e) { throw new TemplateException("WORKSPACE_RESET_FAILED", e.getMessage()); }
    }
}
