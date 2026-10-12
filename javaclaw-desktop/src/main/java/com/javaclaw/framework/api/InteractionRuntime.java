package com.javaclaw.framework.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** 交互执行器的公开身份与工具装配契约；仅描述请求，不授予权限或清除效果保护。 */
public final class InteractionRuntime {
    public static final String AGENT_ID = "system.interaction-executor";
    public static final String PROFILE_ID = "interaction-executor";
    public static final String SOURCE_TYPE = "interaction";
    public static final String ALLOWED_MODES_ATTRIBUTE = "framework.interaction.allowedModes";
    public static final String PARENT_SOURCE_ATTRIBUTE = "framework.interaction.parentSource";
    public static final String PARENT_SCOPE_ATTRIBUTE = "framework.interaction.parentScope";

    private InteractionRuntime() { }

    public static boolean isInteraction(RunRequest request) {
        return request != null && AGENT_ID.equals(request.agent().id())
                && PROFILE_ID.equals(request.profile().id()) && SOURCE_TYPE.equals(request.source().kind());
    }

    public static boolean isMain(RunRequest request) {
        return request != null && !isInteraction(request) && CapabilityRuntime.enabled(request, "interaction.run");
    }

    public static Set<InteractionMode> allowedModes(RunRequest request) {
        if (!isInteraction(request)) return Set.of();
        JsonNode modes = request.attributes().get(ALLOWED_MODES_ATTRIBUTE);
        if (modes == null || !modes.isArray()) return Set.of();
        EnumSet<InteractionMode> result = EnumSet.noneOf(InteractionMode.class);
        for (JsonNode value : modes) {
            if (!value.isTextual()) continue;
            try {
                InteractionMode mode = InteractionMode.valueOf(value.asText());
                if (mode.executable()) result.add(mode);
            } catch (IllegalArgumentException invalid) {
                // 未识别模式不进入可执行集合。
            }
        }
        return Set.copyOf(result);
    }

    public static RunScope childScope(RunScope parent) {
        String identity = parent.workspaceId() + "\0" + parent.userId() + "\0" + parent.sessionId()
                + "\0interaction-executor";
        return new RunScope(parent.workspaceId(), parent.userId(), "child:" +
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)));
    }
}
