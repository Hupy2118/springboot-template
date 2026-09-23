package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.core.v2.TemplateRelease;
import com.devagentstudio.template.engine.source.CapabilityV2Loader;
import com.devagentstudio.template.engine.source.TemplateSourceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/** Validates an unpublished source tree against the same semantic loader used for releases. */
public final class DraftTemplateSourceValidator {
    private static final String DRAFT_REVISION = "draft-validation";

    public TemplateRelease validate(Path rawSourceRoot) {
        Path source = rawSourceRoot.toAbsolutePath().normalize();
        Path temporary;
        try { temporary = Files.createTempDirectory("devagentstudio-draft-"); LegacyV2SourceTree.copy(source, temporary); }
        catch (IOException e) { throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: " + e.getMessage()); }
        try {
            Files.write(temporary.resolve("template-revision.txt"), (DRAFT_REVISION + "\n").getBytes(StandardCharsets.UTF_8));
            return new CapabilityV2Loader().load(temporary);
        } catch (IOException e) { throw new TemplateSourceException("DRAFT_VALIDATION_FAILED: " + e.getMessage()); }
        finally { delete(temporary); }
    }

    private static void delete(Path root) { try { java.util.stream.Stream<Path> paths = Files.walk(root); try { paths.sorted(Collections.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } finally { paths.close(); } } catch (IOException ignored) { } }
}
