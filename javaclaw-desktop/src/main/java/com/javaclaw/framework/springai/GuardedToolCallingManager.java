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
        List<AssistantMessage> outputs = response.getResults().stream()
                .map(generation -> generation.getOutput())
                .toList();
        List<AssistantMessage.ToolCall> calls = outputs.stream()
                .flatMap(output -> output.getToolCalls().stream()).toList();
        long decisions = calls.stream()
                .filter(call -> HarnessDecisionToolCallback.NAME.equals(call.name())).count();
        if (decisions > 0 && outputs.size() != 1) {
            // Only the first generation is durably journaled. No correction can
            // safely replay a rejected batch spread across multiple results.
            throw new ProtocolBatchException(false);
        }
        if (decisions > 0 && (decisions != 1 || calls.size() != 1)) {
            throw new ProtocolBatchException(true);
        }
        AssistantMessage assistant = outputs.stream()
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
            try {
                return journal.afterToolBatch(delegate.executeToolCalls(prompt, response));
            } catch (RuntimeException failure) {
                // A later call can fail after an earlier click was delivered. Persist
                // its read-only follow-up without hiding the original batch failure.
                try { journal.observeCompletedClicks(); }
                catch (RuntimeException observationFailure) {
                    if (failure != observationFailure) failure.addSuppressed(observationFailure);
                }
                throw failure;
            }
        }
        return journal.rejectUnavailableToolBatch(prompt, assistant);
    }

    /** A rejected control batch has executed no callbacks and may receive one correction. */
    static final class ProtocolBatchException extends RuntimeException {
        private final boolean recoverable;

        ProtocolBatchException(boolean recoverable) {
            super("harness_submit_decision must be the only call in one provider result");
            this.recoverable = recoverable;
        }

        boolean recoverable() { return recoverable; }
    }
}
