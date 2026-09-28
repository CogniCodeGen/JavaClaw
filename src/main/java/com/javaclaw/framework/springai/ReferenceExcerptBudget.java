package com.javaclaw.framework.springai;

import java.util.List;

/** 为选中的参考资料分配正文预算，并在截断时保留首尾内容。 */
final class ReferenceExcerptBudget {
    private static final int MIN_TRUNCATED_REFERENCE_CHARS = 96;

    private ReferenceExcerptBudget() { }

    static int[] quotas(List<Integer> lengths, int budget) {
        int[] quotas = new int[lengths.size()];
        boolean[] complete = new boolean[lengths.size()];
        int remaining = budget;
        int open = lengths.size();
        while (open > 0) {
            int share = remaining / open;
            boolean filled = false;
            for (int index = 0; index < lengths.size(); index++) {
                if (complete[index] || lengths.get(index) > share) continue;
                quotas[index] = lengths.get(index);
                remaining -= quotas[index];
                complete[index] = true;
                open--;
                filled = true;
            }
            if (filled) continue;
            for (int index = 0; index < lengths.size(); index++) {
                if (complete[index]) continue;
                quotas[index] = share;
                remaining -= share;
            }
            for (int index = 0; index < lengths.size() && remaining > 0; index++) {
                if (complete[index]) continue;
                quotas[index]++;
                remaining--;
            }
            break;
        }
        for (int index = 0; index < lengths.size(); index++) {
            if (lengths.get(index) > quotas[index]
                    && quotas[index] < MIN_TRUNCATED_REFERENCE_CHARS) {
                throw new ContextPlanningRequiredException(
                        "selected reference count cannot fit meaningful excerpts within the per-Step character limit");
            }
        }
        return quotas;
    }

    static String truncate(String body, int limit) {
        String marker = "\n[Reference content truncated; middle omitted]\n";
        int keep = limit - marker.length();
        if (keep < 2) {
            throw new ContextPlanningRequiredException(
                    "selected reference excerpt cannot fit its truncation marker");
        }
        int front = (keep + 1) / 2;
        int back = keep - front;
        int backStart = body.length() - back;
        if (front > 0 && front < body.length()
                && Character.isHighSurrogate(body.charAt(front - 1))
                && Character.isLowSurrogate(body.charAt(front))) front--;
        if (backStart > 0 && backStart < body.length()
                && Character.isHighSurrogate(body.charAt(backStart - 1))
                && Character.isLowSurrogate(body.charAt(backStart))) backStart++;
        return body.substring(0, front) + marker + body.substring(backStart);
    }
}
