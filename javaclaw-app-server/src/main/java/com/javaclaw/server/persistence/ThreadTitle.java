package com.javaclaw.server.persistence;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从用户原文提取本地显示标题；不调用模型，不修改消息，按完整 grapheme 截断并遵守数据库列上限。 */
final class ThreadTitle {
    private static final int MAXIMUM_GRAPHEMES = 32;
    private static final int MAXIMUM_CODE_UNITS = 480;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern MARKDOWN_PREFIX =
            Pattern.compile("(?m)^\\h*(?:(?:#{1,6}|>+|[-*+]|[0-9]{1,9}[.)])\\h+)+");
    private static final Pattern FENCES = Pattern.compile("(?m)^\\h*(?:`{3,}|~{3,})[^\\r\\n]*(?:\\R|$)");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__|~~|`)([^\\r\\n]+?)\\1");
    private static final Pattern CHECKBOX = Pattern.compile("^\\[[ xX]\\](?:\\h+|$)");

    private ThreadTitle() {}

    static Optional<String> fromMessage(String text) {
        String plain = FENCES.matcher(text).replaceAll("");
        plain = MARKDOWN_PREFIX.matcher(plain).replaceAll("");
        plain = CHECKBOX.matcher(plain.strip()).replaceFirst("");
        plain = EMPHASIS.matcher(plain).replaceAll("$2");
        plain = WHITESPACE.matcher(plain).replaceAll(" ").strip();
        if (plain.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = GRAPHEME.matcher(plain);
        int count = 0;
        int end = 0;
        int previous = 0;
        while (matcher.find()) {
            if (count == MAXIMUM_GRAPHEMES || matcher.end() > MAXIMUM_CODE_UNITS) {
                int cut = count == MAXIMUM_GRAPHEMES ? previous : end;
                return cut == 0
                        ? Optional.empty()
                        : Optional.of(plain.substring(0, cut).stripTrailing() + "…");
            }
            previous = end;
            end = matcher.end();
            count++;
        }
        return Optional.of(plain);
    }
}
