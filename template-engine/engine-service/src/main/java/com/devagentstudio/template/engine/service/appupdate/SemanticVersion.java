package com.devagentstudio.template.engine.service.appupdate;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable SemVer 2.0.0 value; build metadata does not affect precedence. */
final class SemanticVersion implements Comparable<SemanticVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private final BigInteger[] core;
    private final String[] prerelease;

    private SemanticVersion(BigInteger[] core, String[] prerelease) {
        this.core = core;
        this.prerelease = prerelease;
    }

    static SemanticVersion parse(String value) {
        if (value == null) throw invalid();
        Matcher match = FORMAT.matcher(value);
        if (!match.matches()) throw invalid();
        String[] prerelease = match.group(4) == null ? new String[0] : match.group(4).split("\\.");
        for (String identifier : prerelease) {
            if (numeric(identifier) && identifier.length() > 1 && identifier.charAt(0) == '0') throw invalid();
        }
        return new SemanticVersion(new BigInteger[] {
                new BigInteger(match.group(1)), new BigInteger(match.group(2)), new BigInteger(match.group(3))
        }, prerelease);
    }

    boolean isPrerelease() { return prerelease.length != 0; }

    /** Compares arbitrary-size numeric identifiers without integer overflow. */
    @Override
    public int compareTo(SemanticVersion other) {
        for (int index = 0; index < core.length; index++) {
            int comparison = core[index].compareTo(other.core[index]);
            if (comparison != 0) return comparison;
        }
        if (!isPrerelease() || !other.isPrerelease()) {
            return Boolean.compare(other.isPrerelease(), isPrerelease());
        }
        for (int index = 0; index < Math.min(prerelease.length, other.prerelease.length); index++) {
            String left = prerelease[index];
            String right = other.prerelease[index];
            boolean leftNumeric = numeric(left);
            boolean rightNumeric = numeric(right);
            int comparison;
            if (leftNumeric && rightNumeric) comparison = new BigInteger(left).compareTo(new BigInteger(right));
            else if (leftNumeric != rightNumeric) comparison = leftNumeric ? -1 : 1;
            else comparison = left.compareTo(right);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(prerelease.length, other.prerelease.length);
    }

    private static boolean numeric(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
        }
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("version must be valid SemVer 2.0.0");
    }
}
