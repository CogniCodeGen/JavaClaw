package com.javaclaw.framework.spi;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal SemVer 2.0 value used for immutable extension selection. */
public record SemanticVersion(int major, int minor, int patch, String qualifier)
        implements Comparable<SemanticVersion> {
    private static final Pattern FORMAT = Pattern.compile(
            "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");

    public SemanticVersion {
        qualifier = qualifier == null || qualifier.isBlank() ? null : qualifier.trim();
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("semantic version components must be non-negative");
        }
        if (qualifier != null) {
            if (!qualifier.matches("[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*")) {
                throw new IllegalArgumentException("invalid semantic version qualifier: " + qualifier);
            }
            for (String identifier : qualifier.split("\\.")) {
                if (identifier.length() > 1 && identifier.charAt(0) == '0'
                        && identifier.chars().allMatch(Character::isDigit)) {
                    throw new IllegalArgumentException(
                            "numeric prerelease identifiers must not contain leading zeroes");
                }
            }
        }
    }

    public static SemanticVersion parse(String value) {
        Matcher matcher = FORMAT.matcher(Objects.requireNonNull(value, "value").trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid semantic version: " + value);
        }
        return new SemanticVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), matcher.group(4));
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        if (result == 0) result = Integer.compare(patch, other.patch);
        if (result != 0) return result;
        if (qualifier == null) return other.qualifier == null ? 0 : 1;
        if (other.qualifier == null) return -1;
        String[] left = qualifier.split("\\.", -1);
        String[] right = other.qualifier.split("\\.", -1);
        int count = Math.min(left.length, right.length);
        for (int index = 0; index < count; index++) {
            String a = left[index];
            String b = right[index];
            boolean aNumeric = a.chars().allMatch(Character::isDigit);
            boolean bNumeric = b.chars().allMatch(Character::isDigit);
            if (aNumeric && bNumeric) {
                result = new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
            } else if (aNumeric != bNumeric) {
                result = aNumeric ? -1 : 1;
            } else {
                result = a.compareTo(b);
            }
            if (result != 0) return result;
        }
        return Integer.compare(left.length, right.length);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (qualifier == null ? "" : "-" + qualifier);
    }
}
