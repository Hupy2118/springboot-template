package com.xcodeagent.template.authoring;

/** Derives the Runtime-compatible marker for one stable V1 anchor identity. */
public final class ManagedMarker {
    private static final String COMPONENT = "[a-z0-9][a-z0-9-]*";

    private ManagedMarker() { }

    public static String of(String capabilityId, String targetId, String anchorKey) {
        if (!valid(capabilityId) || targetId == null || targetId.trim().isEmpty() || !valid(anchorKey))
            throw new IllegalArgumentException("managed marker identity");
        String value = capabilityId + "-" + targetId.replace('.', '-') + "-" + anchorKey;
        if (!valid(value)) throw new IllegalArgumentException("managed marker");
        return value;
    }

    private static boolean valid(String value) { return value != null && value.matches(COMPONENT); }
}
