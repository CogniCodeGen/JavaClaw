package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.InteractionRuntime;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.api.TaskContractV3;
import com.javaclaw.framework.api.TaskCriterionV3;

import java.util.List;
import java.util.Set;

/** Shared fail-closed role and active-backend rules; this policy never changes effect fences. */
public final class InteractionExecutionPolicy {
    public static final String AGENT_ID = InteractionRuntime.AGENT_ID;
    public static final String PROFILE_ID = InteractionRuntime.PROFILE_ID;
    public static final String SOURCE_TYPE = InteractionRuntime.SOURCE_TYPE;
    public static final String TASK_ATTRIBUTE = "framework.interaction.task";
    public static final String ALLOWED_MODES_ATTRIBUTE = InteractionRuntime.ALLOWED_MODES_ATTRIBUTE;
    public static final String INITIAL_MODE_ATTRIBUTE = "framework.interaction.initialMode";
    public static final String PARENT_SOURCE_ATTRIBUTE = InteractionRuntime.PARENT_SOURCE_ATTRIBUTE;
    public static final String PARENT_SCOPE_ATTRIBUTE = InteractionRuntime.PARENT_SCOPE_ATTRIBUTE;
    public static final String MODE_SELECTED_EVENT = "core.interaction.mode_selected";
    public static final String DELEGATE_TOOL = "interaction_delegate";
    public static final String SELECT_MODE_TOOL = "interaction_select_mode";
    public static final String WAIT_EVENT_TOOL = "interaction_wait_event";
    public static final String READ_RESULT_TOOL = "interaction_read_result";
    public static final String TOOL_GROUP = "interaction";

    private InteractionExecutionPolicy() {}

    public static boolean isInteraction(RunRequest request) {
        return InteractionRuntime.isInteraction(request);
    }

    public static boolean isMain(RunRequest request) {
        return InteractionRuntime.isMain(request);
    }

    /** 统一识别需要交互执行器接管的宿主能力。 */
    public static boolean isInteractionCriterion(String capabilityId) {
        return capabilityId != null
                && (capabilityId.startsWith("browser.") || capabilityId.startsWith("desktop."));
    }

    /** 保留冻结合同中的原始条件顺序，缺少合同时不授予委派入口。 */
    public static List<TaskCriterionV3> interactionCriteria(TaskContractV3 contract) {
        return contract == null ? List.of() : contract.criteria().stream()
                .filter(criterion -> isInteractionCriterion(criterion.capabilityId())).toList();
    }

    /** One child owns one contiguous block; delegation must not reorder the parent's criteria. */
    public static void requireContiguousInteractionBlock(TaskContractV3 contract) {
        boolean seenInteraction = false;
        boolean blockClosed = false;
        for (var criterion : contract.criteria()) {
            boolean interaction = isInteractionCriterion(criterion.capabilityId());
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
        return InteractionRuntime.allowedModes(request);
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
            return !com.javaclaw.agent.ToolRiskRegistry.isHostInteractionTool(tool)
                    && !group.equals("web") && !group.equals("desktop-session")
                    && !Set.of("sys_mouse_move", "sys_mouse_click", "sys_mouse_click_at", "sys_mouse_scroll",
                            "sys_key_type", "sys_key_press", "sys_key_combo", "sys_screenshot").contains(tool)
                    && !tool.equals(SELECT_MODE_TOOL)
                    && !tool.equals(WAIT_EVENT_TOOL);
        }
        if ("ask_user_clarification".equals(tool) && "agents".equals(group)) return true;
        if (SELECT_MODE_TOOL.equals(tool) && TOOL_GROUP.equals(group)) return true;
        InteractionMode active = activeMode(request, events);
        if (WAIT_EVENT_TOOL.equals(tool) && TOOL_GROUP.equals(group)) {
            return active == InteractionMode.DESKTOP;
        }
        return active == InteractionMode.BROWSER && browserTool(tool) && group.equals("web")
                || active == InteractionMode.DESKTOP
                        && com.javaclaw.agent.ToolRiskRegistry.isHostToolInGroup(tool, "desktop-session")
                        && group.equals("desktop-session");
    }

    private static boolean browserTool(String tool) {
        return com.javaclaw.agent.ToolRiskRegistry.isHostToolInGroup(tool, "web");
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
