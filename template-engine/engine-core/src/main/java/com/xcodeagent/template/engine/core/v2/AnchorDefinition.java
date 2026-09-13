package com.xcodeagent.template.engine.core.v2;

/** Stable named insertion point owned by a registry target. */
public final class AnchorDefinition {
    private final String targetId, anchorKey, anchor, position;

    public AnchorDefinition(String targetId, String anchorKey, String anchor, String position) {
        this.targetId = targetId;
        this.anchorKey = anchorKey;
        this.anchor = anchor;
        this.position = position;
    }

    public String targetId() { return targetId; }
    public String anchorKey() { return anchorKey; }
    public String anchor() { return anchor; }
    public String position() { return position; }
}
