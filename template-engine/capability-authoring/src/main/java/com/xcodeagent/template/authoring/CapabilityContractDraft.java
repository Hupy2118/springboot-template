package com.xcodeagent.template.authoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Compiler-only aggregate that adds explicit migration and validation contracts to a change draft. */
public final class CapabilityContractDraft {
    private final CapabilityDraft capability;
    private final List<String> requires;
    private final List<MigrationDraft> migrations;
    private final List<ValidatorDraft> validators;
    public CapabilityContractDraft(CapabilityDraft capability, List<String> requires, List<MigrationDraft> migrations, List<ValidatorDraft> validators) {
        this.capability = capability;
        this.requires = Collections.unmodifiableList(new ArrayList<String>(requires));
        this.migrations = Collections.unmodifiableList(new ArrayList<MigrationDraft>(migrations));
        this.validators = Collections.unmodifiableList(new ArrayList<ValidatorDraft>(validators));
    }
    public CapabilityDraft capability() { return capability; }
    public List<String> requires() { return requires; }
    public List<MigrationDraft> migrations() { return migrations; }
    public List<ValidatorDraft> validators() { return validators; }
}
