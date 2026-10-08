package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.CapabilityRuntime;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.TaskContractV3;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Shared fail-closed role and active-backend rules; this policy never changes effect fences. */
public final class InteractionExecutionPolicy {
    public static final String AGENT_ID = "system.interaction-executor";
    public static final String PROFILE_ID = "interaction-executor";
    public static final String SOURCE_TYPE = "interaction";
    public static final String TASK_ATTRIBUTE = "framework.interaction.task";
    public static final String ALLOWED_MODES_ATTRIBUTE = "framework.interaction.allowedModes";
    public static final String INITIAL_MODE_ATTRIBUTE = "framework.interaction.initialMode";
    public static final String PARENT_SOURCE_ATTRIBUTE = "framework.interaction.parentSource";
    public static final String PARENT_SCOPE_ATTRIBUTE = "framework.interaction.parentScope";
    public static final String MODE_SELECTED_EVENT = "core.interaction.mode_selected";
    public static final String DELEGATE_TOOL = "interaction_delegate";
    public static final String SELECT_MODE_TOOL = "interaction_select_mode";
    public static final String WAIT_EVENT_TOOL = "interaction_wait_event";
    public static final String READ_RESULT_TOOL = "interaction_read_result";
    public static final String TOOL_GROUP = "interaction";

    private InteractionExecutionPolicy() {}

    public static boolean isInteraction(RunRequest request) {
        return request != null && AGENT_ID.equals(request.agent().id())
                && PROFILE_ID.equals(request.profile().id())
                && SOURCE_TYPE.equals(request.source().kind());
    }

    public static boolean isMain(RunRequest request) {
        return request != null && !isInteraction(request)
                && CapabilityRuntime.enabled(request, "interaction.run");
    }

    /** One child owns one contiguous block; delegation must not reorder the parent's criteria. */
    public static void requireContiguousInteractionBlock(TaskContractV3 contract) {
        boolean seenInteraction = false;
        boolean blockClosed = false;
        for (var criterion : contract.criteria()) {
            boolean interaction = criterion.capabilityId().startsWith("browser.")
                    || criterion.capabilityId().startsWith("desktop.");
            if (interaction) {
                if (blockClosed) throw new SecurityException("INTERACTION_CONTRACT_INTERLEAVED: "
                        + "browser and desktop criteria must form one contiguous block");
                seenInteraction = true;
            } else if (seenInteraction) {
                blockClosed = true;
            }
        }
    }

    public static Set<InteractionMode> allowedModes(RunRequest request) {
        if (!isInteraction(request)) return Set.of();
        JsonNode value = request.attributes().get(ALLOWED_MODES_ATTRIBUTE);
        if (value == null || !value.isArray()) return Set.of();
        EnumSet<InteractionMode> result = EnumSet.noneOf(InteractionMode.class);
        for (JsonNode item : value) {
            InteractionMode mode = executableMode(item);
            if (mode != null) result.add(mode);
        }
        return Set.copyOf(result);
    }

    /** Events must be the host-loaded events of this run, never model-provided history. */
    public static InteractionMode activeMode(RunRequest request, List<RunEventEnvelope> events) {
        if (!isInteraction(request)) return null;
        Set<InteractionMode> allowed = allowedModes(request);
        InteractionMode active = executableMode(request.attributes().get(INITIAL_MODE_ATTRIBUTE));
        if (active == null || !allowed.contains(active)) active = null;
        long selectedSequence = 0;
        for (RunEventEnvelope event : events == null ? List.<RunEventEnvelope>of() : events) {
            if (MODE_SELECTED_EVENT.equals(event.type()) && event.schemaVersion() == 1
                    && "framework.core".equals(event.producer()) && event.sequence() > selectedSequence) {
                InteractionMode selected = executableMode(event.payload().path("mode"));
                if (selected != null && allowed.contains(selected)) {
                    active = selected;
                    selectedSequence = event.sequence();
                }
            }
        }
        return active;
    }

    public static boolean allowsTool(RunRequest request, List<RunEventEnvelope> events,
                                     String tool, String group) {
        tool = tool == null ? "" : tool;
        group = group == null ? "" : group;
        if (!isInteraction(request)) {
            if (!isMain(request)) return true;
            return !tool.startsWith("web_") && !tool.startsWith("desktop_session_")
                    && !group.equals("web") && !group.equals("desktop-session")
                    && !tool.startsWith("sys_mouse_") && !tool.startsWith("sys_key_")
                    && !tool.equals("sys_screenshot") && !tool.equals(SELECT_MODE_TOOL)
                    && !tool.equals(WAIT_EVENT_TOOL);
        }
        if ("ask_user_clarification".equals(tool) && "agents".equals(group)) return true;
        if (SELECT_MODE_TOOL.equals(tool) && TOOL_GROUP.equals(group)) return true;
        InteractionMode active = activeMode(request, events);
        if (WAIT_EVENT_TOOL.equals(tool) && TOOL_GROUP.equals(group)) {
            return active == InteractionMode.DESKTOP;
        }
        return active == InteractionMode.BROWSER && browserTool(tool) && group.equals("web")
                || active == InteractionMode.DESKTOP && tool.startsWith("desktop_session_")
                        && group.equals("desktop-session");
    }

    private static boolean browserTool(String tool) {
        return tool.startsWith("web_") || Set.of("site_select_account", "site_login_interactive",
                "site_login_now", "site_fill_password", "site_save_session", "site_clear_session", "site_auth_check").contains(tool);
    }

    private static InteractionMode executableMode(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        try {
            InteractionMode mode = InteractionMode.valueOf(value.asText());
            return mode.executable() ? mode : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
