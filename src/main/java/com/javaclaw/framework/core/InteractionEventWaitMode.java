package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

/** 区分有界操作等待与持续监听；持久化记录保留创建时的等待语义。 */
enum InteractionEventWaitMode {
    BOUNDED,
    UNTIL_CHANGE;

    /** 新工具调用默认有界等待，避免静止界面占满整轮任务期限。 */
    static InteractionEventWaitMode forInvocation(JsonNode input) {
        return read(input, BOUNDED);
    }

    /** 旧记录没有模式字段时沿用原有持续订阅语义。 */
    static InteractionEventWaitMode forStoredWait(JsonNode wait) {
        return read(wait, UNTIL_CHANGE);
    }

    private static InteractionEventWaitMode read(JsonNode value, InteractionEventWaitMode fallback) {
        JsonNode mode = value.get("waitMode");
        if (mode == null) return fallback;
        if (!mode.isTextual()) throw new IllegalArgumentException("invalid interaction waitMode");
        try {
            return valueOf(mode.textValue());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid interaction waitMode: " + mode.textValue(), invalid);
        }
    }
}
