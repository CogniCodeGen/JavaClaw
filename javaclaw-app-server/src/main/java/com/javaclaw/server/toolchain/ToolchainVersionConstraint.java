package com.javaclaw.server.toolchain;

import java.util.Arrays;
import java.util.regex.Pattern;

/** 有界静态版本约束；不识别的表达式明确失败，禁止执行项目脚本求值。 */
final class ToolchainVersionConstraint {
    private static final Pattern PART =
            Pattern.compile("(>=|<=|!=|==|=|>|<|\\^|~=|~)?([0-9]+(?:\\.[0-9xX*]+){0,2}|[xX*])");

    private ToolchainVersionConstraint() {}

    static boolean matches(String version, String constraint) {
        return matches(version, constraint, false);
    }

    static boolean matches(String version, String constraint, boolean python) {
        if (constraint.length() > 200 || constraint.isBlank()) {
            throw new IllegalArgumentException("版本约束为空或过长");
        }
        boolean result = false;
        for (String alternative : constraint.strip().split("\\s*\\|\\|\\s*", -1)) {
            result = conjunction(version, alternative, python) || result;
        }
        return result;
    }

    private static boolean conjunction(String version, String constraint, boolean python) {
        String normalized = constraint.strip().replaceAll("([<>=~^!]+)\\s+", "$1");
        var hyphen = Pattern.compile("([0-9.]+)\\s+-\\s+([0-9.]+)").matcher(normalized);
        if (hyphen.matches()) {
            normalized = ">=" + hyphen.group(1) + " <=" + hyphen.group(2);
        }
        boolean result = true;
        for (String part : normalized.split("[ ,]+")) {
            var matcher = PART.matcher(part);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("无法静态解析版本约束: " + part);
            }
            // 仍解析后续子句，避免短路掩盖无效声明。
            result = single(version, matcher.group(1), matcher.group(2), python) && result;
        }
        return result;
    }

    private static boolean single(String version, String operator, String required, boolean python) {
        int[] actual = numbers(version);
        Bound bound = bound(required);
        String op = operator == null ? "=" : operator;
        if (op.equals("!=") || op.equals("==") || op.equals("=")) {
            int count = python && operator != null && bound.exact() ? 3 : bound.specified();
            return prefix(actual, bound.lower(), count) != op.equals("!=");
        }
        return ordered(actual, bound, op, python);
    }

    private static Bound bound(String required) {
        String[] parts = required.split("\\.");
        int specified = 0;
        while (specified < parts.length && parts[specified].matches("[0-9]+")) {
            specified++;
        }
        int[] lower = new int[3];
        for (int index = 0; index < specified; index++) {
            lower[index] = Integer.parseInt(parts[index]);
        }
        return new Bound(lower, specified, specified == parts.length);
    }

    private static boolean ordered(int[] actual, Bound bound, String op, boolean python) {
        int[] lower = bound.lower();
        int specified = bound.specified();
        boolean partial = specified < 3 && !python;
        int comparison = Arrays.compare(actual, lower);
        return switch (op) {
            case ">=" -> comparison >= 0;
            case ">" -> partial ? Arrays.compare(actual, nextPartial(lower, specified)) >= 0 : comparison > 0;
            case "<=" -> partial ? Arrays.compare(actual, nextPartial(lower, specified)) < 0 : comparison <= 0;
            case "<" -> comparison < 0;
            case "^", "~", "~=" -> comparison >= 0 && Arrays.compare(actual, upper(lower, specified, op)) < 0;
            default -> throw new IllegalArgumentException("无法识别版本运算符");
        };
    }

    private record Bound(int[] lower, int specified, boolean exact) {}

    private static int[] nextPartial(int[] lower, int specified) {
        int[] upper = lower.clone();
        int index = Math.max(0, specified - 1);
        upper[index] = Math.addExact(upper[index], 1);
        Arrays.fill(upper, index + 1, upper.length, 0);
        return upper;
    }

    private static int[] upper(int[] lower, int specified, String operator) {
        int index;
        if (operator.equals("^")) {
            index = lower[0] > 0 ? 0 : lower[1] > 0 ? 1 : Math.max(0, specified - 1);
        } else {
            index = operator.equals("~=") ? Math.max(0, specified - 2) : Math.min(1, Math.max(0, specified - 1));
        }
        int[] upper = lower.clone();
        upper[index] = Math.addExact(upper[index], 1);
        Arrays.fill(upper, index + 1, upper.length, 0);
        return upper;
    }

    private static boolean prefix(int[] actual, int[] lower, int count) {
        for (int index = 0; index < count; index++) {
            if (actual[index] != lower[index]) {
                return false;
            }
        }
        return true;
    }

    private static int[] numbers(String version) {
        String plain = version.split("[+-]", 2)[0];
        if (!plain.matches("[0-9]+(?:\\.[0-9]+){0,2}")) {
            throw new IllegalArgumentException("工具链版本不是静态数字版本");
        }
        String[] parts = plain.split("\\.");
        int[] result = new int[3];
        for (int index = 0; index < parts.length; index++) {
            result[index] = Integer.parseInt(parts[index]);
        }
        return result;
    }
}
