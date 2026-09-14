package com.xcodeagent.template.authoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileDifferTest {
    @TempDir Path temporaryDirectory;

    @Test
    void classifiesAddedModifiedAndDeletedFilesInStablePathOrder() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline");
        Path project = temporaryDirectory.resolve("project");
        write(baseline, "deleted.txt", "before");
        write(baseline, "modified.txt", "before");
        write(baseline, "same.txt", "same");
        write(project, "added.txt", "new");
        write(project, "modified.txt", "after");
        write(project, "same.txt", "same");

        List<FileChange> changes = new FileDiffer().compare(baseline, project);

        assertEquals(Arrays.asList(
                new FileChange(FileChange.Type.ADDED, "added.txt"),
                new FileChange(FileChange.Type.DELETED, "deleted.txt"),
                new FileChange(FileChange.Type.MODIFIED, "modified.txt")), changes);
        assertEquals(changes, new FileDiffer().compare(baseline, project));
    }

    @Test
    void ignoresBuildAndEditorDirectoriesOnBothSides() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline");
        Path project = temporaryDirectory.resolve("project");
        write(baseline, "node_modules/removed.js", "before");
        write(baseline, "target/removed.class", "before");
        write(project, "dist/added.js", "after");
        write(project, ".idea/workspace.xml", "after");
        write(project, ".vscode/settings.json", "after");
        write(project, ".git/config", "after");

        assertEquals(Arrays.<FileChange>asList(), new FileDiffer().compare(baseline, project));
    }

    @Test
    void comparesBinaryFileContentWithoutAContentDigest() throws Exception {
        Path baseline = temporaryDirectory.resolve("baseline");
        Path project = temporaryDirectory.resolve("project");
        Path before = baseline.resolve("asset.bin");
        Path after = project.resolve("asset.bin");
        Files.createDirectories(before.getParent());
        Files.createDirectories(after.getParent());
        Files.write(before, new byte[] { 0, 1, 2, 3 });
        Files.write(after, new byte[] { 0, 1, 2, 4 });

        assertEquals(Arrays.asList(new FileChange(FileChange.Type.MODIFIED, "asset.bin")),
                new FileDiffer().compare(baseline, project));
    }

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
