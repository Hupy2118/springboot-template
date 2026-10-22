package com.cmbchina.template.backend.maintenance;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Applies the same deterministic, conflict-safe application.yml contributions as V3 Core. */
final class ApplicationYamlCompiler {
    private ApplicationYamlCompiler() { }

    @SuppressWarnings("unchecked")
    static void apply(Path file, List<Map<String,Object>> contributions) {
        if (!contributions.isEmpty()) YamlContributionCompiler.write(file, YamlContributionCompiler.project(YamlContributionCompiler.read(file), contributions));
    }
}
