package com.xcodeagent.template.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.xcodeagent.template.engine.core.v2.TemplateRelease;
import com.xcodeagent.template.engine.source.CapabilityV2Loader;
import com.xcodeagent.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates an unpublished source tree by publishing a digest only inside a disposable copy. */
public final class DraftTemplateSourceValidator {
    private static final String DRAFT_REVISION = "draft-validation";
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @SuppressWarnings("unchecked")
    public TemplateRelease validate(Path rawSourceRoot) {
        Path source = rawSourceRoot.toAbsolutePath().normalize();
        Path temporary;
        try { temporary = Files.createTempDirectory("xcodeagent-draft-"); copy(source, temporary); }
        catch (IOException e) { throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: " + e.getMessage()); }
        try {
            Files.write(temporary.resolve("template-revision.txt"), (DRAFT_REVISION + "\n").getBytes(StandardCharsets.UTF_8));
            String digest = digest(temporary);
            Map<String, Object> manifest = yaml.readValue(temporary.resolve("release-digests.yaml").toFile(), Map.class);
            if (manifest == null || !(manifest.get("releases") instanceof Map)) throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: release manifest");
            ((Map<String, Object>) manifest.get("releases")).put(DRAFT_REVISION, digest);
            yaml.writeValue(temporary.resolve("release-digests.yaml").toFile(), manifest);
            return new CapabilityV2Loader().load(temporary);
        } catch (IOException e) { throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: " + e.getMessage()); }
        finally { delete(temporary); }
    }

    private static String digest(Path root) {
        try {
            MessageDigest algorithm = MessageDigest.getInstance("SHA-256"); List<Path> files = new ArrayList<Path>();
            java.util.stream.Stream<Path> paths = Files.walk(root);
            try { paths.forEach(path -> { if (Files.isSymbolicLink(path)) throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: symlink"); if (Files.isRegularFile(path)) files.add(path); }); }
            finally { paths.close(); }
            Collections.sort(files);
            for (Path file : files) { String relative = root.relativize(file).toString().replace('\\', '/'); if ("release-digests.yaml".equals(relative)) continue; algorithm.update(relative.getBytes(StandardCharsets.UTF_8)); algorithm.update((byte) 0); algorithm.update(Files.readAllBytes(file)); algorithm.update((byte) 0); }
            StringBuilder value = new StringBuilder(); for (byte item : algorithm.digest()) value.append(String.format("%02x", item & 0xff)); return value.toString();
        } catch (IOException e) { throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: " + e.getMessage()); }
        catch (NoSuchAlgorithmException e) { throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: SHA-256 unavailable"); }
    }
    private static void copy(Path from, Path to) throws IOException { java.util.stream.Stream<Path> paths = Files.walk(from); try { paths.forEach(path -> { try { Path target = to.resolve(from.relativize(path).toString()); if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target); } catch (IOException e) { throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: " + e.getMessage()); } }); } finally { paths.close(); } }
    private static void delete(Path root) { try { java.util.stream.Stream<Path> paths = Files.walk(root); try { paths.sorted(Collections.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } finally { paths.close(); } } catch (IOException ignored) { } }
}
