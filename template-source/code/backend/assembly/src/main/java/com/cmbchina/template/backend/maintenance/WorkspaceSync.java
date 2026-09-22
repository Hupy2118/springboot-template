package com.cmbchina.template.backend.maintenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class WorkspaceSync {
    private final Path root;
    public WorkspaceSync(Path root) { this.root=root; }
    public void sync(String profile) {
        WorkspaceState state=WorkspaceState.read(root.resolve("workspace"));
        if(!state.profile.equals(profile)) throw new TemplateException("WORKSPACE_PROFILE_MISMATCH", "assembled " + state.profile + ", requested " + profile);
        WorkspaceStatus.Report report=WorkspaceStatus.scan(root, state); Path workspace=root.resolve("workspace");
        if(!report.created.isEmpty() && state.editTarget==null) throw new TemplateException("NO_EDIT_TARGET", "full profile cannot own new files");
        for(String key:report.modified) { String owner=state.owners.get(key); Path source=WorkspaceStatus.source(root, owner, key); createParent(source); String text=key.endsWith("Application.java") ? WorkspaceStatus.normalizedApplication(workspace.resolve(key), state) : WorkspaceStatus.read(workspace.resolve(key)); write(source, text); }
        for(String key:report.deleted) { try { Files.deleteIfExists(WorkspaceStatus.source(root, state.owners.get(key), key)); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
        for(String key:report.created) { Path source=WorkspaceStatus.source(root, state.editTarget, key); createParent(source); try { Files.copy(workspace.resolve(key), source); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
    }
    private static void createParent(Path file) { try { Files.createDirectories(file.getParent()); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
    private static void write(Path file, String text) { try { Files.write(file, text.getBytes(StandardCharsets.UTF_8)); } catch(IOException e) { throw new TemplateException("WORKSPACE_SYNC_FAILED", e.getMessage()); } }
}
