package com.cmbchina.template.backend.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class WorkspaceState {
    public String profile;
    public List<String> extensions = new ArrayList<String>();
    public String editTarget;
    public Map<String, String> owners = new TreeMap<String, String>();
    public List<String> applicationAnnotations = new ArrayList<String>();
    public static Path file(Path workspace) { return workspace.resolve(".xcodeagent/backend-workspace-state.json"); }
    public void write(Path workspace) {
        try { Files.createDirectories(file(workspace).getParent()); new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file(workspace).toFile(), this); }
        catch(IOException e) { throw new TemplateException("WORKSPACE_STATE_WRITE_FAILED", e.getMessage()); }
    }
    public static WorkspaceState read(Path workspace) {
        try { return new ObjectMapper().readValue(file(workspace).toFile(), WorkspaceState.class); }
        catch(IOException e) { throw new TemplateException("WORKSPACE_STATE_NOT_FOUND", e.getMessage()); }
    }
}
