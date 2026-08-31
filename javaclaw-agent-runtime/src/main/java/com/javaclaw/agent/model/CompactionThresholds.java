package com.javaclaw.agent.model;

import java.util.OptionalLong;

import com.javaclaw.core.api.TurnConfig;

/** Profile-driven context-window validation shared by native and summary compaction paths. */
public final class CompactionThresholds {
    private CompactionThresholds() {}

    /**
     * 返回经校验的自动压缩阈值；未知窗口或显式零关闭自动压缩，缺省阈值取窗口的 80%。
     *
     * @throws IllegalArgumentException 数值无效，或正阈值不小于模型窗口
     */
    public static OptionalLong autoThreshold(TurnConfig config) {
        String windowValue = config.attributes().get("modelContextWindowTokens");
        if (windowValue == null || windowValue.isBlank()) {
            return OptionalLong.empty();
        }
        long window = positive(windowValue, "modelContextWindowTokens");
        String thresholdValue = config.attributes().get("modelAutoCompactTokenLimit");
        long threshold = thresholdValue == null || thresholdValue.isBlank()
                ? Math.max(1, (window / 5) * 4 + ((window % 5) * 4) / 5)
                : nonNegative(thresholdValue, "modelAutoCompactTokenLimit");
        if (threshold == 0) {
            return OptionalLong.empty();
        }
        if (threshold >= window) {
            throw new IllegalArgumentException(
                    "modelAutoCompactTokenLimit must be smaller than modelContextWindowTokens");
        }
        return OptionalLong.of(threshold);
    }

    /** 校验可选 Profile 属性；写入和解析路径均调用，避免非法阈值延迟到模型请求才暴露。 */
    public static void validate(java.util.Map<String, String> attributes) {
        TurnConfigProbe probe = new TurnConfigProbe(attributes);
        probe.validate();
    }

    private static long positive(String value, String name) {
        long parsed = nonNegative(value, name);
        if (parsed == 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static long nonNegative(String value, String name) {
        try {
            long parsed = Long.parseLong(value.strip());
            if (parsed < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    private record TurnConfigProbe(java.util.Map<String, String> attributes) {
        private void validate() {
            String windowValue = attributes.get("modelContextWindowTokens");
            String thresholdValue = attributes.get("modelAutoCompactTokenLimit");
            if (windowValue == null || windowValue.isBlank()) {
                if (thresholdValue != null && !thresholdValue.isBlank()) {
                    nonNegative(thresholdValue, "modelAutoCompactTokenLimit");
                }
                return;
            }
            long window = positive(windowValue, "modelContextWindowTokens");
            if (thresholdValue == null || thresholdValue.isBlank()) {
                return;
            }
            long threshold = nonNegative(thresholdValue, "modelAutoCompactTokenLimit");
            if (threshold >= window && threshold != 0) {
                throw new IllegalArgumentException(
                        "modelAutoCompactTokenLimit must be smaller than modelContextWindowTokens");
            }
        }
    }
}
