package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.util.SensitiveDataRedactor;

/** 有界且脱敏的业务草稿；其内容不能作为句柄、验收证据或授权。 */
public final class InteractionBusinessCheckpoint {
    public static final int MAX_CHARACTERS = 4_000;

    private InteractionBusinessCheckpoint() {}

    /** 校验宿主入参和历史投影，防止任一入口绕过大小与秘密处理。 */
    public static JsonNode sanitize(JsonNode checkpoint) {
        if (checkpoint == null || checkpoint.isNull()) return null;
        if (!checkpoint.isObject() || checkpoint.toString().length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("checkpoint must be a business JSON object of at most 4000 characters");
        if (rawImage(checkpoint))
            throw new IllegalArgumentException("checkpoint cannot contain raw screenshots");
        JsonNode sanitized = SensitiveDataRedactor.redactJson(checkpoint);
        if (sanitized.toString().length() > MAX_CHARACTERS)
            throw new IllegalArgumentException("redacted checkpoint exceeds 4000 characters");
        return sanitized;
    }

    private static boolean rawImage(JsonNode value) {
        if (value.isTextual()) {
            String text = value.asText().stripLeading();
            return text.toLowerCase(java.util.Locale.ROOT).contains("data:image/")
                    || text.startsWith("iVBORw0KGgo") || text.startsWith("/9j/")
                    || text.startsWith("R0lGOD") || text.startsWith("UklGR");
        }
        if (value.isContainerNode()) {
            for (JsonNode child : value) if (rawImage(child)) return true;
        }
        return false;
    }
}
