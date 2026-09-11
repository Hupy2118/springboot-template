package com.xcodeagent.template.engine.core.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CapabilityDefinitionV2 {
    private final String id;
    private final List<String> requires;
    private final Map<String, Object> defaultConfig;
    private final List<ExistingTargetDefinition> existingTargets;
    private final List<AdditionDefinition> additions;
    private final List<MigrationDefinition> migrations;
    private final List<Map<String, Object>> validators;

    public CapabilityDefinitionV2(String id, List<String> requires, Map<String, Object> defaultConfig,
                                  List<ExistingTargetDefinition> existingTargets, List<AdditionDefinition> additions, List<MigrationDefinition> migrations,
                                  List<Map<String, Object>> validators) {
        this.id = id;
        this.requires = Collections.unmodifiableList(new ArrayList<String>(requires));
        this.defaultConfig = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(defaultConfig));
        this.existingTargets = Collections.unmodifiableList(new ArrayList<ExistingTargetDefinition>(existingTargets));
        this.additions = Collections.unmodifiableList(new ArrayList<AdditionDefinition>(additions));
        this.migrations = Collections.unmodifiableList(new ArrayList<MigrationDefinition>(migrations));
        List<Map<String, Object>> copied = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : validators) copied.add(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(item)));
        this.validators = Collections.unmodifiableList(copied);
    }
    public String id() { return id; }
    public List<String> requires() { return requires; }
    public Map<String, Object> defaultConfig() { return defaultConfig; }
    public List<ExistingTargetDefinition> existingTargets() { return existingTargets; }
    public List<AdditionDefinition> additions() { return additions; }
    public List<MigrationDefinition> migrations() { return migrations; }
    public List<Map<String, Object>> validators() { return validators; }
}
