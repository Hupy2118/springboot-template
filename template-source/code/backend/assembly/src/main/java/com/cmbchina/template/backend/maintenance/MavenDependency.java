package com.cmbchina.template.backend.maintenance;

import java.util.Objects;

public final class MavenDependency {
    public String groupId;
    public String artifactId;
    public String version;
    public String scope;

    public MavenDependency() { }
    public MavenDependency(String groupId, String artifactId, String version, String scope) {
        this.groupId = groupId; this.artifactId = artifactId; this.version = version; this.scope = scope;
    }
    public String coordinate() { return groupId + ":" + artifactId; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof MavenDependency)) return false;
        MavenDependency that = (MavenDependency) other;
        return Objects.equals(groupId, that.groupId) && Objects.equals(artifactId, that.artifactId)
                && Objects.equals(version, that.version) && Objects.equals(scope, that.scope);
    }
    @Override public int hashCode() { return Objects.hash(groupId, artifactId, version, scope); }
}
