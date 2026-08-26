package com.javaclaw.framework.springai;

import com.javaclaw.util.TokenEstimator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/** Canonical text-token estimate shared by prompt observability and regression benchmarks. */
public final class PromptTokenEstimator {
    private static final String CONTEXT_SUMMARY_PREFIX =
            "Conversation summary (structured, persisted):";

    private PromptTokenEstimator() { }

    public static Breakdown estimate(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        int currentUserIndex = -1;
        for (int index = instructions.size() - 1; index >= 0; index--) {
            if (instructions.get(index).getMessageType() == MessageType.USER) {
                currentUserIndex = index;
                break;
            }
        }

        int systemTokens = 0;
        int historyTokens = 0;
        int currentInputTokens = 0;
        int toolResultTokens = 0;
        int continuationTokens = 0;
        int contextSummaryTokens = 0;
        int messageTokens = 0;
        StringBuilder systemText = new StringBuilder();
        for (int index = 0; index < instructions.size(); index++) {
            Message message = instructions.get(index);
            int tokens = estimateMessage(message);
            messageTokens += tokens;
            if (message.getMessageType() == MessageType.SYSTEM
                    && safeText(message).startsWith(CONTEXT_SUMMARY_PREFIX)) {
                historyTokens += tokens;
                contextSummaryTokens += tokens;
            } else if (message.getMessageType() == MessageType.SYSTEM) {
                systemTokens += tokens;
                systemText.append(safeText(message)).append('\n');
            } else if (index < currentUserIndex
                    && (message.getMessageType() == MessageType.USER
                    || message.getMessageType() == MessageType.ASSISTANT)) {
                historyTokens += tokens;
            } else if (index == currentUserIndex) {
                currentInputTokens += tokens;
            } else if (message.getMessageType() == MessageType.TOOL) {
                toolResultTokens += tokens;
            } else if (index > currentUserIndex) {
                continuationTokens += tokens;
            }
        }

        int toolSchemaTokens = 0;
        int toolCount = 0;
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            toolCount = options.getToolCallbacks().size();
            toolSchemaTokens = options.getToolCallbacks().stream()
                    .map(ToolCallback::getToolDefinition)
                    .map(definition -> definition.name() + "\n" + definition.description()
                            + "\n" + definition.inputSchema())
                    .mapToInt(TokenEstimator::estimate)
                    .sum();
        }
        int skillTokens = estimateSkillCatalog(systemText.toString());
        return new Breakdown(
                systemTokens,
                skillTokens,
                Math.max(0, systemTokens - skillTokens),
                historyTokens,
                contextSummaryTokens,
                currentInputTokens,
                toolResultTokens,
                continuationTokens,
                toolSchemaTokens,
                messageTokens + toolSchemaTokens,
                toolCount);
    }

    static int estimateMessage(Message message) {
        int tokens = TokenEstimator.estimate(safeText(message));
        if (message instanceof AssistantMessage assistant) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                tokens += TokenEstimator.estimate(
                        call.id() + "\n" + call.type() + "\n"
                                + call.name() + "\n" + call.arguments());
            }
        } else if (message instanceof ToolResponseMessage response) {
            for (ToolResponseMessage.ToolResponse tool : response.getResponses()) {
                tokens += TokenEstimator.estimate(
                        tool.id() + "\n" + tool.name() + "\n" + tool.responseData());
            }
        }
        return tokens;
    }

    private static int estimateSkillCatalog(String systemPrompt) {
        StringBuilder sections = new StringBuilder();
        boolean skillSection = false;
        for (String line : systemPrompt.split("\\R", -1)) {
            if (line.startsWith("## ")) skillSection = line.startsWith("## 可用技能");
            if (skillSection) sections.append(line).append('\n');
        }
        return TokenEstimator.estimate(sections.toString());
    }

    private static String safeText(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }

    public record Breakdown(
            int systemPromptTokens,
            int skillCatalogTokens,
            int systemWithoutSkillTokens,
            int historyTokens,
            int contextSummaryTokens,
            int currentInputTokens,
            int toolResultTokens,
            int continuationTokens,
            int toolSchemaTokens,
            int totalInputTokens,
            int toolCount) { }
}
