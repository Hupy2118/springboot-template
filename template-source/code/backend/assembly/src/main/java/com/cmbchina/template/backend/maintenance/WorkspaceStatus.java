package com.cmbchina.template.backend.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public final class WorkspaceStatus {
    public static final class Report {
        public final List<String> modified = new ArrayList<String>(); public final List<String> created = new ArrayList<String>(); public final List<String> deleted = new ArrayList<String>(); public final List<String> generated = new ArrayList<String>();
        public boolean changed() { return !modified.isEmpty() || !created.isEmpty() || !deleted.isEmpty(); }
    }
    private WorkspaceStatus() { }
    public static boolean hasChanges(Path root, WorkspaceState state) { return scan(root, state).changed(); }
    public static Report scan(Path root, WorkspaceState state) {
        Path workspace=root.resolve("workspace"); Report report=new Report(); Set<String> seen=new HashSet<String>();
        try { Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String key=workspace.relativize(file).toString().replace('\\','/'); if(key.startsWith(".xcodeagent/")) return FileVisitResult.CONTINUE; seen.add(key);
                String owner=state.owners.get(key); if(owner==null) { report.created.add(key); return FileVisitResult.CONTINUE; }
                Path source=source(root, owner, key); if(!Files.exists(source)) { report.modified.add(key); return FileVisitResult.CONTINUE; }
                if(key.endsWith("Application.java") && normalizedApplication(file, state).equals(read(source))) { if(!read(file).equals(read(source))) report.generated.add(key); }
                else if(!read(file).equals(read(source))) report.modified.add(key); return FileVisitResult.CONTINUE;
            }
        }); } catch(IOException e) { throw new TemplateException("WORKSPACE_STATUS_FAILED", e.getMessage()); }
        for(String key:state.owners.keySet()) if(!seen.contains(key)) report.deleted.add(key); Collections.sort(report.modified); Collections.sort(report.created); Collections.sort(report.deleted); return report;
    }
    public static Path source(Path root, String owner, String key) { return root.resolve("base".equals(owner) ? "base" : "extensions/" + owner).resolve(key); }
    public static String normalizedApplication(Path workspaceFile, WorkspaceState state) {
        String value=read(workspaceFile); for(String fqcn:state.applicationAnnotations) { String simple=fqcn.substring(fqcn.lastIndexOf('.')+1); value=value.replace("import " + fqcn + ";\n", ""); value=value.replace("@" + simple + "\n", ""); } return value;
    }
    static String read(Path file) { try { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); } catch(IOException e) { throw new TemplateException("WORKSPACE_READ_FAILED", e.getMessage()); } }
}
