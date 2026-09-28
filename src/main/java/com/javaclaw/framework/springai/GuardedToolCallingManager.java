package com.javaclaw.framework.springai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Rejects a provider's unadvertised tool batch before any callback can run. */
final class GuardedToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate;
    private final ModelStepJournal journal;

    GuardedToolCallingManager(ToolCallingManager delegate, ModelStepJournal journal) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        AssistantMessage assistant = response.getResults().stream()
                .map(generation -> generation.getOutput())
                .filter(output -> !output.getToolCalls().isEmpty())
                .findFirst().orElse(null);
        if (assistant == null) return delegate.executeToolCalls(prompt, response);

        Set<String> offered = prompt.getOptions() instanceof ToolCallingChatOptions options
                && options.getToolCallbacks() != null
                ? options.getToolCallbacks().stream()
                        .map(callback -> callback.getToolDefinition().name())
                        .collect(Collectors.toSet())
                : Set.of();
        if (assistant.getToolCalls().stream().allMatch(call -> offered.contains(call.name()))) {
            return delegate.executeToolCalls(prompt, response);
        }
        return journal.rejectUnavailableToolBatch(prompt, assistant);
    }
}
