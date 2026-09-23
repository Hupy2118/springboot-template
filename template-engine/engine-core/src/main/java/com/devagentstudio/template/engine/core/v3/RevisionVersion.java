package com.devagentstudio.template.engine.core.v3;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric YYYY.MM.DD.N ordering for Code Template Releases. */
public final class RevisionVersion implements Comparable<RevisionVersion> {
    private static final Pattern FORMAT = Pattern.compile("^(\\d{4})\\.(\\d{2})\\.(\\d{2})\\.([1-9]\\d*)$");
    private final int year;
    private final int month;
    private final int day;
    private final BigInteger sequence;
    private final String value;

    private RevisionVersion(String value, int year, int month, int day, BigInteger sequence) {
        this.value = value; this.year = year; this.month = month; this.day = day; this.sequence = sequence;
    }

    public static RevisionVersion parse(String value) {
        Matcher match = FORMAT.matcher(value == null ? "" : value.trim());
        if (!match.matches()) throw new IllegalArgumentException("revision must use YYYY.MM.DD.N with N >= 1");
        try {
            int year = Integer.parseInt(match.group(1));
            int month = Integer.parseInt(match.group(2));
            int day = Integer.parseInt(match.group(3));
            LocalDate.of(year, month, day);
            return new RevisionVersion(value.trim(), year, month, day, new BigInteger(match.group(4)));
        } catch (DateTimeException | NumberFormatException e) {
            throw new IllegalArgumentException("revision has invalid date or sequence", e);
        }
    }

    @Override public int compareTo(RevisionVersion other) {
        int result = Integer.compare(year, other.year);
        if (result == 0) result = Integer.compare(month, other.month);
        if (result == 0) result = Integer.compare(day, other.day);
        if (result == 0) result = sequence.compareTo(other.sequence);
        return result;
    }

    @Override public String toString() { return value; }
}
