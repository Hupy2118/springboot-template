package com.devagentstudio.template.authoring;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** Copies and commits only the source paths owned by the Legacy V2 authoring tools. */
final class LegacyV2SourceTree {
    private static final String[] COMPONENTS = {
            "base", "capabilities", "strategy-registry-v2.yaml", "template-revision.txt"
    };

    private LegacyV2SourceTree() { }

    static Path createStage(Path rawSourceRoot, String prefix) throws IOException {
        Path sourceRoot = rawSourceRoot.toAbsolutePath().normalize();
        Path stage = Files.createTempDirectory(sourceRoot.getParent(), prefix);
        try {
            copy(sourceRoot, stage);
            return stage;
        } catch (IOException | RuntimeException e) {
            delete(stage);
            throw e;
        }
    }

    static void copy(Path rawSourceRoot, Path rawTargetRoot) throws IOException {
        Path sourceRoot = rawSourceRoot.toAbsolutePath().normalize();
        Path targetRoot = rawTargetRoot.toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        for (String component : COMPONENTS) {
            Path source = sourceRoot.resolve(component);
            if (Files.exists(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                copyTree(source, targetRoot.resolve(component));
            }
        }
    }

    /** Replaces only staged V2 components, leaving every other source tree untouched. */
    static void replace(Path rawSourceRoot, Path rawStageRoot, String... components) throws IOException {
        Path sourceRoot = rawSourceRoot.toAbsolutePath().normalize();
        Path stageRoot = rawStageRoot.toAbsolutePath().normalize();
        Path backupRoot = Files.createTempDirectory(sourceRoot.getParent(), ".legacy-v2-backup-");
        List<String> backedUp = new ArrayList<String>();
        List<String> installed = new ArrayList<String>();
        boolean committed = false;
        boolean preserveBackup = false;
        try {
            for (String component : components) {
                Path staged = stageRoot.resolve(component);
                if (!Files.exists(staged, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                Path destination = sourceRoot.resolve(component);
                Path backup = backupRoot.resolve(component);
                if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(backup.getParent());
                    move(destination, backup);
                    backedUp.add(component);
                }
                Files.createDirectories(destination.getParent());
                move(staged, destination);
                installed.add(component);
            }
            committed = true;
        } catch (IOException failure) {
            IOException rollbackFailure = rollback(sourceRoot, backupRoot, installed, backedUp);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
                preserveBackup = true;
            }
            throw failure;
        } finally {
            if (committed || !preserveBackup) {
                try { delete(backupRoot); }
                catch (RuntimeException ignored) { }
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Stream<Path> paths = Files.walk(source);
        try {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(path)) throw new IOException("V2 Runtime Source must not contain symlinks: " + path);
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } finally {
            paths.close();
        }
    }

    private static IOException rollback(Path sourceRoot, Path backupRoot,
                                        List<String> installed, List<String> backedUp) {
        IOException failure = null;
        List<String> reverseInstalled = new ArrayList<String>(installed);
        Collections.reverse(reverseInstalled);
        for (String component : reverseInstalled) {
            try { delete(sourceRoot.resolve(component)); }
            catch (RuntimeException e) { failure = append(failure, e); }
        }
        List<String> reverseBackedUp = new ArrayList<String>(backedUp);
        Collections.reverse(reverseBackedUp);
        for (String component : reverseBackedUp) {
            try {
                Path backup = backupRoot.resolve(component);
                if (Files.exists(backup, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Path destination = sourceRoot.resolve(component);
                    Files.createDirectories(destination.getParent());
                    move(backup, destination);
                }
            } catch (IOException e) { failure = append(failure, e); }
        }
        return failure;
    }

    private static IOException append(IOException current, Exception next) {
        IOException result = current == null ? new IOException("Legacy V2 source rollback failed") : current;
        result.addSuppressed(next);
        return result;
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target); }
    }

    private static void delete(Path root) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Stream<Path> paths = null;
        try {
            paths = Files.walk(root);
            for (Path path : (Iterable<Path>) paths.sorted(Collections.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot clean Legacy V2 staging path " + root, e);
        } finally {
            if (paths != null) paths.close();
        }
    }
}
