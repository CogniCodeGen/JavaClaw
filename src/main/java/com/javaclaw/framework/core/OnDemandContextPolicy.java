package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** 按需发现与选择上下文时冻结在执行计划中的限制。 */
public record OnDemandContextPolicy(
        int searches,
        int fetches,
        int candidates,
        int plannerInputChars,
        int selectedBodyChars,
        int selectedTools,
        int toolSelectionVersion) {

    /** 当前计划按候选 ID 选择工具。 */
    public static final int CANDIDATE_TOOL_SELECTION_VERSION = 2;

    /** 新编译的按需执行计划使用的默认限制。 */
    public static final OnDemandContextPolicy DEFAULT = new OnDemandContextPolicy(
            2, 3, 32, 8_000, 12_000, 8, CANDIDATE_TOOL_SELECTION_VERSION);

    /** 使用当前候选工具选择格式创建计划。 */
    public OnDemandContextPolicy(int searches, int fetches, int candidates,
            int plannerInputChars, int selectedBodyChars, int selectedTools) {
        this(searches, fetches, candidates, plannerInputChars, selectedBodyChars,
                selectedTools, CANDIDATE_TOOL_SELECTION_VERSION);
    }

    public OnDemandContextPolicy {
        if (searches < 1 || searches > 32
                || fetches < 1 || fetches > 64
                || candidates < 1 || candidates > 256
                || plannerInputChars < 1_000 || plannerInputChars > 200_000
                || selectedBodyChars < 1_000 || selectedBodyChars > 200_000
                || selectedTools < 1 || selectedTools > 256
                || toolSelectionVersion != CANDIDATE_TOOL_SELECTION_VERSION) {
            throw new IllegalArgumentException("on-demand context policy value is outside the safe range");
        }
    }

    /** 从已编译的 context.on_demand 能力配置生成策略。 */
    public static OnDemandContextPolicy from(JsonNode configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.isObject()) return DEFAULT;
        return new OnDemandContextPolicy(
                integer(configuration, "searches", DEFAULT.searches()),
                integer(configuration, "fetches", DEFAULT.fetches()),
                integer(configuration, "candidates", DEFAULT.candidates()),
                integer(configuration, "plannerInputChars", DEFAULT.plannerInputChars()),
                integer(configuration, "selectedBodyChars", DEFAULT.selectedBodyChars()),
                integer(configuration, "selectedTools", DEFAULT.selectedTools()),
                CANDIDATE_TOOL_SELECTION_VERSION);
    }

    private static int integer(JsonNode configuration, String name, int fallback) {
        JsonNode value = configuration.get(name);
        if (value == null || !value.isIntegralNumber()) return fallback;
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(name + " exceeds integer range");
        }
        return value.intValue();
    }
}
