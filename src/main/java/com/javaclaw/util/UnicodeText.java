package com.javaclaw.util;

/** UTF-16 slicing helpers that never create a new unpaired surrogate at a boundary. */
public final class UnicodeText {
    private UnicodeText() { }

    public static String prefix(String value, int maxCharacters) {
        if (value == null || value.isEmpty() || maxCharacters <= 0) return "";
        if (value.length() <= maxCharacters) return value;
        return value.substring(0, safePrefixEnd(value, maxCharacters));
    }

    public static String suffix(String value, int maxCharacters) {
        if (value == null || value.isEmpty() || maxCharacters <= 0) return "";
        if (value.length() <= maxCharacters) return value;
        int start = safeSuffixStart(value, value.length() - maxCharacters);
        return value.substring(start);
    }

    public static String dropFirstCodePoint(String value) {
        if (value == null || value.isEmpty()) return "";
        int end = Character.isHighSurrogate(value.charAt(0))
                && value.length() > 1
                && Character.isLowSurrogate(value.charAt(1)) ? 2 : 1;
        return value.substring(end);
    }

    public static int safePrefixEnd(String value, int requestedEnd) {
        int end = Math.max(0, Math.min(value.length(), requestedEnd));
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return end;
    }

    public static int safeSuffixStart(String value, int requestedStart) {
        int start = Math.max(0, Math.min(value.length(), requestedStart));
        if (start > 0 && start < value.length()
                && Character.isHighSurrogate(value.charAt(start - 1))
                && Character.isLowSurrogate(value.charAt(start))) {
            start++;
        }
        return start;
    }
}
