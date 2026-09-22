package com.devagentstudio.template.authoring;

import com.devagentstudio.template.engine.source.TemplateSourceException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local ownership record for the Draft produced by exactly one Workbench. */
public final class CompileState {
    private final String capabilityId;
    private final List<String> strategyIds;
    private final List<String> validatorIds;

    public CompileState(String capabilityId, List<String> strategyIds, List<String> validatorIds) {
        if (capabilityId == null || !capabilityId.matches("[a-z0-9][a-z0-9-]*")) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT");
        this.capabilityId = capabilityId;
        this.strategyIds = ids(strategyIds);
        this.validatorIds = ids(validatorIds);
    }
    public String capabilityId() { return capabilityId; }
    public List<String> strategyIds() { return strategyIds; }
    public List<String> validatorIds() { return validatorIds; }
    private static List<String> ids(List<String> values) {
        List<String> result = new ArrayList<String>();
        if (values != null) for (String value : values) {
            if (value == null || value.trim().isEmpty() || result.contains(value)) throw new TemplateSourceException("CAPABILITY_OWNERSHIP_CONFLICT");
            result.add(value);
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }
}
