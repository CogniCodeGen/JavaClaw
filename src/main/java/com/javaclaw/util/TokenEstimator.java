package com.javaclaw.util;

/** Conservative provider-neutral estimator used only for local prompt budgeting. */
public final class TokenEstimator {
    private TokenEstimator() { }

    public static int estimate(String value) {
        if (value == null || value.isEmpty()) return 0;
        int tokens = 0;
        int asciiRun = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch <= 0x7f) {
                asciiRun++;
            } else {
                if (asciiRun > 0) {
                    tokens += (asciiRun + 3) / 4;
                    asciiRun = 0;
                }
                tokens++;
            }
        }
        if (asciiRun > 0) tokens += (asciiRun + 3) / 4;
        return tokens;
    }

    public static String truncateToTokens(String value, int maxTokens) {
        if (value == null || value.isEmpty() || maxTokens < 0) return value == null ? "" : value;
        if (estimate(value) <= maxTokens) return value;
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (estimate(value.substring(0, mid)) <= maxTokens) low = mid;
            else high = mid - 1;
        }
        int end = UnicodeText.safePrefixEnd(value, low);
        return value.substring(0, end);
    }
}
