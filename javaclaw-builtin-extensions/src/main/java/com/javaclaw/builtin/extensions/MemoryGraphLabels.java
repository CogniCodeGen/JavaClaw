package com.javaclaw.builtin.extensions;

/** 图谱标签的 UTF-16 有界显示；边界不能把有效 emoji 代理对切断为非法 JSON 文本。 */
final class MemoryGraphLabels {
    private MemoryGraphLabels() {}

    static String label(String content) {
        int end = Math.min(200, content.length());
        if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
            end--;
        }
        return content.substring(0, end);
    }
}
