package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.SemanticVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** SemVer range used by definitions and extension dependencies. */
public final class VersionRange {
    private final String expression;
    private final List<Predicate<SemanticVersion>> constraints;

    private VersionRange(String expression, List<Predicate<SemanticVersion>> constraints) {
        this.expression = expression;
        this.constraints = List.copyOf(constraints);
    }

    public static VersionRange parse(String raw) {
        String expression = Objects.requireNonNull(raw, "raw").trim();
        if (expression.isEmpty() || expression.equals("*") || expression.equalsIgnoreCase("latest")) {
            return new VersionRange(expression, List.of(version -> true));
        }
        if ((expression.startsWith("[") || expression.startsWith("("))
                && (expression.endsWith("]") || expression.endsWith(")"))) {
            return parseInterval(expression);
        }
        if (expression.startsWith("^")) {
            SemanticVersion lower = SemanticVersion.parse(expression.substring(1));
            SemanticVersion upper = lower.major() > 0
                    ? new SemanticVersion(lower.major() + 1, 0, 0, null)
                    : new SemanticVersion(0, lower.minor() + 1, 0, null);
            return new VersionRange(expression, List.of(
                    version -> version.compareTo(lower) >= 0,
                    version -> version.compareTo(upper) < 0));
        }
        if (expression.startsWith("~")) {
            SemanticVersion lower = SemanticVersion.parse(expression.substring(1));
            SemanticVersion upper = new SemanticVersion(lower.major(), lower.minor() + 1, 0, null);
            return new VersionRange(expression, List.of(
                    version -> version.compareTo(lower) >= 0,
                    version -> version.compareTo(upper) < 0));
        }
        if (expression.contains(" ") || expression.startsWith(">")
                || expression.startsWith("<") || expression.startsWith("=")) {
            List<Predicate<SemanticVersion>> constraints = new ArrayList<>();
            for (String token : expression.split("\\s+")) {
                if (!token.isBlank()) constraints.add(comparator(token));
            }
            return new VersionRange(expression, constraints);
        }
        SemanticVersion exact = SemanticVersion.parse(expression);
        return new VersionRange(expression, List.of(exact::equals));
    }

    private static VersionRange parseInterval(String expression) {
        String[] bounds = expression.substring(1, expression.length() - 1).split(",", -1);
        if (bounds.length != 2) throw new IllegalArgumentException("invalid version interval: " + expression);
        List<Predicate<SemanticVersion>> constraints = new ArrayList<>();
        if (!bounds[0].isBlank()) {
            SemanticVersion lower = SemanticVersion.parse(bounds[0].trim());
            constraints.add(expression.startsWith("[")
                    ? version -> version.compareTo(lower) >= 0
                    : version -> version.compareTo(lower) > 0);
        }
        if (!bounds[1].isBlank()) {
            SemanticVersion upper = SemanticVersion.parse(bounds[1].trim());
            constraints.add(expression.endsWith("]")
                    ? version -> version.compareTo(upper) <= 0
                    : version -> version.compareTo(upper) < 0);
        }
        return new VersionRange(expression, constraints);
    }

    private static Predicate<SemanticVersion> comparator(String token) {
        String operator;
        if (token.startsWith(">=")) operator = ">=";
        else if (token.startsWith("<=")) operator = "<=";
        else if (token.startsWith(">")) operator = ">";
        else if (token.startsWith("<")) operator = "<";
        else if (token.startsWith("=")) operator = "=";
        else return SemanticVersion.parse(token)::equals;
        SemanticVersion target = SemanticVersion.parse(token.substring(operator.length()));
        return switch (operator) {
            case ">=" -> version -> version.compareTo(target) >= 0;
            case "<=" -> version -> version.compareTo(target) <= 0;
            case ">" -> version -> version.compareTo(target) > 0;
            case "<" -> version -> version.compareTo(target) < 0;
            default -> target::equals;
        };
    }

    public boolean contains(SemanticVersion version) {
        return constraints.stream().allMatch(constraint -> constraint.test(version));
    }

    @Override
    public String toString() {
        return expression;
    }
}
