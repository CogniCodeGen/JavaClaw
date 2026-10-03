package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

/** Stable provider feedback and historical variants for rejected unavailable-tool batches. */
final class UnavailableToolFeedback {
    private UnavailableToolFeedback() { }

    static JsonNode current(
            AssistantMessage.ToolCall call, List<String> offeredTools) {
        var feedback = JsonNodeFactory.instance.objectNode()
                .put("error", "tool_not_offered")
                .put("tool", call.name())
                .put("executed", false)
                .put("feedbackScope", "previous_provider_step")
                .put("message", "No calls in this batch were executed. offeredTools is a historical "
                        + "record of the failed step, NOT the current tool directory. Follow the "
                        + "current framework tool manifest and provider schemas, including their "
                        + "exact parameter names. Select a fresh call from that current directory; "
                        + "do not replay the rejected batch. Use framework_tool_catalog only when "
                        + "currently offered. This rejection is not a permission or platform failure.");
        var names = feedback.putArray("offeredTools");
        offeredTools.forEach(names::add);
        return feedback;
    }

    /** Preserve exact validation of feedback saved before the current-step manifest existed. */
    static JsonNode previous(
            AssistantMessage.ToolCall call, List<String> offeredTools) {
        var feedback = JsonNodeFactory.instance.objectNode()
                .put("error", "tool_not_offered")
                .put("tool", call.name())
                .put("executed", false)
                .put("message", "At least one tool in this batch was not offered for this step. "
                        + "No calls in the batch were executed. Only the tools in offeredTools "
                        + "were offered in the failed step. In the next step, use only tools "
                        + "offered in that step's provider prompt. Call framework_tool_catalog "
                        + "to activate another tool only if framework_tool_catalog is offered "
                        + "in that step. This does not mean the tool or desktop capability is "
                        + "unavailable. Do not claim a permission or platform failure without "
                        + "a corresponding tool result.");
        var names = feedback.putArray("offeredTools");
        offeredTools.forEach(names::add);
        return feedback;
    }

    /** Preserve replay of rejected batches persisted before offeredTools feedback existed. */
    static JsonNode legacy(AssistantMessage.ToolCall call) {
        return JsonNodeFactory.instance.objectNode()
                .put("error", "tool_not_offered")
                .put("tool", call.name())
                .put("message", "At least one tool in this batch was not offered for this step. "
                        + "No calls in the batch were executed. This does not mean the tool or "
                        + "desktop capability is unavailable: select or activate the authorized "
                        + "tool for the next step. Do not claim a permission or platform failure "
                        + "without a corresponding tool result.");
    }

}
