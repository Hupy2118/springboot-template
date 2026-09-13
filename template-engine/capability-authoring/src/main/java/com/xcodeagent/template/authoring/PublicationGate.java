package com.xcodeagent.template.authoring;

import java.nio.file.Path;

/** Test/CI-only external gates; production Java Runtime never implements XCodeAgent execution. */
public interface PublicationGate {
    void verifyExecutorEquivalence(Path sourceRoot);
    void verifyValidators(Path sourceRoot);
}
