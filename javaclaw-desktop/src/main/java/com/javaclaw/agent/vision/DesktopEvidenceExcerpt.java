package com.javaclaw.agent.vision;

import java.util.Locale;

/** 仅从候选已经给出的内容中选取可在同帧 OCR 原文找到的有界摘录。 */
final class DesktopEvidenceExcerpt {
    static final int MAX_CANDIDATE_LENGTH = 4_000;
    private static final int MAX_EXCERPT_LENGTH = 500;
    private static final int MIN_REPAIRED_CHARACTERS = 16;
    private static final int MAX_SEGMENTS = 32;

    static String supportedExcerpt(String candidate, String visibleText) {
        String raw = candidate.strip();
        if (raw.isBlank() || raw.length() > MAX_CANDIDATE_LENGTH) return null;
        if (containsVisibleText(visibleText, raw)) return boundedExcerpt(raw);
        // 过长表格可能省去中间的分组标题，只能保留候选开头已逐字匹配的连续部分。
        if (raw.length() > MAX_EXCERPT_LENGTH) {
            String prefix = literalPrefix(raw, visibleText);
            if (prefix != null) return prefix;
        }
        String[] segments = raw.split("[|;；\\r\\n]+", MAX_SEGMENTS + 1);
        if (segments.length > MAX_SEGMENTS) return null;
        String selected = null;
        for (String segment : segments) {
            String excerpt = segment.strip();
            if (!hasSubstantialExcerpt(excerpt)
                    || !containsVisibleText(visibleText, excerpt)) continue;
            excerpt = boundedExcerpt(excerpt);
            if (selected == null || excerpt.length() > selected.length()) selected = excerpt;
        }
        return selected;
    }

    private static String literalPrefix(String raw, String visibleText) {
        String value = normalizeSpacing(raw);
        for (int end = Math.min(value.length(), MAX_EXCERPT_LENGTH); end >= MIN_REPAIRED_CHARACTERS; end--) {
            if (end < value.length() && value.charAt(end) != ' ') continue;
            String excerpt = value.substring(0, end).strip();
            if (hasSubstantialExcerpt(excerpt)
                    && containsVisibleText(visibleText, excerpt)) return excerpt;
        }
        return null;
    }

    static boolean containsVisibleText(String visibleText, String excerpt) {
        // OCR 换行允许归一为空格；字符、标点与词序仍须来自同帧原文。
        String text = normalizeSpacing(visibleText);
        String value = normalizeSpacing(excerpt);
        return !value.isBlank() && text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }

    private static String boundedExcerpt(String raw) {
        if (raw.length() <= MAX_EXCERPT_LENGTH) return raw;
        int end = raw.lastIndexOf(' ', MAX_EXCERPT_LENGTH);
        if (end < MIN_REPAIRED_CHARACTERS) end = MAX_EXCERPT_LENGTH;
        if (Character.isHighSurrogate(raw.charAt(end - 1))) end--;
        return raw.substring(0, end).strip();
    }

    private static long nonWhitespaceCharacters(String value) {
        return value.codePoints().filter(character -> !Character.isWhitespace(character)).count();
    }

    private static boolean hasSubstantialExcerpt(String value) {
        return nonWhitespaceCharacters(value) >= MIN_REPAIRED_CHARACTERS
                && normalizeSpacing(value).contains(" ");
    }

    private static String normalizeSpacing(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private DesktopEvidenceExcerpt() { }
}
