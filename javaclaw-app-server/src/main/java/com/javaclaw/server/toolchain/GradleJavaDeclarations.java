package com.javaclaw.server.toolchain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 只识别静态 Gradle JDK/编译目标声明；注释和字符串中的示例不能成为执行要求。 */
final class GradleJavaDeclarations {
    private static final String NUMBER = "(?<value>[0-9]+)";
    private static final String JAVA_CALL = "JavaLanguageVersion\\.of\\s*\\(\\s*" + NUMBER + "\\s*\\)";
    private static final String TARGET =
            "(?<value>JavaVersion\\.VERSION_(?:1_)?[0-9]+" + "|\"(?:1\\.)?[0-9]+\"|'(?:1\\.)?[0-9]+'|(?:1\\.)?[0-9]+)";
    private static final Pattern NON_CODE =
            Pattern.compile("(?s)//[^\\r\\n]*|/\\*.*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final List<Rule> RULES = List.of(
            rule("\\blanguageVersion\\s*=\\s*" + JAVA_CALL, true),
            rule("\\blanguageVersion\\s*\\.set\\s*\\(\\s*" + JAVA_CALL + "\\s*\\)", true),
            rule("\\bjvmToolchain\\s*\\(\\s*" + NUMBER + "\\s*\\)", true),
            rule("\\b(?:sourceCompatibility|targetCompatibility|options\\.release)\\s*=\\s*" + TARGET, false),
            rule("\\boptions\\.release\\s*\\.set\\s*\\(\\s*" + NUMBER + "\\s*\\)", false));
    private static final Pattern UNRESOLVED = Pattern.compile(
            "\\b(?:languageVersion|jvmToolchain|sourceCompatibility|targetCompatibility|options\\.release)\\b");

    private GradleJavaDeclarations() {}

    static List<String> parse(String source) {
        StringBuilder remaining = new StringBuilder(source);
        var nonCode = NON_CODE.matcher(source);
        while (nonCode.find()) {
            clear(remaining, nonCode.start(), nonCode.end());
        }
        String masked = remaining.toString();
        List<String> constraints = new ArrayList<>();
        for (Rule rule : RULES) {
            var matcher = rule.pattern().matcher(source);
            while (matcher.find()) {
                if (remaining.charAt(matcher.start()) != ' ') {
                    requireEnd(masked, matcher.end());
                    String value = matcher.group("value")
                            .replace("JavaVersion.VERSION_", "")
                            .replace("\"", "")
                            .replace("'", "")
                            .replaceFirst("^1[_.]", "");
                    constraints.add((rule.exact() ? "" : ">=") + value);
                    clear(remaining, matcher.start(), matcher.end());
                }
            }
        }
        if (UNRESOLVED.matcher(remaining).find()) {
            throw new IllegalArgumentException("Gradle 版本表达式需要执行求值，不能从其他静态声明推断");
        }
        return List.copyOf(constraints);
    }

    private static void requireEnd(String source, int offset) {
        for (int index = offset; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '\n' || value == '\r' || value == ';' || value == '}') {
                return;
            }
            if (!Character.isWhitespace(value)) {
                throw new IllegalArgumentException("静态版本后存在动态表达式");
            }
        }
    }

    private static void clear(StringBuilder value, int start, int end) {
        for (int index = start; index < end; index++) {
            if (value.charAt(index) != '\n' && value.charAt(index) != '\r') {
                value.setCharAt(index, ' ');
            }
        }
    }

    private static Rule rule(String expression, boolean exact) {
        return new Rule(Pattern.compile(expression), exact);
    }

    private record Rule(Pattern pattern, boolean exact) {}
}
