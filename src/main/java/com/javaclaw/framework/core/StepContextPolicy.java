package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/** Immutable limits for the model-visible context of one provider Step. */
public record StepContextPolicy(
        int maxMessageCharacters,
        int maxToolSchemaCharacters,
        int retainedToolExchanges,
        int maxToolResultCharacters,
        int maxTools) {

    /** 启用上下文裁剪且未覆盖限制时使用的默认值。 */
    public static final StepContextPolicy DEFAULT = new StepContextPolicy(
            48_000, 48_000, 4, 16_000, 64);

    public StepContextPolicy {
        if (maxMessageCharacters < 4_000 || maxMessageCharacters > 200_000
                || maxToolSchemaCharacters < 4_000 || maxToolSchemaCharacters > 200_000
                || retainedToolExchanges < 1 || retainedToolExchanges > 32
                || maxToolResultCharacters < 1_000 || maxToolResultCharacters > 100_000
                || maxTools < 1 || maxTools > 256) {
            throw new IllegalArgumentException("context policy value is outside the safe range");
        }
    }

    /** Builds a bounded policy from a compiled capability configuration. */
    public static StepContextPolicy from(JsonNode configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.isObject()) return DEFAULT;
        return new StepContextPolicy(
                integer(configuration, "maxMessageCharacters", DEFAULT.maxMessageCharacters()),
                integer(configuration, "maxToolSchemaCharacters", DEFAULT.maxToolSchemaCharacters()),
                integer(configuration, "retainedToolExchanges", DEFAULT.retainedToolExchanges()),
                integer(configuration, "maxToolResultCharacters", DEFAULT.maxToolResultCharacters()),
                integer(configuration, "maxTools", DEFAULT.maxTools()));
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
