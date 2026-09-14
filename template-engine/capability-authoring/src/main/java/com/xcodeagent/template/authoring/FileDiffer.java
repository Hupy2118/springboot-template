package com.xcodeagent.template.authoring;

import com.xcodeagent.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Computes stable filesystem changes without interpreting source language syntax. */
public final class FileDiffer {
    private static final Set<String> IGNORED_DIRECTORIES = Collections.unmodifiableSet(
            new TreeSet<String>(Arrays.asList("node_modules", "target", "dist", ".git", ".idea", ".vscode")));

    public List<FileChange> compare(Path rawBaseline, Path rawProject) {
        Path baseline = rawBaseline.toAbsolutePath().normalize();
        Path project = rawProject.toAbsolutePath().normalize();
        Map<String, byte[]> baselineFiles = files(baseline);
        Map<String, byte[]> projectFiles = files(project);
        Set<String> paths = new TreeSet<String>();
        paths.addAll(baselineFiles.keySet());
        paths.addAll(projectFiles.keySet());
        List<FileChange> result = new ArrayList<FileChange>();
        for (String path : paths) {
            byte[] before = baselineFiles.get(path);
            byte[] after = projectFiles.get(path);
            if (before == null) result.add(new FileChange(FileChange.Type.ADDED, path));
            else if (after == null) result.add(new FileChange(FileChange.Type.DELETED, path));
            else if (!Arrays.equals(before, after)) result.add(new FileChange(FileChange.Type.MODIFIED, path));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, byte[]> files(Path root) {
        if (!Files.isDirectory(root)) throw new TemplateSourceException("WORKBENCH_DIRECTORY_MISSING: " + root);
        Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        try {
            List<Path> paths = new ArrayList<Path>();
            java.util.stream.Stream<Path> stream = Files.walk(root);
            try {
                stream.forEach(path -> {
                    if (Files.isSymbolicLink(path)) throw new TemplateSourceException("WORKBENCH_SYMLINK_UNSUPPORTED: " + path);
                    if (Files.isRegularFile(path) && !ignored(root.relativize(path))) paths.add(path);
                });
            } finally { stream.close(); }
            Collections.sort(paths);
            for (Path file : paths) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                result.put(relative, Files.readAllBytes(file));
            }
            return result;
        } catch (IOException e) { throw new TemplateSourceException("WORKBENCH_DIFF_FAILED: " + e.getMessage()); }
    }

    private static boolean ignored(Path relative) {
        for (Path part : relative) if (IGNORED_DIRECTORIES.contains(part.toString())) return true;
        return false;
    }

}
