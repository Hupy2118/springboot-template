package com.xcodeagent.template.engine.source;

import java.nio.file.Path;

/** Immutable Stage1 result. Later phases add normalized manifests to this context. */
public final class TemplateSourceContext {
    private final Path root;
    private final String templateRevision;

    TemplateSourceContext(Path root, String templateRevision) {
        this.root = root;
        this.templateRevision = templateRevision;
    }

    public Path getRoot() { return root; }
    public String getTemplateRevision() { return templateRevision; }
}
