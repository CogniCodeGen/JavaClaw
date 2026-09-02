package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;

/** 按 Unicode 边界生成可重建的固定重叠文本块。 */
final class KnowledgeChunker {
    private KnowledgeChunker() {}

    static List<String> split(String text, int maximumCharacters, int overlapCharacters) {
        String checked = text == null ? "" : text.strip();
        if (checked.isEmpty()) {
            return List.of("（未提取到可检索文本）");
        }
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < checked.length()) {
            int end = safeEnd(checked, start, maximumCharacters);
            result.add(checked.substring(start, end));
            if (end == checked.length()) {
                break;
            }
            int next = Math.max(start + 1, end - overlapCharacters);
            start = safeStart(checked, next);
        }
        return List.copyOf(result);
    }

    private static int safeEnd(String text, int start, int maximumCharacters) {
        int end = Math.min(text.length(), start + maximumCharacters);
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static int safeStart(String text, int start) {
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) {
            return start + 1;
        }
        return start;
    }
}
