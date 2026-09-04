package com.xcodeagent.template.engine.core;

import com.xcodeagent.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Test-only local workspace executor. It has no HTTP or project lifecycle responsibility. */
public final class WorkspaceFixture {
    public void apply(Path workspace, CorePlanResult plan) {
        try {
            for (FileOperation operation : plan.operations()) check(workspace, operation);
            for (FileOperation operation : plan.operations()) write(workspace, operation);
        } catch (IOException e) { throw new TemplateSourceException("WORKSPACE_APPLY_FAILED: " + e.getMessage()); }
    }

    private void check(Path workspace, FileOperation operation) {
        Path path = workspace.resolve(operation.path()).normalize();
        if (!path.startsWith(workspace)) throw new TemplateSourceException("WORKSPACE_PATH_INVALID: " + operation.path());
        boolean exists = Files.exists(path);
        if (operation.type() == FileOperation.Type.ADD_FILE && exists) throw new TemplateSourceException("OPERATION_PRECONDITION_FAILED: add " + operation.path());
        if (operation.type() != FileOperation.Type.ADD_FILE && (!exists || !Files.isRegularFile(path))) throw new TemplateSourceException("OPERATION_PRECONDITION_FAILED: " + operation.path());
    }

    private void write(Path workspace, FileOperation operation) throws IOException {
        Path path = workspace.resolve(operation.path()).normalize();
        if (operation.type() == FileOperation.Type.DELETE_FILE) { Files.delete(path); return; }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".engine-", ".tmp");
        Files.write(temporary, operation.content().getBytes(StandardCharsets.UTF_8));
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
